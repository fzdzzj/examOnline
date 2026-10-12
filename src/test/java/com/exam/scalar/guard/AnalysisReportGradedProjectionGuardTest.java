package com.exam.scalar.guard;

import com.exam.exam.entity.Exam;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.score.dto.ExamAnalysisReportResponse;
import com.exam.score.service.ExamAnalysisReportService;
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
 * 分析报告送审联名单列投影的常驻护栏（change project-analysis-report-graded-read 阶段 3）：
 * {@link ExamAnalysisReportService#buildReport} 的全场已汇总取数入口
 * {@code listGraded}（{@link GradingSubmissionMapper#selectList}）只消费冻结标量列
 * student_id 与 total_score（班级概览 actual/totals、关注名单 studentId+totalScore、
 * gradedStudentIds 只用 studentId），不得不带 answers LONGTEXT。
 * 目标语句返回实体的 {@code answers} 必须为 null，而库内同批行该列非 null
 * （seed 前置断言，排除"库本来就空"的伪绿）；取消投影或放宽投影列时，
 * 长字段非 null 计数即脱离 0、且捕获 SELECT 列表偏离冻结列集，本测试变红。
 *
 * <p>同请求内 {@code ScoreExportService.aggregateQuestionStats} 经 {@code forEachSubmissionPage}
 * 对其分页 {@code GradingSubmissionMapper.selectList}（带 ORDER BY id ASC LIMIT）维持自身取数，
 * 属本变更零触碰域（逐题指标路径），本护栏按语句形状（无 ORDER BY/LIMIT）只观测 listGraded 语句，
 * 不对分页语句作任何断言。
 */
@SpringBootTest
@ActiveProfiles("test")
// 调度隔离：与本仓库既有 ScalarProjectionGuardTest 同口径，观测窗口内不得存在定时流量。
@TestPropertySource(properties = {
        "exam.schedule.initial-delay-ms=3600000",
        "exam.schedule.fixed-delay-ms=3600000"
})
@DisplayName("分析报告全场已汇总列投影护栏：listGraded 只取 student_id,total_score")
class AnalysisReportGradedProjectionGuardTest {

    private static final long GV_EXAM = 981_700_000L;
    private static final long GV_PAPER = 981_700_900L;
    private static final long GV_OWNER = 981_799_999L;
    private static final long GV_STUDENT_BASE = 981_700_100L;
    private static final int GV_ROWS = 3;
    // 冻结列集（机械归因：班会激活调取总量/缺席不用 studentId；消费列见类 javadoc）
    private static final String FROZEN_COLUMNS = "student_id,total_score";
    private static final String LONG_ANSWERS = "{\"1001\":\"guard-analysis-answers\"}";
    private static final String PAPER_JSON =
            "{\"paperId\":" + GV_PAPER + ",\"title\":\"护栏-分析报告\",\"totalScore\":20.0,\"questions\":["
                    + "{\"number\":1,\"questionId\":1001001,\"type\":1,\"content\":\"q1\","
                    + "\"choices\":[\"A\",\"B\"],\"correctAnswer\":\"A\",\"score\":4.0}"
                    + "]}";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ExamAnalysisReportService analysisReportService;

    @TestConfiguration
    static class GuardConfig {
        @Bean
        Interceptor gradedSelectGuardInterceptor() {
            return new GradedSelectGuardInterceptor();
        }
    }

    @BeforeEach
    void resetGuard() {
        GradedSelectGuardInterceptor.reset();
    }

    @Test
    @DisplayName("listGraded：SELECT 列表==冻结列集，实体 answers 为 null 且库内非 null，报告口径不变")
    void listGradedProjectsScalarColumnsOnly() {
        Timestamp nowTs = Timestamp.valueOf(LocalDateTime.now());
        Timestamp deadline = Timestamp.valueOf(LocalDateTime.now().plusHours(2));

        jdbc.update("DELETE FROM exam_submissions WHERE exam_id = ?", GV_EXAM);
        jdbc.update("DELETE FROM exam_snapshots WHERE exam_id = ?", GV_EXAM);
        jdbc.update("DELETE FROM users WHERE id BETWEEN ? AND ?", GV_STUDENT_BASE, GV_STUDENT_BASE + GV_ROWS - 1);
        jdbc.update("DELETE FROM exams WHERE id = ?", GV_EXAM);

        jdbc.update("INSERT INTO exams (id, title, paper_id, class_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,NULL,?,?,60,?,1,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                GV_EXAM, "护栏-分析报告", GV_PAPER, nowTs, deadline,
                Exam.STATUS_GRADED, GV_OWNER);

        jdbc.update("INSERT INTO exam_snapshots (exam_id, exam_json, paper_json, version, created_by)"
                        + " VALUES (?,?,?,1,?)",
                GV_EXAM, "{}", PAPER_JSON, GV_OWNER);

        for (int i = 0; i < GV_ROWS; i++) {
            long sid = GV_STUDENT_BASE + i;
            jdbc.update("INSERT INTO users (id, username, password, name, status, must_change_password, is_deleted)"
                            + " VALUES (?,?,?,?,1,0,0)",
                    sid, "gv_ar_" + sid, "x", "护栏生" + i);
            jdbc.update("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, submit_time,"
                            + " answers, paper_json, status, version, objective_score, subjective_score, total_score,"
                            + " grading_status, partial_graded, created_time, updated_time)"
                            + " VALUES (?,?,?,?,?,?,?,?,0,?,?,?,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    GV_EXAM, sid, nowTs, deadline, nowTs, LONG_ANSWERS, PAPER_JSON,
                    ExamSubmission.STATUS_GRADED, BigDecimal.valueOf(4.0 + i), BigDecimal.valueOf(6.0),
                    BigDecimal.valueOf(10.0 + i), i % 2);
        }

        assertEquals(GV_ROWS, count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?"
                        + " AND answers IS NOT NULL AND status = ?", GV_EXAM, ExamSubmission.STATUS_GRADED),
                "seed 前置：库内该场确有 answers 非 null 的已汇总行——实体读到 null 只能来自列投影");

        ExamAnalysisReportResponse resp;
        GradedSelectGuardInterceptor.armed = true;
        try {
            resp = analysisReportService.buildReport(GV_EXAM);
        } finally {
            GradedSelectGuardInterceptor.armed = false;
        }

        assertGuardSawListGraded();
        assertEquals(0, GradedSelectGuardInterceptor.LIST_GRADED_LONG_NON_NULL.get(),
                "列投影后 listGraded 返回实体 answers 必须为 null");
        assertEquals(FROZEN_COLUMNS, normalizedSelectList(GradedSelectGuardInterceptor.listGradedSql),
                "listGraded SELECT 列表必须 == 冻结列集：" + GradedSelectGuardInterceptor.listGradedSql);

        assertEquals(GV_ROWS, resp.getClassOverview().getActualCount(), "实考口径不变（3 份已汇总）");
        assertEquals(0, resp.getClassOverview().getAbsenceCount(), "缺席 0（class_id 为空，应考名单空）");
    }

    /** 护栏 canary：arm 窗口内必须实捕到 listGraded 语句，防"语句没跑＝全绿"伪绿。 */
    private void assertGuardSawListGraded() {
        assertEquals(1, GradedSelectGuardInterceptor.LIST_GRADED_EXECUTIONS.get(),
                "canary：护栏必须实捕到且仅捕到一次 listGraded 语句");
        assertNotNull(GradedSelectGuardInterceptor.listGradedSql, "canary：须捕获到 listGraded SQL");
        System.out.println("CAPTURED_LIST_GRADED_SQL=" + GradedSelectGuardInterceptor.listGradedSql);
        String sqlLower = GradedSelectGuardInterceptor.listGradedSql.toLowerCase(Locale.ROOT);
        assertFalse(sqlLower.contains("answers"),
                "列投影后 listGraded SELECT 列表不得载入长字段："
                        + GradedSelectGuardInterceptor.listGradedSql);
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

    /**
     * listGraded 语句观测护栏（Executor 层，仅本测试上下文注册）。arm 窗口内观测
     * {@code GradingSubmissionMapper.selectList} 的每一条执行，按语句形状分流：
     * 无 ORDER BY / 无 LIMIT 的执行判定为 listGraded 目标语句（listGraded 查询形态），
     * 对其返回行逐行检查 answers——任一非 null 即累计违规，由用例断言为 0。
     * 带 ORDER BY id ASC LIMIT 的是 aggregateQuestionStats 分页语句（零触碰域），不观测不断言。
     */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            org.apache.ibatis.cache.CacheKey.class, BoundSql.class})
    })
    static class GradedSelectGuardInterceptor implements Interceptor {

        static final String TARGET_MS = "com.exam.grading.mapper.GradingSubmissionMapper.selectList";
        static final AtomicInteger LIST_GRADED_EXECUTIONS = new AtomicInteger();
        static final AtomicInteger LIST_GRADED_LONG_NON_NULL = new AtomicInteger();
        static volatile String listGradedSql;
        static volatile boolean armed;

        static void reset() {
            LIST_GRADED_EXECUTIONS.set(0);
            LIST_GRADED_LONG_NON_NULL.set(0);
            listGradedSql = null;
            armed = false;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
            if (armed && ms.getId().equals(TARGET_MS) && result instanceof List<?> list) {
                Object param = invocation.getArgs()[1];
                String sql = ms.getBoundSql(param).getSql();
                if (!isListGradedShape(sql)) {
                    // aggregateQuestionStats 分页语句（逐题指标零触碰域）：不观测不断言
                    return result;
                }
                LIST_GRADED_EXECUTIONS.incrementAndGet();
                listGradedSql = sql;
                for (Object o : list) {
                    if (o instanceof GradingSubmission s && s.getAnswers() != null) {
                        LIST_GRADED_LONG_NON_NULL.incrementAndGet();
                    }
                }
            }
            return result;
        }

        private static boolean isListGradedShape(String sql) {
            String lower = sql.toLowerCase(Locale.ROOT);
            // listGraded 无 ORDER BY / 无 LIMIT；aggregate 分页查询带 ORDER BY id ASC LIMIT
            return !lower.contains("order by") && !lower.contains("limit");
        }
    }
}