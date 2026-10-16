package com.exam.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.config.CacheConfig;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 定时流转事务边界在真实数据库上的验证（fix-exam-state-machine-transaction，阶段 2 先红用例）。
 *
 * <p><b>为什么必须经 {@code scheduledAdvance} 入口</b>：缺陷本质是定时/启动入口对
 * {@code autoAdvance} 的<b>同类自调用绕过 Spring 代理</b>——注解式 {@code @Transactional}
 * 只在代理入站调用时生效。{@code ExamStateMachineServiceTest} 直接调用 {@code autoAdvance()}，
 * 恰好模拟了"经代理调用"的形态，因此从未暴露本缺陷。本用例一律经 {@code scheduledAdvance()}
 * 入口触发，与生产 {@code @Scheduled} 路径同形。
 *
 * <p><b>先红证明</b>：修复前（{@code autoAdvance} 无事务）本类确定性变红——报"注入故障后
 * 当轮候选仍被推进到 ENDED"（故障场之前的 CAS 已各自提交，落成 ENDED 且无自愈路径）。
 * 变异 m1（退回自调用无事务形态）逐字复现该红并留证；变异 m3（缓存清除落到回滚路径）
 * 击红缓存用例；变异 m2（吞掉故障）未被杀，原因与边界见 evidence/mutation-verification.md。
 * 断言对象是真实数据库行/真实 Redis 键，不是打桩交互，不存在"改打桩即改结论"的空间。
 *
 * <p><b>用例顺序是刻意固定的</b>：{@link MethodOrderer.OrderAnnotation} +
 * 每个 {@code @Test} 上的 {@code @Order}。用例 1（先）让 spy 上的 {@code markAbsence}
 * 至少真实执行过一次，避免"spy 默认是否调用真实实现"依赖 Mockito 默认应答版本；
 * 用例 2/3（后）随时序用例建立该基线后各自注入故障。三个用例的"注入"都是显式打桩，
 * 与真实实现是否被调用无关，顺序只影响冗余度、不影响判据。
 *
 * <p><b>断言口径刻意不写死考试 ID</b>：扫表是<b>全库</b>语义（"进行中且时间窗已过"的所有考试
 * 都是当轮候选），测试库里可能残留其他用例造的候选行。故断言"当轮候选集合"整体回滚——
 * 既不依赖 ID 排序，也不会被残留行误伤。
 *
 * <p><b>夹具刻意做轻</b>：不建班级/学生/答卷，只需 CAS 与 {@code markAbsence} 的调用点，
 * 故 {@code AbsenceService} 用 spy——未打桩时照常走真实实现，只对指定场次注入故障。
 *
 * <p>调度间隔在本类拉到 1h（与 {@code exam.taking.sweep} 测试策略一致），避免后台 tick
 * 与用例显式调用交错；{@code scheduledAdvance()} 由用例自己调用。
 */
@SpringBootTest(properties = {
        "exam.schedule.fixed-delay-ms=3600000",
        "exam.schedule.initial-delay-ms=3600000"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExamStateMachineTransactionTest extends IntegrationTestBase {

    /** spy 而非 mock：未打桩的方法照常走真实实现（缺考差集与 INSERT IGNORE 都是真库事实）。 */
    @MockitoSpyBean
    private AbsenceService absenceService;

    @Autowired
    private ExamStateMachineService stateMachineService;

    @Autowired
    private ExamMapper examMapper;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void resetAbsenceSpy() {
        // 打桩随上下文缓存跨用例存活，逐用例复位，避免上一用例的故障注入污染下一用例
        Mockito.reset(absenceService);
    }

    /**
     * 缓存清除必须在事务提交之后：本轮整轮回滚（无提交）时不得清空 examDetail 缓存。
     *
     * <p>先经真实读路径种下缓存键，再让 markAbsence 失败触发整轮回滚——若缓存清除落在
     * 回滚路径上（例如被塞进 CAS 循环内），键会在本轮被删掉，本用例即变红。
     * 注意本用例<b>不</b>声称能区分"提交前 clear"与"提交后 clear"：本仓的
     * {@code RedisCacheManager} 不参与 DB 事务（clear 立即提交、回滚无法复原），
     * 因此该时序差异在测试里不可观测，只能靠代码结构与 review 保证。
     */
    @Test
    @Order(1)
    void rolledBackRoundLeavesExamDetailCacheUntouched() throws Exception {
        String teacher = registerTeacher();
        long examId = inProgressExamEndingNow(teacher);

        // key = id + ':' + 请求者 ID，见 ExamService.detailCacheKey
        perform(jsonGet("/api/exams/" + examId, teacher), 200);
        String prefix = CacheConfig.KEY_PREFIX + CacheConfig.CACHE_EXAM_DETAIL;
        assertTrue(hasCacheKey(prefix), "前置条件：详情接口应已写入 examDetail 缓存键");

        doThrow(new IllegalStateException("markAbsence 瞬时故障（用例注入）"))
                .when(absenceService).markAbsence(examId);
        assertNotNull(runSweepSavingFailure(), "前置条件：注入故障的一轮必须失败");

        assertTrue(hasCacheKey(prefix),
                "整轮回滚未提交任何状态流转，缓存清除应留在提交之后——不得清空 examDetail 缓存");
    }

    /**
     * 核心断言：整轮一个事务——任一 markAbsence 失败时当轮 CAS 全部回滚、考试保持原状态，
     * 且下一轮扫表能重试补偿成功（spec-delta「由下一轮扫表重试补偿」）。
     *
     * <p>夹具造两场"进行中且时间窗已过"的考试（当轮都会命中），只对其中一场注入
     * markAbsence 故障。修复前：故障场之前的 CAS 已各自提交，落成 ENDED 且无自愈路径
     * （autoAdvance 只扫 IN_PROGRESS）→ 缺考永久漏标。修复后：整轮回滚，当轮候选全部保持
     * IN_PROGRESS 且 version 不前进；故障撤除后同一轮重试即成功。
     */
    @Test
    @Order(2)
    void markAbsenceFailureRollsBackWholeRoundThenRetryCompensates() throws Exception {
        String teacher = registerTeacher();
        List<Long> bothEnding = twoInProgressExamsEndingNow(teacher);
        long failingExam = bothEnding.get(1);

        Set<Long> candidates = expiredInProgressIds();
        assertTrue(candidates.containsAll(bothEnding) && candidates.size() >= 2,
                "前置条件：当轮应同时命中夹具造的两场候选考试，实际=" + candidates);
        Map<Long, Integer> versionsBefore = candidates.stream().collect(Collectors.toMap(
                id -> id, id -> examMapper.selectById(id).getVersion()));

        doThrow(new IllegalStateException("markAbsence 瞬时故障（用例注入）"))
                .when(absenceService).markAbsence(failingExam);
        assertNotNull(runSweepSavingFailure(),
                "整轮同事务：markAbsence 失败必须逃出定时入口，而不是被吞掉留下半轮已提交");

        verify(absenceService).markAbsence(failingExam);
        List<Exam> after = examMapper.selectBatchIds(candidates);
        assertEquals(candidates.size(), after.size(), "回滚后当轮候选行必须都还在");
        for (Exam exam : after) {
            assertEquals(Exam.STATUS_IN_PROGRESS, exam.getStatus(),
                    "整轮一个事务：任一场 markAbsence 故障时，当轮全部 CAS 必须一起回滚；"
                            + "考试 " + exam.getId() + " 未保持进行中");
            assertEquals(versionsBefore.get(exam.getId()), exam.getVersion(),
                    "回滚后 version 不得前进（CAS 未提交）；考试 " + exam.getId());
        }

        // 重试补偿：撤除故障注入（丢弃本用例的打桩）后同一轮重试应全部推进成功
        Mockito.reset(absenceService);
        stateMachineService.scheduledAdvance();
        for (Exam exam : examMapper.selectBatchIds(candidates)) {
            assertEquals(Exam.STATUS_ENDED, exam.getStatus(),
                    "重试补偿：故障撤除后当轮候选应全部推进到已结束；考试 " + exam.getId());
            assertEquals(versionsBefore.get(exam.getId()) + 1, exam.getVersion(),
                    "重试补偿：version 恰好 +1（幂等，不重复推进）；考试 " + exam.getId());
        }
    }

    /**
     * 回归护栏（修复前后都绿）：正常一轮照常提交 进行中→已结束，并在提交后清空缓存。
     * 自己造的考试必须从 IN_PROGRESS 走到 ENDED 且 version 恰好 +1，防止
     * "为回滚而回滚"把正常路径的提交与清缓存一并改坏。
     */
    @Test
    @Order(3)
    void successfulRoundEndsExamCommitsVersionAndClearsCache() throws Exception {
        String teacher = registerTeacher();
        long examId = inProgressExamEndingNow(teacher);
        int versionBefore = examMapper.selectById(examId).getVersion();

        perform(jsonGet("/api/exams/" + examId, teacher), 200);
        String prefix = CacheConfig.KEY_PREFIX + CacheConfig.CACHE_EXAM_DETAIL;
        assertTrue(hasCacheKey(prefix), "前置条件：详情接口应已写入 examDetail 缓存键");

        stateMachineService.scheduledAdvance();

        Exam after = examMapper.selectById(examId);
        assertEquals(Exam.STATUS_ENDED, after.getStatus(), "正常一轮：CAS 提交后考试进入已结束");
        assertEquals(versionBefore + 1, after.getVersion(), "正常一轮：version 恰好 +1");
        assertFalse(hasCacheKey(prefix), "本轮确有流转 → 缓存清除应在提交后执行并生效");
    }

    // ==================== 造数 / 工具 ====================

    /** 跑一轮定时入口，返回逃出的异常（未逃出返回 null）——不把"异常类型"写死成断言。 */
    private RuntimeException runSweepSavingFailure() {
        try {
            stateMachineService.scheduledAdvance();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** 当轮的扫表候选（进行中且时间窗已过）——事务边界断言的对象集合。 */
    private Set<Long> expiredInProgressIds() {
        return examMapper.selectList(Wrappers.<Exam>lambdaQuery()
                        .eq(Exam::getStatus, Exam.STATUS_IN_PROGRESS)
                        .le(Exam::getEndTime, LocalDateTime.now()))
                .stream().map(Exam::getId).collect(Collectors.toSet());
    }

    /**
     * 经真实接口造一场"已发布、已自动开考、时间窗已关闭"的考试（夹具自用，单独一场时成立）。
     *
     * <p>开考一律经定时入口（与生产同形）；时间窗关闭放在最后，避免夹具自身的中间扫表
     * 提前把它推到 ENDED（那样注入故障时它已不是当轮候选）。
     */
    private long inProgressExamEndingNow(String teacher) throws Exception {
        long examId = publishedExamNotStarted(teacher);
        openExamWindows(Set.of(examId));
        return examId;
    }

    /**
     * 造两场都处于"当轮候选"的考试：先把两场都建好并置为进行中，再一起关掉时间窗。
     *
     * <p>不能"建一场关一场"：扫表是全库语义，后一场开考时的扫表会把前一场一并推到 ENDED，
     * 于是下一轮的候选只剩一场。合并推进可保证两场同时处于候选态。
     */
    private List<Long> twoInProgressExamsEndingNow(String teacher) throws Exception {
        long first = publishedExamNotStarted(teacher);
        long second = publishedExamNotStarted(teacher);
        openExamWindows(Set.of(first, second));
        return List.of(first, second);
    }

    /** 建一场已发布的考试（未开始，时间窗充裕），不做任何推进。 */
    private long publishedExamNotStarted(String teacher) throws Exception {
        long questionId = createQuestion(teacher, 1, unique("状态机事务题"), "A", List.of("A", "B", "C", "D"), null);
        long paperId = createPaper(teacher, unique("状态机事务卷"), 10.0);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                "{\"questionId\":" + questionId + ",\"score\":10}"), 200);
        long examId = perform(jsonPost("/api/exams", teacher,
                "{\"title\":\"" + unique("状态机事务考试") + "\",\"paperId\":" + paperId
                        + ",\"startTime\":\"" + LocalDateTime.now().minusMinutes(5)
                        + "\",\"endTime\":\"" + LocalDateTime.now().plusHours(2)
                        + "\",\"durationMinutes\":30}"), 200).get("data").get("id").asLong();
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        return examId;
    }

    /**
     * 一轮扫表把给定考试开考（未开始→进行中），随后把时间窗关到过去。
     * 调用后这些考试都成为"当轮候选"（进行中 + 时间窗已过），且本轮扫表不产生 ENDED。
     */
    private void openExamWindows(Set<Long> examIds) {
        stateMachineService.scheduledAdvance();
        for (Long examId : examIds) {
            assertEquals(Exam.STATUS_IN_PROGRESS, examMapper.selectById(examId).getStatus(),
                    "前置条件：考试 " + examId + " 应已自动开考");
        }
        examMapper.update(null, Wrappers.<Exam>lambdaUpdate()
                .in(Exam::getId, examIds)
                .set(Exam::getEndTime, LocalDateTime.now().minusMinutes(1)));
        for (Long examId : examIds) {
            assertEquals(Exam.STATUS_IN_PROGRESS, examMapper.selectById(examId).getStatus(),
                    "前置条件：关窗后考试 " + examId + " 应仍是进行中");
        }
    }

    private boolean hasCacheKey(String prefix) {
        var keys = redis.keys(prefix + "*");
        return keys != null && !keys.isEmpty();
    }
}
