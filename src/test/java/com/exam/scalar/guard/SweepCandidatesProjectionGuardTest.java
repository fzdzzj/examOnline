package com.exam.scalar.guard;

import com.exam.exam.entity.Exam;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兜底扫描候选行读形态列投影的常驻护栏（change project-sweep-candidates-scalar-projection）：
 * {@link ExamSubmissionMapper#selectForceSubmitCandidates} 的全仓唯一消费方
 * {@code ExamSweepService.forceSubmitOverdue} 对候选行只消费 {@code getExamId()} 与
 * {@code getStudentId()}（随后 forceSubmitByBackend 按 (examId, studentId) 重新定位答卷），
 * 故目标语句只取冻结标量列 {@code s.exam_id, s.student_id}。
 * 目标语句返回实体的 {@code paperJson} 必须为 null，而库内同批行该列非 null
 * （seed 前置断言，排除“库本来就空”的伪绿）；取消投影或放宽投影列时，
 * 长字段非 null 计数即脱离 0、且捕获 SELECT 列表偏离冻结列集，本测试变红。
 *
 * <p>对账补发站点 {@code selectSubmittedWithoutAnswers}（命中量为个位数行）维持全列读取，
 * 不据此判为违规；本护栏只覆盖兜底扫描候选语句。
 */
@SpringBootTest
@ActiveProfiles("test")
// 调度隔离：与本仓库既有 ScalarProjectionGuardTest 同口径，观测窗口内不得存在定时扫描流量。
@TestPropertySource(properties = {
        "exam.taking.sweep.fixed-delay-ms=3600000",
        "exam.taking.sweep.initial-delay-ms=3600000"
})
@DisplayName("兜底扫描候选列投影护栏：selectForceSubmitCandidates 只取冻结标量列")
class SweepCandidatesProjectionGuardTest {

    private static final long GV_EXAM = 981_600_000L;
    private static final long GV_PAPER = 981_600_900L;
    private static final long GV_OWNER = 981_699_999L;
    private static final long GV_STUDENT_BASE = 981_600_100L;
    private static final int GV_ROWS = 3;
    // 冻结列集（机械归因：ExamSweepService.forceSubmitOverdue 唯一消费 getExamId/getStudentId）
    private static final String FROZEN_COLUMNS = "s.exam_id,s.student_id";
    private static final String LONG_PAPER_JSON = "{\"snapshot\":\"sweep-guard\"}";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ExamSubmissionMapper submissionMapper;

    @TestConfiguration
    static class GuardConfig {
        @Bean
        Interceptor sweepCandidatesSelectGuardInterceptor() {
            return new SweepCandidatesSelectGuardInterceptor();
        }
    }

    @BeforeEach
    void resetGuard() {
        SweepCandidatesSelectGuardInterceptor.reset();
    }

    @Test
    @DisplayName("selectForceSubmitCandidates：SELECT 列表==冻结列集，实体长字段为 null 且库内非 null，消费列照常载入")
    void sweepCandidatesProjectScalarColumnsOnly() {
        LocalDateTime now = LocalDateTime.now();
        TimestampLike start = new TimestampLike(now.minusMinutes(65));
        TimestampLike deadline = new TimestampLike(now.minusMinutes(5));

        jdbc.update("DELETE FROM exam_submissions WHERE exam_id = ?", GV_EXAM);
        jdbc.update("DELETE FROM users WHERE id BETWEEN ? AND ?", GV_STUDENT_BASE, GV_STUDENT_BASE + GV_ROWS - 1);
        jdbc.update("DELETE FROM exams WHERE id = ?", GV_EXAM);

        jdbc.update("INSERT INTO exams (id, title, paper_id, class_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,NULL,?,?,60,?,1,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                GV_EXAM, "护栏-兜底扫描", GV_PAPER, start.ts, new TimestampLike(now.plusHours(2)).ts,
                Exam.STATUS_IN_PROGRESS, GV_OWNER);

        for (int i = 0; i < GV_ROWS; i++) {
            long sid = GV_STUDENT_BASE + i;
            jdbc.update("INSERT INTO users (id, username, password, name, status, must_change_password, is_deleted)"
                            + " VALUES (?,?,?,?,1,0,0)",
                    sid, "gv_sweep_" + sid, "x", "护栏生" + i);
            jdbc.update("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, submit_time,"
                            + " answers, paper_json, status, version, grading_status, partial_graded,"
                            + " created_time, updated_time)"
                            + " VALUES (?,?,?,?,NULL,NULL,?,1,0,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    GV_EXAM, sid, start.ts, deadline.ts, LONG_PAPER_JSON);
        }

        assertEquals(GV_ROWS, count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?"
                        + " AND status = 1 AND deadline_time < ? AND paper_json IS NOT NULL", GV_EXAM, now),
                "seed 前置：库内确有长字段非 null 的超时候选行——实体读到 null 只能来自列投影");

        List<ExamSubmission> candidates;
        SweepCandidatesSelectGuardInterceptor.armed = true;
        try {
            candidates = submissionMapper.selectForceSubmitCandidates(now, 10);
        } finally {
            SweepCandidatesSelectGuardInterceptor.armed = false;
        }

        assertEquals(1, SweepCandidatesSelectGuardInterceptor.EXECUTIONS.get(), "目标语句执行条数");
        assertEquals(GV_ROWS, SweepCandidatesSelectGuardInterceptor.ROWS.get(), "目标语句返回行数");
        assertEquals(GV_ROWS, candidates.size(), "候选条数与 seed 一致");
        for (ExamSubmission candidate : candidates) {
            assertNotNull(candidate.getExamId(), "下游消费列 examId 必须照常载入");
            assertNotNull(candidate.getStudentId(), "下游消费列 studentId 必须照常载入");
        }
        assertEquals(0, SweepCandidatesSelectGuardInterceptor.LONG_FIELD_NON_NULL.get(),
                "列投影后目标语句返回实体的 paperJson 必须为 null");
        assertGuardSawStatement();
        assertEquals(FROZEN_COLUMNS, normalizedSelectList(SweepCandidatesSelectGuardInterceptor.targetSql),
                "捕获 SELECT 列表必须 == 冻结列集：" + SweepCandidatesSelectGuardInterceptor.targetSql);
    }

    /** 护栏 canary：观测窗口内若目标语句一次都没被拦到，本断言失败（防“语句没跑＝全绿”的伪绿）。 */
    private void assertGuardSawStatement() {
        assertTrue(SweepCandidatesSelectGuardInterceptor.EXECUTIONS.get() > 0,
                "canary：护栏必须实际拦到目标语句，否则本次护栏证据不成立");
        assertNotNull(SweepCandidatesSelectGuardInterceptor.targetSql, "canary：须捕获到目标语句 SQL");
        System.out.println("CAPTURED_SWEEP_SQL=" + SweepCandidatesSelectGuardInterceptor.targetSql);
        String sqlLower = SweepCandidatesSelectGuardInterceptor.targetSql.toLowerCase(Locale.ROOT);
        assertFalse(sqlLower.contains("paper_json"),
                "列投影后 SELECT 语句不得载入长字段 paper_json："
                        + SweepCandidatesSelectGuardInterceptor.targetSql);
    }

    /** 剥掉优化器注释（/\*+ ... *\//）后提取 SELECT 列表并规范化（去空白、小写）。 */
    private static String normalizedSelectList(String sql) {
        if (sql == null) {
            return "";
        }
        String stripped = sql.replaceAll("/\\*.*?\\*/", " ").trim();
        String upper = stripped.toUpperCase(Locale.ROOT);
        int from = upper.indexOf(" FROM ");
        if (!upper.startsWith("SELECT ") || from < 0) {
            return "";
        }
        return stripped.substring("SELECT ".length(), from).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private int count(String sql, Object... args) {
        Number n = jdbc.queryForObject(sql, Number.class, args);
        return n == null ? -1 : n.intValue();
    }

    /** 传参小工具：LocalDateTime → java.sql.Timestamp（H2/MySQL 均接受 Timestamp 作为 DATETIME 实参）。 */
    private record TimestampLike(java.sql.Timestamp ts) {
        TimestampLike(LocalDateTime value) {
            this(java.sql.Timestamp.valueOf(value));
        }
    }

    /**
     * 目标语句观测护栏（Executor 层，仅本测试上下文注册）：armed 窗口内观测
     * {@code ExamSubmissionMapper.selectForceSubmitCandidates} 的执行条数与返回行，逐行检查长字段——
     * 任一 {@code ExamSubmission} 的 paperJson 非 null 即累计违规，由用例断言为 0。
     */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            org.apache.ibatis.cache.CacheKey.class, BoundSql.class})
    })
    static class SweepCandidatesSelectGuardInterceptor implements Interceptor {

        static final String TARGET_MS =
                "com.exam.submission.mapper.ExamSubmissionMapper.selectForceSubmitCandidates";
        static final AtomicInteger EXECUTIONS = new AtomicInteger();
        static final AtomicInteger ROWS = new AtomicInteger();
        static final AtomicInteger LONG_FIELD_NON_NULL = new AtomicInteger();
        static volatile String targetSql;
        static volatile boolean armed;

        static void reset() {
            EXECUTIONS.set(0);
            ROWS.set(0);
            LONG_FIELD_NON_NULL.set(0);
            targetSql = null;
            armed = false;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
            if (armed && ms.getId().equals(TARGET_MS) && result instanceof List<?> list) {
                EXECUTIONS.incrementAndGet();
                ROWS.addAndGet(list.size());
                Object param = invocation.getArgs()[1];
                targetSql = ms.getBoundSql(param).getSql();
                for (Object o : list) {
                    if (o instanceof ExamSubmission s && s.getPaperJson() != null) {
                        LONG_FIELD_NON_NULL.incrementAndGet();
                    }
                }
            }
            return result;
        }
    }
}
