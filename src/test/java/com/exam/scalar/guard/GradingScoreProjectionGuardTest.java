package com.exam.scalar.guard;

import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.exam.entity.Exam;
import com.exam.score.dto.ScoreItem;
import com.exam.score.dto.ScorePreviewResponse;
import com.exam.score.service.ScoreService;
import com.exam.submission.entity.ExamSubmission;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判分/成绩读形态列投影的常驻护栏（change project-grading-score-scalar-projection 阶段 3）：
 * 唯一 GO 站点——发布前预览（{@link ScoreService#publishPreview}，单元 M5）——对答卷行只消费
 * 冻结标量列（student_id、objective_score、subjective_score、total_score、partial_graded）。
 * 目标语句（{@code GradingSubmissionMapper.selectList}）返回实体的 {@code answers}
 * 必须为 null，而库内同批行该列非 null（seed 前置断言，排除“库本来就空”的伪绿）；
 * 同批锁定预览的用户可见口径（逐项分数与名次）不因投影改变。取消投影或放宽投影列时，
 * 长字段非 null 计数即脱离 0、且捕获 SELECT 列表偏离冻结列集，本测试变红。
 *
 * <p>NO-GO 站点（M1–M4）不据此判为违规：其目标语句未做投影，本护栏只覆盖 M5。
 */
@SpringBootTest
@ActiveProfiles("test")
// 调度隔离：与本仓库既有 ScalarProjectionGuardTest 同口径，观测窗口内不得存在定时流量。
@TestPropertySource(properties = {
        "exam.schedule.initial-delay-ms=3600000",
        "exam.schedule.fixed-delay-ms=3600000"
})
@DisplayName("判分/成绩读形态列投影护栏：M5(publishPreview 主语句)只取冻结标量列")
class GradingScoreProjectionGuardTest {

    private static final long GV_EXAM = 981_500_000L;
    private static final long GV_PAPER = 981_500_900L;
    private static final long GV_OWNER = 981_599_999L;
    private static final long GV_STUDENT_BASE = 981_500_100L;
    private static final int GV_ROWS = 3;
    // 冻结列集（M5；PREREGISTRATION.md §0），实体声明序
    private static final String FROZEN_COLUMNS =
            "student_id,objective_score,subjective_score,total_score,partial_graded";
    private static final String LONG_ANSWERS = "{\"1001\":\"guard-answers\"}";
    private static final String LONG_PAPER_JSON = "{\"snapshot\":\"guard-paper\"}";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ScoreService scoreService;

    @TestConfiguration
    static class GuardConfig {
        @Bean
        Interceptor gradingSubmissionSelectGuardInterceptor() {
            return new SubmissionSelectGuardInterceptor();
        }
    }

    @BeforeEach
    void resetGuard() {
        SubmissionSelectGuardInterceptor.reset();
    }

    @Test
    @DisplayName("publishPreview：目标语句只取冻结标量列，实体长字段为 null 且库内非 null，预览口径不变")
    void publishPreviewProjectsScalarColumnsOnly() {
        Timestamp nowTs = Timestamp.valueOf(LocalDateTime.now());
        Timestamp deadline = Timestamp.valueOf(LocalDateTime.now().plusMinutes(60));

        jdbc.update("DELETE FROM exam_submissions WHERE exam_id = ?", GV_EXAM);
        jdbc.update("DELETE FROM users WHERE id BETWEEN ? AND ?", GV_STUDENT_BASE, GV_STUDENT_BASE + GV_ROWS - 1);
        jdbc.update("DELETE FROM exams WHERE id = ?", GV_EXAM);

        jdbc.update("INSERT INTO exams (id, title, paper_id, class_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,NULL,?,?,60,?,1,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                GV_EXAM, "护栏-M5预览", GV_PAPER, nowTs, deadline, Exam.STATUS_GRADED, GV_OWNER);

        for (int i = 0; i < GV_ROWS; i++) {
            long sid = GV_STUDENT_BASE + i;
            jdbc.update("INSERT INTO users (id, username, password, name, status, must_change_password, is_deleted)"
                            + " VALUES (?,?,?,?,1,0,0)",
                    sid, "gv_m5_" + sid, "x", "护栏生" + i);
            jdbc.update("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, submit_time,"
                            + " answers, paper_json, status, version, objective_score, subjective_score, total_score,"
                            + " grading_status, partial_graded, created_time, updated_time)"
                            + " VALUES (?,?,?,?,?,?,?,?,0,?,?,?,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    GV_EXAM, sid, nowTs, deadline, nowTs, LONG_ANSWERS, LONG_PAPER_JSON,
                    ExamSubmission.STATUS_GRADED, score(i), BigDecimal.valueOf(10L * i),
                    score(i).add(BigDecimal.valueOf(10L * i)), i % 2);
        }

        assertEquals(GV_ROWS, count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?"
                        + " AND answers IS NOT NULL AND paper_json IS NOT NULL", GV_EXAM),
                "seed 前置：库内该场确有长字段非 null 的行——实体读到 null 只能来自列投影");

        ScorePreviewResponse resp;
        SecurityUtil.set(teacherLogin(GV_OWNER));
        try {
            SubmissionSelectGuardInterceptor.armed = true;
            resp = scoreService.publishPreview(GV_EXAM);
            SubmissionSelectGuardInterceptor.armed = false;
        } finally {
            SecurityUtil.clear();
            SubmissionSelectGuardInterceptor.armed = false;
        }

        assertEquals(1, SubmissionSelectGuardInterceptor.EXECUTIONS.get(), "目标语句执行条数");
        assertEquals(GV_ROWS, SubmissionSelectGuardInterceptor.ROWS.get(), "目标语句返回行数");
        assertEquals(0, SubmissionSelectGuardInterceptor.LONG_FIELD_NON_NULL.get(),
                "列投影后目标语句返回实体的 answers 必须为 null");
        assertGuardSawStatement();
        assertEquals(FROZEN_COLUMNS, normalizedSelectList(SubmissionSelectGuardInterceptor.targetSql),
                "捕获 SELECT 列表必须 == 冻结列集：" + SubmissionSelectGuardInterceptor.targetSql);

        assertEquals(GV_ROWS, resp.getItems().size(), "预览条目数不变");
        List<BigDecimal> totals = resp.getItems().stream().map(ScoreItem::getTotalScore).toList();
        assertEquals(List.of(BigDecimal.valueOf(72.0), BigDecimal.valueOf(61.0), BigDecimal.valueOf(50.0)),
                totals, "按总分降序（50/61/72 降序）：预览口径不因投影改变");
        assertEquals(1, resp.getPartialGradedCount(), "partialGraded 口径不变（i%2==1 恰 1 行）");
        assertTrue(resp.getItems().stream().allMatch(it -> it.getStudentName() != null
                && !it.getStudentName().isEmpty()), "学生姓名照常解析（经 users 表，不受投影影响）");
    }

    private static BigDecimal score(int i) {
        return BigDecimal.valueOf(50.0 + i);
    }

    /** 护栏 canary：观测窗口内若目标语句一次都没被拦到，本断言失败（防“语句没跑＝全绿”的伪绿）。 */
    private void assertGuardSawStatement() {
        assertTrue(SubmissionSelectGuardInterceptor.EXECUTIONS.get() > 0,
                "canary：护栏必须实际拦到目标语句，否则本次护栏证据不成立");
        assertNotNull(SubmissionSelectGuardInterceptor.targetSql, "canary：须捕获到目标语句 SQL");
        System.out.println("CAPTURED_M5_SQL=" + SubmissionSelectGuardInterceptor.targetSql);
        String sqlLower = SubmissionSelectGuardInterceptor.targetSql.toLowerCase(Locale.ROOT);
        assertFalse(sqlLower.contains("answers"),
                "列投影后 SELECT 列表不得载入长字段：" + SubmissionSelectGuardInterceptor.targetSql);
    }

    private static String normalizedSelectList(String sql) {
        if (sql == null) {
            return "";
        }
        String upper = sql.toUpperCase(Locale.ROOT);
        int from = upper.indexOf(" FROM ");
        if (!upper.startsWith("SELECT ") || from < 0) {
            return "";
        }
        return sql.substring("SELECT ".length(), from).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private int count(String sql, Object... args) {
        Number n = jdbc.queryForObject(sql, Number.class, args);
        return n == null ? -1 : n.intValue();
    }

    private static LoginUser teacherLogin(long teacherId) {
        LoginUser user = new LoginUser();
        user.setId(teacherId);
        user.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.TEACHER));
        return user;
    }

    /**
     * 目标语句观测护栏（Executor 层，仅本测试上下文注册）：armed 窗口内观测
     * {@code GradingSubmissionMapper.selectList} 的执行条数与返回行，逐行检查长字段——
     * 任一 {@code GradingSubmission} 的 answers 非 null 即累计违规，由用例断言为 0。
     */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            org.apache.ibatis.cache.CacheKey.class, BoundSql.class})
    })
    static class SubmissionSelectGuardInterceptor implements Interceptor {

        static final String TARGET_MS = "com.exam.grading.mapper.GradingSubmissionMapper.selectList";
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
                    if (o instanceof com.exam.grading.entity.GradingSubmission s
                            && s.getAnswers() != null) {
                        LONG_FIELD_NON_NULL.incrementAndGet();
                    }
                }
            }
            return result;
        }
    }
}
