package com.exam.scalar.guard;

import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.exam.entity.Exam;
import com.exam.exam.service.AbsenceService;
import com.exam.submission.entity.ExamSubmission;
import com.exam.taking.dto.ExamListItem;
import com.exam.taking.service.ExamTakingService;
import org.apache.ibatis.cache.CacheKey;
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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标量站点列投影的常驻护栏（change project-scalar-only-submission-reads 阶段 3）：
 * 两个 GO 站点——学生考试列表（{@link ExamTakingService#myExams}）与缺考标记
 * （{@link AbsenceService#markAbsence}）——对答卷行只消费标量字段。列投影后目标语句
 * （{@code ExamSubmissionMapper.selectList}）返回实体的 {@code answers}/{@code paperJson}
 * 必须为 null，而库内同批行该列非 null（seed 前置断言，排除"库本来就空"的伪绿）。
 * 同批锁定两条路径的用户可见口径（分组、缺考差集）不因投影改变；
 * 取消投影或放宽投影列时，长字段非 null 计数即脱离 0，本测试变红。
 */
@SpringBootTest
@ActiveProfiles("test")
// 调度隔离：考试状态机「进行中→已结束」自动迁移会调用 markAbsence，其扫描语句与站点 2
// 目标语句同一条——护栏观测窗口内不得存在定时流量，否则条数/行数断言被污染。
@TestPropertySource(properties = {
        "exam.schedule.initial-delay-ms=3600000",
        "exam.schedule.fixed-delay-ms=3600000"
})
@DisplayName("标量站点列投影护栏：目标语句实体长字段为 null（库内非 null）且输出口径不变")
class ScalarProjectionGuardTest {

    private static final long S1_EXAM_BASE = 980_100_000L;
    private static final long S1_STUDENT = 980_100_100L;
    private static final long S1_CLASS = 980_100_050L;
    private static final long S2_EXAM = 980_200_000L;
    private static final long S2_CLASS = 980_200_100L;
    private static final long S2_STUDENT_BASE = 980_200_200L;
    private static final long OWNER_ID = 980_999_999L;

    private static final String LONG_ANSWERS = "{\"1001\":\"guard-answers-payload\"}";
    private static final String LONG_PAPER_JSON = "{\"snapshot\":\"guard-paper-payload\"}";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ExamTakingService takingService;
    @Autowired
    private AbsenceService absenceService;

    @TestConfiguration
    static class GuardConfig {
        @Bean
        Interceptor submissionSelectGuardInterceptor() {
            return new SubmissionSelectGuardInterceptor();
        }
    }

    @BeforeEach
    void resetGuard() {
        SubmissionSelectGuardInterceptor.reset();
    }

    @Test
    @DisplayName("myExams：投影取数，长字段为 null 且三组分组口径逐项一致")
    void myExamsProjectsScalarColumnsOnly() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.plusDays(3650);
        Timestamp nowTs = Timestamp.valueOf(now);

        jdbc.update("DELETE FROM user_class WHERE class_id = ?", S1_CLASS);
        jdbc.update("DELETE FROM classes WHERE id = ?", S1_CLASS);
        jdbc.update("DELETE FROM exam_submissions WHERE exam_id BETWEEN ? AND ?", S1_EXAM_BASE, S1_EXAM_BASE + 2);
        jdbc.update("DELETE FROM exams WHERE id BETWEEN ? AND ?", S1_EXAM_BASE, S1_EXAM_BASE + 2);

        jdbc.update("INSERT INTO classes (id, name, course_id, teacher_id, created_by, created_time,"
                        + " updated_time, is_deleted) VALUES (?,?,NULL,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                S1_CLASS, "护栏-列表班级", OWNER_ID, OWNER_ID);
        jdbc.update("INSERT INTO user_class (user_id, class_id, joined_time)"
                        + " VALUES (?,?,CURRENT_TIMESTAMP)",
                S1_STUDENT, S1_CLASS);

        jdbc.update("INSERT INTO exams (id, title, paper_id, class_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,60,?,1,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                S1_EXAM_BASE, "护栏-进行中", 980_100_900L, S1_CLASS, Timestamp.valueOf(start),
                Timestamp.valueOf(start.plusMinutes(60)), Exam.STATUS_IN_PROGRESS, OWNER_ID);
        jdbc.update("INSERT INTO exams (id, title, paper_id, class_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,60,?,1,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                S1_EXAM_BASE + 1, "护栏-已交卷", 980_100_901L, S1_CLASS, Timestamp.valueOf(start.plusHours(1)),
                Timestamp.valueOf(start.plusHours(2)), Exam.STATUS_IN_PROGRESS, OWNER_ID);
        jdbc.update("INSERT INTO exams (id, title, paper_id, class_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,60,?,1,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                S1_EXAM_BASE + 2, "护栏-待考", 980_100_902L, S1_CLASS, Timestamp.valueOf(start.plusHours(2)),
                Timestamp.valueOf(start.plusHours(3)), Exam.STATUS_NOT_STARTED, OWNER_ID);
        jdbc.update("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                        + " answers, paper_json, status, version, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                S1_EXAM_BASE, S1_STUDENT, nowTs, Timestamp.valueOf(now.plusMinutes(60)),
                LONG_ANSWERS, LONG_PAPER_JSON, ExamSubmission.STATUS_IN_PROGRESS);
        jdbc.update("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                        + " answers, paper_json, status, version, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                S1_EXAM_BASE + 1, S1_STUDENT, nowTs, Timestamp.valueOf(now.plusMinutes(60)),
                LONG_ANSWERS, LONG_PAPER_JSON, ExamSubmission.STATUS_SUBMITTED);

        assertEquals(2, count("SELECT COUNT(*) FROM exam_submissions WHERE student_id = ?"
                        + " AND answers IS NOT NULL AND paper_json IS NOT NULL", S1_STUDENT),
                "seed 前置：库内该学生确有长字段非 null 的行——实体读到 null 只能来自列投影");

        List<ExamListItem> items;
        SecurityUtil.set(studentLogin(S1_STUDENT));
        try {
            SubmissionSelectGuardInterceptor.armed = true;
            items = takingService.myExams();
            SubmissionSelectGuardInterceptor.armed = false;
        } finally {
            SecurityUtil.clear();
            SubmissionSelectGuardInterceptor.armed = false;
        }

        assertEquals(1, SubmissionSelectGuardInterceptor.EXECUTIONS.get(), "目标语句执行条数");
        assertEquals(2, SubmissionSelectGuardInterceptor.ROWS.get(), "目标语句返回行数（本人 2 行答卷）");
        assertEquals(0, SubmissionSelectGuardInterceptor.LONG_FIELD_NON_NULL.get(),
                "列投影后目标语句返回实体的 answers/paper_json 必须为 null");
        assertGuardSawStatement();

        List<ExamListItem> ours = items.stream()
                .filter(i -> i.getExamId() >= S1_EXAM_BASE && i.getExamId() <= S1_EXAM_BASE + 2)
                .toList();
        assertEquals(List.of(S1_EXAM_BASE + 2, S1_EXAM_BASE, S1_EXAM_BASE + 1),
                ours.stream().map(ExamListItem::getExamId).toList(), "按分组优先级：待考/进行中/已完成");
        ExamListItem upcoming = ours.get(0);
        assertEquals(ExamListItem.GROUP_UPCOMING, upcoming.getGroup());
        assertFalse(upcoming.isCanEnter());
        assertNull(upcoming.getSubmissionStatus());
        ExamListItem ongoing = ours.get(1);
        assertEquals(ExamListItem.GROUP_ONGOING, ongoing.getGroup());
        assertTrue(ongoing.isCanEnter());
        assertEquals(ExamSubmission.STATUS_IN_PROGRESS, ongoing.getSubmissionStatus());
        assertNotNull(ongoing.getRemainingSeconds());
        assertTrue(ongoing.getRemainingSeconds() > 0 && ongoing.getRemainingSeconds() <= 3600,
                "剩余秒数应为服务端截止邻近值：" + ongoing.getRemainingSeconds());
        ExamListItem finished = ours.get(2);
        assertEquals(ExamListItem.GROUP_FINISHED, finished.getGroup());
        assertFalse(finished.isCanEnter());
        assertEquals(ExamSubmission.STATUS_SUBMITTED, finished.getSubmissionStatus());
        assertNull(finished.getRemainingSeconds());

        // 清理班级夹具
        jdbc.update("DELETE FROM user_class WHERE class_id = ?", S1_CLASS);
        jdbc.update("DELETE FROM classes WHERE id = ?", S1_CLASS);
    }

    @Test
    @DisplayName("markAbsence：投影取数，长字段为 null 且缺考差集口径不变")
    void markAbsenceProjectsScalarColumnsOnly() {
        LocalDateTime now = LocalDateTime.now();
        Timestamp nowTs = Timestamp.valueOf(now);
        jdbc.update("DELETE FROM exam_absence WHERE exam_id = ?", S2_EXAM);
        jdbc.update("DELETE FROM exam_submissions WHERE exam_id = ?", S2_EXAM);
        jdbc.update("DELETE FROM user_class WHERE class_id = ?", S2_CLASS);
        jdbc.update("DELETE FROM classes WHERE id = ?", S2_CLASS);
        jdbc.update("DELETE FROM exams WHERE id = ?", S2_EXAM);
        jdbc.update("INSERT INTO exams (id, title, paper_id, class_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,60,2,1,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                S2_EXAM, "护栏-缺考", 980_200_900L, S2_CLASS, Timestamp.valueOf(now.minusHours(3)),
                Timestamp.valueOf(now.minusHours(2)), OWNER_ID);
        jdbc.update("INSERT INTO classes (id, name, course_id, teacher_id, created_by, created_time,"
                        + " updated_time, is_deleted) VALUES (?,?,NULL,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                S2_CLASS, "护栏-缺考班级", OWNER_ID, OWNER_ID);
        for (long sid : List.of(S2_STUDENT_BASE, S2_STUDENT_BASE + 1, S2_STUDENT_BASE + 2)) {
            jdbc.update("INSERT INTO user_class (user_id, class_id, joined_time)"
                    + " VALUES (?,?,CURRENT_TIMESTAMP)", sid, S2_CLASS);
        }
        jdbc.update("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                        + " answers, paper_json, status, version, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                S2_EXAM, S2_STUDENT_BASE + 1, nowTs, Timestamp.valueOf(now.minusMinutes(90)),
                LONG_ANSWERS, LONG_PAPER_JSON, ExamSubmission.STATUS_SUBMITTED);

        assertEquals(1, count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?"
                        + " AND answers IS NOT NULL AND paper_json IS NOT NULL", S2_EXAM),
                "seed 前置：库内该考试确有长字段非 null 的行——实体读到 null 只能来自列投影");

        int inserted;
        SubmissionSelectGuardInterceptor.armed = true;
        try {
            inserted = absenceService.markAbsence(S2_EXAM);
        } finally {
            SubmissionSelectGuardInterceptor.armed = false;
        }

        assertEquals(2, inserted, "差集：应考 3 人 − 有答卷 1 人 = 缺考 2 人");
        assertEquals(1, SubmissionSelectGuardInterceptor.EXECUTIONS.get(), "目标语句执行条数");
        assertEquals(1, SubmissionSelectGuardInterceptor.ROWS.get(), "目标语句返回行数（该场仅有答卷 1 行）");
        assertEquals(0, SubmissionSelectGuardInterceptor.LONG_FIELD_NON_NULL.get(),
                "列投影后目标语句返回实体的 answers/paper_json 必须为 null");
        assertGuardSawStatement();

        List<Long> absentIds = jdbc.queryForList(
                "SELECT student_id FROM exam_absence WHERE exam_id = ? ORDER BY student_id", Long.class, S2_EXAM);
        assertEquals(List.of(S2_STUDENT_BASE, S2_STUDENT_BASE + 2), absentIds, "缺考名单 = 名单 − 有答卷者");
        assertEquals(0, absenceService.markAbsence(S2_EXAM), "重复标记幂等：差集已空，不再新增");
    }

    /** 护栏 canary：观测窗口内若目标语句一次都没被拦到，本断言失败（防"语句没跑＝全绿"的伪绿）。 */
    private void assertGuardSawStatement() {
        assertTrue(SubmissionSelectGuardInterceptor.EXECUTIONS.get() > 0,
                "canary：护栏必须实际拦到目标语句，否则本次护栏证据不成立");
        assertNotNull(SubmissionSelectGuardInterceptor.targetSql, "canary：须捕获到目标语句 SQL");
        System.out.println("CAPTURED_S1_SQL=" + SubmissionSelectGuardInterceptor.targetSql);
        String sqlLower = SubmissionSelectGuardInterceptor.targetSql.toLowerCase(java.util.Locale.ROOT);
        assertFalse(sqlLower.contains("paper_json") || sqlLower.contains("answers"),
                "列投影后 SELECT 列表不得载入长字段：" + SubmissionSelectGuardInterceptor.targetSql);
    }

    private int count(String sql, Object... args) {
        Number n = jdbc.queryForObject(sql, Number.class, args);
        return n == null ? -1 : n.intValue();
    }

    private static LoginUser studentLogin(long studentId) {
        LoginUser user = new LoginUser();
        user.setId(studentId);
        user.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.STUDENT));
        return user;
    }

    /**
     * 目标语句观测护栏（Executor 层，仅本测试上下文注册）：armed 窗口内观测
     * {@code ExamSubmissionMapper.selectList} 的执行条数与返回行，逐行检查长字段——
     * 任一 {@link ExamSubmission} 的 answers/paperJson 非 null 即累计违规，由用例断言为 0。
     */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            CacheKey.class, BoundSql.class})
    })
    static class SubmissionSelectGuardInterceptor implements Interceptor {

        static final String TARGET_MS = "com.exam.submission.mapper.ExamSubmissionMapper.selectList";
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
                BoundSql boundSql = ms.getBoundSql(param);
                targetSql = boundSql.getSql();
                for (Object o : list) {
                    if (o instanceof ExamSubmission s
                            && (s.getAnswers() != null || s.getPaperJson() != null)) {
                        LONG_FIELD_NON_NULL.incrementAndGet();
                    }
                }
            }
            return result;
        }
    }
}
