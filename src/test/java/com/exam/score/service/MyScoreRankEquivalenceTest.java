package com.exam.score.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.exam.common.BusinessException;
import com.exam.exam.entity.Exam;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.score.dto.MyScoreResponse;
import com.exam.submission.entity.ExamSubmission;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * myScore 名次聚合查询的等价性测试（optimize-my-score-rank-fetch E1–E8）：
 * 名次改为「严格更高分人数 + 1」一条聚合计数后，与逐份比较的竞赛排名口径（RankCalculator）
 * 逐学生对拍必须完全一致；404 判定由「本人行落在全班 GRADED 集合」的隐式形式改为显式判
 * status，条件集合（无行 / totalScore 为 null / status 不是 GRADED）逐条等价。
 *
 * <p>E6/E7 的取数形态护栏（行级取数条数不随班级人数增长、myScore 路径不出现全班 selectList）
 * 由测试侧 MyBatis 拦截器 {@link SubmissionsRowGuard} 承载——仅本测试上下文注册，
 * 旧「取回全班」实现必须红灯，聚合计数实现必须绿灯。
 *
 * <p>数据隔离：exam_id 使用 941_000_000+ 段位、student_id 使用 942_000_000+ 段位直插
 * （schema.sql 自动建表，无 @Sql 自建表；共享 H2 上以独占段位 + @AfterEach 清理避免跨类干扰）。
 * 建表唯一来源是 schema.sql（AGENTS.md 约定 1/2）。
 */
@SpringBootTest
@ActiveProfiles("test")
class MyScoreRankEquivalenceTest {

    // ==================== 测试侧取数护栏（仅统计，不改变行为） ====================

    /**
     * 语句级护栏拦截器：统计 GradingSubmissionMapper 各语句的调用次数与返回行数。
     * E7 用「答卷表行级取数不随班级人数增长」判红旧实现；E6 附带断言 myScore 路径
     * 不出现全班 selectList。
     */
    @TestConfiguration
    static class GuardConfig {
        @Bean
        Interceptor submissionsRowGuard() {
            return new SubmissionsRowGuard();
        }
    }

    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            CacheKey.class, BoundSql.class})
    })
    static class SubmissionsRowGuard implements Interceptor {
        static final String MAPPER_PREFIX = "com.exam.grading.mapper.GradingSubmissionMapper.";
        static final Map<String, LongAdder> QUERIES = new ConcurrentHashMap<>();
        static final Map<String, LongAdder> ROWS = new ConcurrentHashMap<>();
        /** 单次语句执行返回行数的最大值（E6 结构判据：任何一条答卷表查询不得成批取回全班）。 */
        static final java.util.concurrent.atomic.AtomicLong MAX_ROWS = new java.util.concurrent.atomic.AtomicLong();

        static void reset() {
            QUERIES.clear();
            ROWS.clear();
            MAX_ROWS.set(0);
        }

        /** GradingSubmissionMapper 各语句返回行数合计（答卷表行级取数）。 */
        static long submissionsRows() {
            long rows = 0;
            for (Map.Entry<String, LongAdder> e : ROWS.entrySet()) {
                if (e.getKey().startsWith(MAPPER_PREFIX)) {
                    rows += e.getValue().sum();
                }
            }
            return rows;
        }

        static long maxSingleQueryRows() {
            return MAX_ROWS.get();
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
            Object result = invocation.proceed();
            int rows = (result instanceof Collection<?> c) ? c.size() : 0;
            QUERIES.computeIfAbsent(ms.getId(), k -> new LongAdder()).increment();
            ROWS.computeIfAbsent(ms.getId(), k -> new LongAdder()).add(rows);
            MAX_ROWS.accumulateAndGet(rows, Math::max);
            return result;
        }
    }

    // ==================== 依赖与数据工具 ====================

    /** 考试 id 段位：本测试类独占 941_000_000..941_006_999，@AfterEach 整段清理。 */
    private static final long EXAM_ID_LO = 941_000_000L;
    private static final long EXAM_ID_HI = 941_006_999L;
    private static final long EXAM_BASE = 941_000_000L;
    private static final long EQ_BASE = 941_001_000L;
    private static final long GUARD_BASE = 941_005_000L;
    private static final long STUDENT_BASE = 942_000_000L;

    @Autowired
    private ScoreQueryService scoreQueryService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RankCalculator rankCalculator;

    @AfterEach
    void cleanRangeAndSecurity() {
        jdbc.update("DELETE FROM exam_submissions WHERE exam_id BETWEEN ? AND ?", EXAM_ID_LO, EXAM_ID_HI);
        jdbc.update("DELETE FROM exams WHERE id BETWEEN ? AND ?", EXAM_ID_LO, EXAM_ID_HI);
        jdbc.update("DELETE FROM score_review WHERE exam_id BETWEEN ? AND ?", EXAM_ID_LO, EXAM_ID_HI);
        com.exam.auth.security.SecurityUtil.clear();
    }

    private void insertExam(long examId) {
        Timestamp ts = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                examId, "myScore-eq-" + examId, 941000001L, ts, ts, 60,
                Exam.STATUS_PUBLISHED, 1, 941000999L, 0);
    }

    /** 直插一份答卷；total 为 null 表示总分未产生。 */
    private void insertSubmission(long examId, long studentId, int status, BigDecimal total) {
        Timestamp ts = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, status,"
                        + " objective_score, subjective_score, total_score, grading_status, partial_graded,"
                        + " version, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                examId, studentId, ts, ts, status, total, BigDecimal.ZERO.setScale(1), total,
                total == null ? 0 : 1, 0);
    }

    private void loginAs(long studentId) {
        com.exam.auth.security.LoginUser user = new com.exam.auth.security.LoginUser();
        user.setId(studentId);
        user.setRoleLevel(com.exam.auth.security.RoleHierarchy.levelOf(com.exam.auth.security.RoleHierarchy.STUDENT));
        com.exam.auth.security.SecurityUtil.set(user);
    }

    private MyScoreResponse myScoreOf(long studentId, long examId) {
        loginAs(studentId);
        return scoreQueryService.myScore(examId);
    }

    private void assertOwnRecordNotFound(long examId) {
        BusinessException e = assertThrows(BusinessException.class, () -> scoreQueryService.myScore(examId));
        assertEquals("暂无本人成绩记录", e.getMessage());
    }

    /** 全班已汇总集合（status=GRADED 且 total 非空），按 id 升序——与名次口径同一集合定义。 */
    private List<BigDecimal> gradedTotalsOrderedById(long examId) {
        return jdbc.query(
                "SELECT total_score FROM exam_submissions"
                        + " WHERE exam_id = ? AND status = ? AND total_score IS NOT NULL ORDER BY id",
                (rs, i) -> rs.getBigDecimal("total_score"), examId, ExamSubmission.STATUS_GRADED);
    }

    // ==================== E1：并列同名次、后续跳号 ====================

    @Test
    void e1CompetitionRankingTiesThenSkip() {
        long examId = EXAM_BASE + 1;
        insertExam(examId);
        long s1 = STUDENT_BASE + 1, s2 = STUDENT_BASE + 2, s3 = STUDENT_BASE + 3, s4 = STUDENT_BASE + 4;
        insertSubmission(examId, s1, ExamSubmission.STATUS_GRADED, new BigDecimal("90"));
        insertSubmission(examId, s2, ExamSubmission.STATUS_GRADED, new BigDecimal("85"));
        insertSubmission(examId, s3, ExamSubmission.STATUS_GRADED, new BigDecimal("85"));
        insertSubmission(examId, s4, ExamSubmission.STATUS_GRADED, new BigDecimal("80"));

        assertEquals(1, myScoreOf(s1, examId).getRank());
        assertEquals(2, myScoreOf(s2, examId).getRank());
        assertEquals(2, myScoreOf(s3, examId).getRank());
        assertEquals(4, myScoreOf(s4, examId).getRank());
    }

    // ==================== E2：数值相等 scale 不同仍并列（按入库后实际形态断言） ====================

    @Test
    void e2ScaleEquivalentTotalsTieAfterDecimalNormalization() {
        long examId = EXAM_BASE + 2;
        insertExam(examId);
        long s1 = STUDENT_BASE + 11, s2 = STUDENT_BASE + 12, s3 = STUDENT_BASE + 13;
        insertSubmission(examId, s1, ExamSubmission.STATUS_GRADED, new BigDecimal("60"));
        insertSubmission(examId, s2, ExamSubmission.STATUS_GRADED, new BigDecimal("60.00"));
        insertSubmission(examId, s3, ExamSubmission.STATUS_GRADED, new BigDecimal("59.9"));

        // DECIMAL(5,1) 列会归一 scale：入库后 60 与 60.00 的实际形态都是 scale=1 的 60.0（断言写明）
        BigDecimal stored1 = jdbc.queryForObject(
                "SELECT total_score FROM exam_submissions WHERE exam_id = ? AND student_id = ?",
                BigDecimal.class, examId, s1);
        BigDecimal stored2 = jdbc.queryForObject(
                "SELECT total_score FROM exam_submissions WHERE exam_id = ? AND student_id = ?",
                BigDecimal.class, examId, s2);
        assertEquals(1, stored1.scale(), "入库实际形态：DECIMAL(5,1) 归一为 scale=1");
        assertEquals(1, stored2.scale(), "入库实际形态：DECIMAL(5,1) 归一为 scale=1");
        assertEquals(0, stored1.compareTo(new BigDecimal("60")));
        assertEquals(0, stored2.compareTo(new BigDecimal("60")));

        assertEquals(1, myScoreOf(s1, examId).getRank());
        assertEquals(1, myScoreOf(s2, examId).getRank());
        assertEquals(3, myScoreOf(s3, examId).getRank());
    }

    // ==================== E3：本人总分未产生 → 404 ====================

    @Test
    void e3OwnRowWithoutTotalScoreIsNotFound() {
        long examId = EXAM_BASE + 3;
        insertExam(examId);
        long studentId = STUDENT_BASE + 21;
        insertSubmission(examId, studentId, ExamSubmission.STATUS_GRADED, null);
        loginAs(studentId);

        assertOwnRecordNotFound(examId);
    }

    // ==================== E4：本人 status 不是 GRADED（即使有总分）→ 404 ====================

    @Test
    void e4OwnRowNotGradedIsNotFoundEvenWithTotal() {
        long examId = EXAM_BASE + 4;
        insertExam(examId);
        long studentId = STUDENT_BASE + 22;
        insertSubmission(examId, studentId, ExamSubmission.STATUS_SUBMITTED, new BigDecimal("88.8"));
        loginAs(studentId);

        assertOwnRecordNotFound(examId);
    }

    // ==================== E5：本人最高分 → 1；全场同分 → 全 1 ====================

    @Test
    void e5TopScoreIsFirstAndAllTiesShareFirst() {
        long examId = EXAM_BASE + 5;
        insertExam(examId);
        long s1 = STUDENT_BASE + 31, s2 = STUDENT_BASE + 32, s3 = STUDENT_BASE + 33;
        insertSubmission(examId, s1, ExamSubmission.STATUS_GRADED, new BigDecimal("70"));
        insertSubmission(examId, s2, ExamSubmission.STATUS_GRADED, new BigDecimal("90"));
        insertSubmission(examId, s3, ExamSubmission.STATUS_GRADED, new BigDecimal("50"));
        assertEquals(1, myScoreOf(s2, examId).getRank());
        assertEquals(2, myScoreOf(s1, examId).getRank());
        assertEquals(3, myScoreOf(s3, examId).getRank());

        long examId2 = EXAM_BASE + 6;
        insertExam(examId2);
        for (int i = 1; i <= 4; i++) {
            insertSubmission(examId2, STUDENT_BASE + 40 + i, ExamSubmission.STATUS_GRADED, new BigDecimal("88.8"));
        }
        for (int i = 1; i <= 4; i++) {
            assertEquals(1, myScoreOf(STUDENT_BASE + 40 + i, examId2).getRank(), "全场同分应全为第 1 名");
        }
    }

    // ==================== E6：差分对拍（逐学生）+ myScore 路径无全班 selectList ====================

    @Test
    void e6AggregateRankMatchesReferenceOraclePerStudent() {
        SubmissionsRowGuard.reset();
        int[] sizes = {1, 2, 50, 3000};
        for (int n : sizes) {
            long examId = EQ_BASE + n;
            insertExam(examId);
            List<Long> gradedIds = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long studentId = STUDENT_BASE + i;
                // 4000+x (scale 1) = 400.0 + x/10，x=(i×7919) mod 300 ⇒ 值域 400.0–699.9、大量并列
                BigDecimal total = BigDecimal.valueOf(4000 + (i * 7919L) % 300, 1);
                insertSubmission(examId, studentId, ExamSubmission.STATUS_GRADED, total);
                gradedIds.add(studentId);
            }
            long submittedWithTotal = STUDENT_BASE + 900_001;
            long gradedWithoutTotal = STUDENT_BASE + 900_002;
            if (n >= 2) {
                // 集合外样本：已交卷但有总分（旧实现经「不在榜单」隐式 404）、已批改但总分未产生。
                // 两者都不得计入名次，本人查询一律 404。
                insertSubmission(examId, submittedWithTotal, ExamSubmission.STATUS_SUBMITTED, new BigDecimal("999.9"));
                insertSubmission(examId, gradedWithoutTotal, ExamSubmission.STATUS_GRADED, null);
            }

            // oracle：同一集合定义（status=GRADED 且 total 非空，按 id 升序）上跑 RankCalculator
            List<BigDecimal> totals = gradedTotalsOrderedById(examId);
            assertEquals(n, totals.size(), "graded 集合大小应等于 n（集合外样本不得混入）size=" + n);
            int[] oracle = rankCalculator.rank(totals);
            for (int i = 0; i < gradedIds.size(); i++) {
                MyScoreResponse response = myScoreOf(gradedIds.get(i), examId);
                assertEquals(oracle[i], response.getRank(),
                        "差分对拍失败 size=" + n + " studentIdx=" + i);
                assertEquals(0, response.getTotalScore().compareTo(totals.get(i)),
                        "返回总分应与本人行一致 size=" + n + " studentIdx=" + i);
            }
            if (n >= 2) {
                loginAs(submittedWithTotal);
                assertOwnRecordNotFound(examId);
                loginAs(gradedWithoutTotal);
                assertOwnRecordNotFound(examId);
            }
        }
        // 取数形态结构断言（E6）：对拍全程任何一条答卷表语句的单次返回行数不得超过常数——
        // 聚合实现每条语句至多返回本人行/标量（≤1 行）；旧「取回全班」实现一查 n 行（n=3000 时必红）。
        // 判据用常数上限而非精确 1：selectOne 在 MyBatis-Plus 下内部走 selectList 语句（1 行），
        // 「语句 id 不出现 selectList」不构成可绿的判据。
        assertTrue(SubmissionsRowGuard.maxSingleQueryRows() <= 4,
                "myScore 路径存在成批取回答卷行的查询（单次语句最多返回 "
                        + SubmissionsRowGuard.maxSingleQueryRows() + " 行）");
    }

    // ==================== E7：行级取数条数不随班级人数增长（旧实现必红） ====================

    @Test
    void e7SubmissionRowsFetchedByMyScoreDoNotGrowWithClassSize() {
        long rowsSmall = fetchSubmissionRowsForOneMyScore(GUARD_BASE + 200, 200);
        long rowsLarge = fetchSubmissionRowsForOneMyScore(GUARD_BASE + 1000, 1000);

        assertTrue(rowsSmall <= 10, "小班 myScore 对答卷表的行级取数应恒为常数，实测=" + rowsSmall);
        assertTrue(rowsLarge <= 10, "大班 myScore 对答卷表的行级取数应恒为常数，实测=" + rowsLarge);
        assertEquals(rowsSmall, rowsLarge, "行级取数条数不得随班级人数增长");
    }

    private long fetchSubmissionRowsForOneMyScore(long examId, int n) {
        insertExam(examId);
        for (int i = 0; i < n; i++) {
            insertSubmission(examId, STUDENT_BASE + i, ExamSubmission.STATUS_GRADED,
                    BigDecimal.valueOf(4000 + (i * 7919L) % 300, 1));
        }
        SubmissionsRowGuard.reset();
        loginAs(STUDENT_BASE);
        // 恰好一次 myScore 调用（学生 0 总分=400.0 为确定值），随后读护栏累计的答卷表行级取数
        MyScoreResponse response = scoreQueryService.myScore(examId);
        assertTrue(response.getRank() >= 1 && response.getRank() <= n, "名次应落在 1..n");
        assertEquals(0, response.getTotalScore().compareTo(new BigDecimal("400")),
                "返回总分应为学生 0 的 400.0，实际=" + response.getTotalScore());
        return SubmissionsRowGuard.submissionsRows();
    }

    // ==================== E8：复核隐藏 + 强一致读（不加 @DS slave） ====================

    @Test
    void e8aPendingReviewHidesScoresAndRank() {
        long examId = EXAM_BASE + 8;
        insertExam(examId);
        long studentId = STUDENT_BASE + 81;
        insertSubmission(examId, studentId, ExamSubmission.STATUS_GRADED, new BigDecimal("77.7"));
        jdbc.update("INSERT INTO score_review (exam_id, student_id, status, reason) VALUES (?,?,?,?)",
                examId, studentId, 0, "对客观题得分有疑问");

        MyScoreResponse response = myScoreOf(studentId, examId);
        assertTrue(Boolean.TRUE.equals(response.getReviewing()));
        assertNull(response.getTotalScore());
        assertNull(response.getObjectiveScore());
        assertNull(response.getSubjectiveScore());
        assertEquals(0, response.getRank());
    }

    @Test
    void e8bMyScoreStaysOnPrimaryAndSeesPrimaryWritesImmediately() {
        // 强一致读边界（myScore javadoc：不加 @DS("slave")）：类/方法/Mapper 不得带从库路由注解
        Class<? extends java.lang.annotation.Annotation> dsClass = DS.class;
        assertFalse(ScoreService.class.isAnnotationPresent(dsClass),
                "ScoreService 不得标注 @DS 从库路由");
        for (java.lang.reflect.Method method : ScoreService.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(dsClass),
                    "ScoreService." + method.getName() + " 不得标注 @DS 从库路由");
        }
        assertFalse(ScoreQueryService.class.isAnnotationPresent(dsClass),
                "ScoreQueryService 不得标注 @DS 从库路由");
        for (java.lang.reflect.Method method : ScoreQueryService.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(dsClass),
                    "ScoreQueryService." + method.getName() + " 不得标注 @DS 从库路由");
        }
        assertFalse(GradingSubmissionMapper.class.isAnnotationPresent(dsClass),
                "GradingSubmissionMapper 不得标注 @DS 从库路由");
        for (java.lang.reflect.Method method : GradingSubmissionMapper.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(dsClass),
                    "GradingSubmissionMapper." + method.getName() + " 不得标注 @DS 从库路由");
        }

        // 读己之写：主库直插后同线程立即可见（发布后立即查必须是最新分数）
        long examId = EXAM_BASE + 9;
        insertExam(examId);
        long studentId = STUDENT_BASE + 91;
        insertSubmission(examId, studentId, ExamSubmission.STATUS_GRADED, new BigDecimal("66.6"));
        MyScoreResponse response = myScoreOf(studentId, examId);
        assertEquals(0, response.getTotalScore().compareTo(new BigDecimal("66.6")),
                "主库写入后立即查询必须可见（强一致读）");
        assertFalse(response.getReviewing());
    }
}
