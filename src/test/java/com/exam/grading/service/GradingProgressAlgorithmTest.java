package com.exam.grading.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.dto.GradingProgressResponse;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.submission.entity.ExamSubmission;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 判分进度「部分批改计数」优化等价性测试（spec changes/optimize-grading-progress-partial-algorithm）。
 *
 * <p>把<b>优化前</b>的 O(n*m) 逐答卷嵌套扫描与<b>优化后</b>的 O(n+m) 单遍哈希两种流计算逐点
 * 对照：随机规模 + 边界形状下 partial 计数必须完全一致。参考实现 {@link #partialByNestedScan}
 * 逐字保留改动前的原文，它本身就是判据。
 *
 * <p>另有一组用例驱动真实 {@code GradingQueryService.progress()}（Mockito 隔离、无 Spring
 * 上下文），断言端点返回的 {@code partialGradedCount} 与参考实现一致——避免"只在测试里复述
 * 新写法、不碰生产代码"的自说自话。
 *
 * <p><b>已披露的口径修正</b>：改动前实现对主观行 {@code submission_id == null} 会抛 NPE
 * （{@code null.equals(...)} 先于 {@code score == null} 求值），而单遍哈希对 null 键是容忍的。
 * 该输入在库内不可能出现（{@code schema.sql}：{@code subjective_grades.submission_id BIGINT
 * NOT NULL}），故两者在<b>可达域上逐点等价</b>；卡面"包含 null 键 100% 等价"的措辞对
 * "主观行为 null 键"这一越界输入不成立，证据见 {@link #nullRowKeyIsOutOfDomainDivergence()}。
 */
@ExtendWith(MockitoExtension.class)
class GradingProgressAlgorithmTest {

    private static final Long TEACHER_ID = 7L;
    private static final Long EXAM_ID = 10L;
    private static final BigDecimal GRADED = new BigDecimal("5.0");

    @Mock
    private ExamMapper examMapper;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private SubjectiveGradeMapper subjectiveGradeMapper;

    private GradingQueryService gradingQueryService;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 环境无 MyBatis 启动流程：为 lambda 条件初始化实体元数据（与
        // ObjectiveGradingServiceTest 同法，保证构造 Wrapper 时不缺 TableInfo）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GradingSubmission.class);
        TableInfoHelper.initTableInfo(assistant, SubjectiveGrade.class);
        gradingQueryService = new GradingQueryService(examMapper, gradingSubmissionMapper, subjectiveGradeMapper);
        LoginUser teacher = new LoginUser();
        teacher.setId(TEACHER_ID);
        teacher.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.TEACHER));
        SecurityUtil.set(teacher);
    }

    @AfterEach
    void clearSecurity() {
        SecurityUtil.clear();
    }

    // ==================== 两种流计算（参考实现 = 改动前原文） ====================

    /** 优化前原文（O(n*m)）：逐答卷扫描全部主观行。逐字保留，作为等价性判据。 */
    static long partialByNestedScan(List<GradingSubmission> submissions, List<SubjectiveGrade> subjectiveRows) {
        return submissions.stream().filter(submission ->
                subjectiveRows.stream().anyMatch(row -> row.getSubmissionId().equals(submission.getId())
                        && row.getScore() == null)).count();
    }

    /** 优化后目标写法（O(n+m)）：先收集存在未批主观题的答卷 ID 集合，再单遍过滤答卷。 */
    static long partialByHashSet(List<GradingSubmission> submissions, List<SubjectiveGrade> subjectiveRows) {
        Set<Long> ungradedSubmissionIds = subjectiveRows.stream()
                .filter(row -> row.getScore() == null)
                .map(SubjectiveGrade::getSubmissionId)
                .collect(Collectors.toSet());
        return submissions.stream()
                .filter(s -> ungradedSubmissionIds.contains(s.getId()))
                .count();
    }

    /** 断言单遍哈希与嵌套扫描一致，并返回参考值供显式期望比对。 */
    private static long assertEquivalent(String shape, List<GradingSubmission> submissions,
                                         List<SubjectiveGrade> rows) {
        long expected = partialByNestedScan(submissions, rows);
        assertEquals(expected, partialByHashSet(submissions, rows),
                shape + "：单遍哈希须与嵌套扫描逐点一致");
        return expected;
    }

    // ==================== 边界形状 ====================

    @Test
    void emptySubmissionsIsZero() {
        assertEquals(0L, assertEquivalent("空答卷列表", List.of(), subjectiveRows(row(1L, null))));
    }

    @Test
    void emptySubjectiveRowsIsZero() {
        assertEquals(0L, assertEquivalent("空主观行列表", submissionsWithIds(1L, 2L), List.of()));
    }

    @Test
    void allRowsGradedIsZero() {
        List<SubjectiveGrade> rows = subjectiveRows(row(1L, GRADED), row(2L, new BigDecimal("0.0")));
        assertEquals(0L, assertEquivalent("全批改", submissionsWithIds(1L, 2L), rows));
    }

    @Test
    void allRowsUnGradedCountsEverySubmission() {
        List<SubjectiveGrade> rows = subjectiveRows(row(1L, null), row(2L, null));
        assertEquals(2L, assertEquivalent("全未批", submissionsWithIds(1L, 2L), rows));
    }

    @Test
    void partialRowsCountOnlySubmissionsWithAnUnGradedRow() {
        List<SubjectiveGrade> rows = subjectiveRows(
                row(1L, GRADED), row(1L, null),    // 1：部分未批 -> 计入
                row(2L, GRADED), row(2L, GRADED),  // 2：全批完   -> 不计
                row(3L, null));                    // 3：全未批   -> 计入
        assertEquals(2L, assertEquivalent("部分未批", submissionsWithIds(1L, 2L, 3L), rows));
    }

    @Test
    void submissionWithMixedRowStatesCountedExactlyOnce() {
        // 同一答卷多道简答、一道已批一道未批：按答卷计 1 次，不是按行计
        List<SubjectiveGrade> rows = subjectiveRows(row(1L, GRADED), row(1L, null), row(1L, GRADED));
        assertEquals(1L, assertEquivalent("同卷多题混合批改状态", submissionsWithIds(1L), rows));
    }

    @Test
    void rowsBelongingToOtherSubmissionsAreIgnored() {
        List<SubjectiveGrade> rows = subjectiveRows(row(99L, null), row(1L, GRADED));
        assertEquals(0L, assertEquivalent("行属于非本批答卷", submissionsWithIds(1L), rows));
    }

    @Test
    void duplicateSubmissionEntriesCountedPerOccurrence() {
        List<SubjectiveGrade> rows = subjectiveRows(row(1L, null));
        assertEquals(2L, assertEquivalent("答卷列表含重复项", submissionsWithIds(1L, 1L), rows));
    }

    @Test
    void submissionEntriesWithNullIdDoNotMatchNonNullRowKeys() {
        // 答卷项 id 为 null：旧写法 row.getSubmissionId().equals(null) 恒 false -> 不计；
        // 新写法在行键均非 null 时 set.contains(null) 同样 false -> 等价
        List<SubjectiveGrade> rows = subjectiveRows(row(1L, null));
        assertEquals(1L, assertEquivalent("答卷项 id 为 null", submissionsWithIds(null, 1L), rows));
    }

    @Test
    void nullRowKeyIsOutOfDomainDivergence() {
        List<GradingSubmission> submissions = submissionsWithIds(1L);
        // 旧写法：主观行 submission_id 为 null -> null.equals(...) 抛 NPE（与 score 是否为空无关）
        assertThrows(NullPointerException.class,
                () -> partialByNestedScan(submissions, subjectiveRows(row(null, null))));
        assertThrows(NullPointerException.class,
                () -> partialByNestedScan(submissions, subjectiveRows(row(null, GRADED))));
        // 新写法：HashSet 允许 null 键，contains(1L) = false -> 返回 0（不抛）
        assertEquals(0L, partialByHashSet(submissions, subjectiveRows(row(null, null))));
        // 真实端点走新写法，同样不抛、返回 0
        assertEquals(0, runProgressPartialCount(submissions, subjectiveRows(row(null, null))));
        // 该输入库内不可达：schema.sql 声明 subjective_grades.submission_id BIGINT NOT NULL
    }

    // ==================== 随机规模等价性 ====================

    @Test
    void randomizedEquivalenceAcrossSizes() {
        Random random = new Random(20261001L);
        for (int iteration = 0; iteration < 400; iteration++) {
            int idPool = 1 + random.nextInt(16);
            int submissionCount = random.nextInt(40);
            int rowCount = random.nextInt(40);
            List<GradingSubmission> submissions = randomSubmissions(random, submissionCount, idPool);
            List<SubjectiveGrade> rows = randomRows(random, rowCount, idPool);
            long partial = assertEquivalent("随机构造 #" + iteration
                    + "（答卷=" + submissionCount + "，行=" + rowCount + "）", submissions, rows);
            assertTrue(partial >= 0 && partial <= submissionCount, "partial 计数不得超过答卷数");
        }
    }

    @Test
    void equivalenceAtCardScaleThreeThousandSubmissionsSixThousandRows() {
        Random random = new Random(20261001L);
        List<GradingSubmission> submissions = randomSubmissions(random, 3000, 3000);
        List<SubjectiveGrade> rows = randomRows(random, 6000, 3000);
        long partial = assertEquivalent("卡面规模 n=3000/m=6000", submissions, rows);
        assertTrue(partial > 0 && partial <= 3000, "随机样本应落在 0<partial<=3000 的混合区间");
    }

    // ==================== 真实端点（GradingQueryService.progress） ====================

    @Test
    void progressEndpointPartialCountMatchesReferenceOracleOnMixedFixture() {
        List<GradingSubmission> submissions = submissionsWithIds(1L, 2L, 3L, 4L);
        List<SubjectiveGrade> rows = subjectiveRows(
                row(1L, GRADED), row(1L, null),
                row(2L, GRADED),
                row(3L, null), row(3L, null),
                row(4L, null));
        int endpoint = runProgressPartialCount(submissions, rows);
        assertEquals(3, endpoint, "1/3/4 有未批简答、2 全批完 -> 3");
        assertEquals((int) partialByNestedScan(submissions, rows), endpoint);
    }

    @Test
    void progressEndpointMatchesOracleAcrossRandomShapes() {
        Random random = new Random(20261002L);
        for (int iteration = 0; iteration < 30; iteration++) {
            int idPool = 1 + random.nextInt(10);
            List<GradingSubmission> submissions = randomSubmissions(random, 1 + random.nextInt(20), idPool);
            List<SubjectiveGrade> rows = randomRows(random, random.nextInt(25), idPool);
            int endpoint = runProgressPartialCount(submissions, rows);
            assertEquals((int) partialByNestedScan(submissions, rows), endpoint,
                    "端点 partialGradedCount 须等于参考实现（#" + iteration + "）");
        }
    }

    @Test
    void progressEndpointMatchesOracleAtScale() {
        Random random = new Random(20261003L);
        List<GradingSubmission> submissions = randomSubmissions(random, 3000, 3000);
        List<SubjectiveGrade> rows = randomRows(random, 6000, 3000);
        assertEquals((int) partialByNestedScan(submissions, rows), runProgressPartialCount(submissions, rows));
    }

    /** 驱动真实端点：mock 三个 Mapper，返回 progress() 的 partialGradedCount。 */
    private int runProgressPartialCount(List<GradingSubmission> submissions, List<SubjectiveGrade> subjectiveRows) {
        Exam exam = new Exam();
        exam.setId(EXAM_ID);
        exam.setCreatedBy(TEACHER_ID);
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam);
        when(gradingSubmissionMapper.selectList(any())).thenReturn(submissions);
        when(subjectiveGradeMapper.selectList(any())).thenReturn(subjectiveRows);
        GradingProgressResponse response = gradingQueryService.progress(EXAM_ID);
        assertEquals(submissions.size(), response.getSubmittedCount(), "submittedCount 应等于已交卷答卷数");
        return response.getPartialGradedCount();
    }

    // ==================== 造数工具 ====================

    private static GradingSubmission submission(Long id) {
        GradingSubmission submission = new GradingSubmission();
        submission.setId(id);
        submission.setExamId(EXAM_ID);
        submission.setStatus(ExamSubmission.STATUS_SUBMITTED);
        submission.setGradingStatus(ObjectiveGradingService.GRADING_OK);
        return submission;
    }

    private static List<GradingSubmission> submissionsWithIds(Long... ids) {
        List<GradingSubmission> list = new ArrayList<>(ids.length);
        for (Long id : ids) {
            list.add(submission(id));
        }
        return list;
    }

    private static SubjectiveGrade row(Long submissionId, BigDecimal score) {
        SubjectiveGrade row = new SubjectiveGrade();
        row.setSubmissionId(submissionId);
        row.setExamId(EXAM_ID);
        row.setScore(score);
        return row;
    }

    private static List<SubjectiveGrade> subjectiveRows(SubjectiveGrade... rows) {
        return new ArrayList<>(List.of(rows));
    }

    private static List<GradingSubmission> randomSubmissions(Random random, int count, int idPool) {
        List<GradingSubmission> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(submission((long) random.nextInt(idPool)));
        }
        return list;
    }

    private static List<SubjectiveGrade> randomRows(Random random, int count, int idPool) {
        List<SubjectiveGrade> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BigDecimal score = random.nextBoolean() ? new BigDecimal(random.nextInt(10)) : null;
            list.add(row((long) random.nextInt(idPool), score));
        }
        return list;
    }
}