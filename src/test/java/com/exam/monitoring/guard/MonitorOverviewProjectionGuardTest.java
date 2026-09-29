package com.exam.monitoring.guard;

import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.exam.entity.Exam;
import com.exam.monitoring.dto.MonitorOverviewResponse;
import com.exam.monitoring.dto.MonitorStudentItem;
import com.exam.monitoring.service.MonitorService;
import com.exam.submission.entity.ExamSubmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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
 * 监考总览答卷取数护栏（change reattribute-monitor-overview-projection 阶段 3）：
 * {@link MonitorService#overview} 对答卷行的主语句只取标量列（student_id/status），
 * 题目总数的个人快照改由至多一条窄读（仅 paper_json，LIMIT 1）获取。
 *
 * <p>哨兵口径与站点 s6 测量冻结形态一致：主语句返回实体的 answers/paper_json 必须为 null
 * （seed 前置断言库内同谓词行确有非 null 长字段，排除"库本来就空"的伪绿）；
 * 窄读语句恰执行一次、SQL 只取 paper_json 且带 LIMIT 1；取消投影、放宽投影列、
 * 去掉窄读或窄读膨胀（逐人读/整行读）都会让本测试变红。
 *
 * <p>同批锁定用户可见口径：人数统计、题数分母（跨考试参数不串场——诱饵考场题数不同）、
 * 逐学生状态与进度（含无快照/零题不除零）与排序不变。
 */
@SpringBootTest
@ActiveProfiles("test")
// 调度隔离：考试状态机「未开始→进行中→已结束」自动迁移在观测窗口内不得有定时流量；
// 进行中兜底扫描在 application-test.yml 已拉到 1h。
@TestPropertySource(properties = {
        "exam.schedule.initial-delay-ms=3600000",
        "exam.schedule.fixed-delay-ms=3600000"
})
@DisplayName("监考总览取数护栏：主语句列投影且长字段为 null，题数至多一次窄读")
class MonitorOverviewProjectionGuardTest {

    private static final long OWNER_ID = 980_300_000L;
    private static final long EXAM_ID = 980_300_001L;
    /** 诱饵考场：题数与主考场不同，窄读若参数错位会立刻偏离 40。 */
    private static final long DECOY_EXAM_ID = 980_300_002L;
    private static final long EMPTY_EXAM_ID = 980_300_003L;
    private static final long NULL_SNAPSHOT_EXAM_ID = 980_300_004L;
    private static final long S_ONLINE = 980_300_101L;
    private static final long S_OFFLINE = 980_300_102L;
    private static final long S_SUBMITTED = 980_300_103L;
    private static final long S_DECOY = 980_300_104L;
    private static final long S_NULL_SNAPSHOT = 980_300_105L;
    private static final int TOTAL_QUESTIONS = 40;
    private static final int DECOY_QUESTIONS = 7;
    private static final String PRESENCE_KEY = "exam:monitor:online:" + EXAM_ID + ":" + S_ONLINE;
    private static final String DRAFT_KEY = "exam:draft:" + EXAM_ID + ":" + S_ONLINE;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MonitorService monitorService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class GuardConfig {
        @Bean
        Interceptor submissionReadGuardInterceptor() {
            return new SubmissionReadGuardInterceptor();
        }
    }

    @BeforeEach
    void resetGuard() {
        SubmissionReadGuardInterceptor.reset();
    }

    @Test
    @DisplayName("主语句投影取数：长字段为 null，题数经一次窄读且带被查看考试参数")
    void overviewProjectsScalarColumnsAndCountsQuestionsViaSingleNarrowRead() {
        LocalDateTime now = LocalDateTime.now();
        Timestamp started = Timestamp.valueOf(now.minusMinutes(10));
        Timestamp deadline = Timestamp.valueOf(now.plusMinutes(50));
        seedExam(EXAM_ID, "护栏-监考投影", started, deadline);
        seedExam(DECOY_EXAM_ID, "护栏-诱饵考场", started, deadline);
        insertSubmission(EXAM_ID, S_ONLINE, ExamSubmission.STATUS_IN_PROGRESS,
                paperJson(TOTAL_QUESTIONS), answersJson(2), started, deadline);
        insertSubmission(EXAM_ID, S_OFFLINE, ExamSubmission.STATUS_IN_PROGRESS,
                "", null, started, deadline);
        insertSubmission(EXAM_ID, S_SUBMITTED, ExamSubmission.STATUS_SUBMITTED,
                null, answersJson(2), started, deadline);
        insertSubmission(DECOY_EXAM_ID, S_DECOY, ExamSubmission.STATUS_SUBMITTED,
                paperJson(DECOY_QUESTIONS), answersJson(1), started, deadline);
        redis.opsForValue().set(PRESENCE_KEY, "1");
        redis.opsForValue().set(DRAFT_KEY, draftJson(2));

        assertEquals(1, count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?"
                        + " AND answers IS NOT NULL AND paper_json IS NOT NULL", EXAM_ID),
                "seed 前置：库内该场确有长字段非 null 的行——实体读到 null 只能来自列投影");

        MonitorOverviewResponse response;
        SecurityUtil.set(teacherLogin());
        SubmissionReadGuardInterceptor.armed = true;
        try {
            response = monitorService.overview(EXAM_ID);
        } finally {
            SubmissionReadGuardInterceptor.armed = false;
            SecurityUtil.clear();
        }

        assertTrue(SubmissionReadGuardInterceptor.TARGET_EXECUTIONS.get() > 0,
                "canary：护栏必须实际拦到主语句，否则本次护栏证据不成立");
        assertEquals(1, SubmissionReadGuardInterceptor.TARGET_EXECUTIONS.get(), "主语句执行条数");
        assertEquals(3, SubmissionReadGuardInterceptor.TARGET_ROWS.get(), "主语句返回行数（本场 3 行答卷）");
        assertEquals(0, SubmissionReadGuardInterceptor.LONG_FIELD_NON_NULL.get(),
                "列投影后主语句返回实体的 answers/paper_json 必须为 null");
        assertNotNull(SubmissionReadGuardInterceptor.targetSql, "canary：须捕获到主语句 SQL");
        String targetSql = SubmissionReadGuardInterceptor.targetSql.toLowerCase(Locale.ROOT);
        assertFalse(targetSql.contains("paper_json") || targetSql.contains("answers"),
                "主语句 SELECT 列表不得再载入长字段：" + SubmissionReadGuardInterceptor.targetSql);

        assertEquals(1, SubmissionReadGuardInterceptor.NARROW_EXECUTIONS.get(),
                "题数窄读必须恰一次（至多一条的按实记账）");
        assertNotNull(SubmissionReadGuardInterceptor.narrowSql, "canary：须捕获到窄读 SQL");
        String narrowSql = SubmissionReadGuardInterceptor.narrowSql.toLowerCase(Locale.ROOT);
        assertTrue(narrowSql.contains("select paper_json") && narrowSql.contains("limit 1"),
                "窄读形态必须只取 paper_json 且 LIMIT 1：" + SubmissionReadGuardInterceptor.narrowSql);
        assertFalse(narrowSql.contains("answers"), "窄读不得捎带 answers：" + narrowSql);
        assertNotNull(SubmissionReadGuardInterceptor.narrowValue, "窄读须实际返回快照值");
        assertTrue(SubmissionReadGuardInterceptor.narrowValue.contains("questions"),
                "窄读返回值应为个人快照 JSON：" + SubmissionReadGuardInterceptor.narrowValue);

        assertEquals(EXAM_ID, response.getExamId());
        assertEquals(3, response.getTotalStudents());
        assertEquals(TOTAL_QUESTIONS, response.getTotalQuestions(),
                "题数分母来自被查看考试的窄读快照（诱饵考场题数不同，参数错位会偏离）");
        assertEquals(1, response.getSubmittedCount());
        assertEquals(1, response.getOnlineCount());
        assertEquals(1, response.getOfflineCount());
        assertEquals(0, response.getAbnormalCount());

        List<MonitorStudentItem> students = response.getStudents();
        assertEquals(3, students.size());
        assertEquals(S_SUBMITTED, students.get(0).getStudentId().longValue(), "已交卷 100% 排前");
        assertEquals(MonitorStudentItem.STATUS_SUBMITTED, students.get(0).getStatus());
        assertEquals(TOTAL_QUESTIONS, students.get(0).getAnsweredCount());
        assertEquals(100, students.get(0).getProgressPercent());
        assertEquals(S_ONLINE, students.get(1).getStudentId().longValue());
        assertEquals(MonitorStudentItem.STATUS_ONLINE, students.get(1).getStatus());
        assertEquals(2, students.get(1).getAnsweredCount());
        assertEquals(5, students.get(1).getProgressPercent(), "草稿 2 键 / 40 题 = 5%");
        assertEquals(S_OFFLINE, students.get(2).getStudentId().longValue());
        assertEquals(MonitorStudentItem.STATUS_OFFLINE, students.get(2).getStatus());
        assertEquals(0, students.get(2).getAnsweredCount());
        assertEquals(0, students.get(2).getProgressPercent());
    }

    @Test
    @DisplayName("退化形状记账：空场不窄读；无可用快照时窄读恰一次且题数按 0")
    void degenerateShapesKeepNarrowReadAccounting() {
        LocalDateTime now = LocalDateTime.now();
        Timestamp started = Timestamp.valueOf(now.minusMinutes(10));
        Timestamp deadline = Timestamp.valueOf(now.plusMinutes(50));
        seedExam(EMPTY_EXAM_ID, "护栏-空场", started, deadline);
        seedExam(NULL_SNAPSHOT_EXAM_ID, "护栏-无快照", started, deadline);
        insertSubmission(NULL_SNAPSHOT_EXAM_ID, S_NULL_SNAPSHOT, ExamSubmission.STATUS_IN_PROGRESS,
                null, null, started, deadline);

        SecurityUtil.set(teacherLogin());
        try {
            MonitorOverviewResponse empty;
            SubmissionReadGuardInterceptor.armed = true;
            try {
                empty = monitorService.overview(EMPTY_EXAM_ID);
            } finally {
                SubmissionReadGuardInterceptor.armed = false;
            }
            assertEquals(1, SubmissionReadGuardInterceptor.TARGET_EXECUTIONS.get(), "空场主语句执行条数");
            assertEquals(0, SubmissionReadGuardInterceptor.TARGET_ROWS.get(), "空场主语句 0 行");
            assertEquals(0, SubmissionReadGuardInterceptor.NARROW_EXECUTIONS.get(),
                    "主语句 0 行 ⇒ 不得为不存在的行集空跑窄读");
            assertEquals(0, empty.getTotalStudents());
            assertEquals(0, empty.getTotalQuestions());

            SubmissionReadGuardInterceptor.reset();
            MonitorOverviewResponse noSnapshot;
            SubmissionReadGuardInterceptor.armed = true;
            try {
                noSnapshot = monitorService.overview(NULL_SNAPSHOT_EXAM_ID);
            } finally {
                SubmissionReadGuardInterceptor.armed = false;
            }
            assertEquals(1, SubmissionReadGuardInterceptor.TARGET_EXECUTIONS.get(), "无快照场主语句执行条数");
            assertEquals(1, SubmissionReadGuardInterceptor.TARGET_ROWS.get(), "无快照场主语句 1 行");
            assertEquals(0, SubmissionReadGuardInterceptor.LONG_FIELD_NON_NULL.get(),
                    "列投影后实体长字段必须为 null");
            assertEquals(1, SubmissionReadGuardInterceptor.NARROW_EXECUTIONS.get(),
                    "有行但无可用快照：窄读仍按实记账恰一次（返回 0 行）");
            assertEquals(0, noSnapshot.getTotalQuestions(), "无可用快照 ⇒ 题数按现码口径 0");
            assertEquals(1, noSnapshot.getTotalStudents());
            assertEquals(0, noSnapshot.getStudents().get(0).getProgressPercent(),
                    "题数为 0 时进度仍为 0（不得除零）");
        } finally {
            SecurityUtil.clear();
        }
    }

    // ==================== 造数 ====================

    private void seedExam(long examId, String title, Timestamp start, Timestamp end) {
        jdbc.update("DELETE FROM exam_submissions WHERE exam_id = ?", examId);
        jdbc.update("DELETE FROM exams WHERE id = ?", examId);
        // published=0：本夹具只服务监考总览（overview 不读发布态）；共享 H2 下学生考试列表
        // 按开始时间倒序只取第 1 页 50 条，发布态夹具会挤占该窗口并波及其他用例。
        jdbc.update("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,60,?,0,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                examId, title, 980_300_900L, start, end, Exam.STATUS_IN_PROGRESS, OWNER_ID);
    }

    private void insertSubmission(long examId, long studentId, int status, String paperJson,
                                  String answers, Timestamp start, Timestamp deadline) {
        jdbc.update("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                        + " paper_json, answers, status, version, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                examId, studentId, start, deadline, paperJson, answers, status);
    }

    private String paperJson(int questions) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode arr = root.putArray("questions");
        for (int i = 1; i <= questions; i++) {
            arr.addObject().put("id", i);
        }
        return root.toString();
    }

    private String answersJson(int count) {
        ObjectNode answers = objectMapper.createObjectNode();
        for (int i = 1; i <= count; i++) {
            answers.put(String.valueOf(i), "A");
        }
        return answers.toString();
    }

    private String draftJson(int count) {
        ObjectNode answers = objectMapper.createObjectNode();
        for (int i = 1; i <= count; i++) {
            answers.put(String.valueOf(i), "A");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", 1);
        root.set("answers", answers);
        root.putArray("marked");
        root.put("savedTime", LocalDateTime.now().toString());
        return root.toString();
    }

    private int count(String sql, Object... args) {
        Number n = jdbc.queryForObject(sql, Number.class, args);
        return n == null ? -1 : n.intValue();
    }

    private static LoginUser teacherLogin() {
        LoginUser user = new LoginUser();
        user.setId(OWNER_ID);
        user.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.TEACHER));
        return user;
    }

    /**
     * 取数观测护栏（Executor 层，仅本测试上下文注册）：armed 窗口内观测
     * ①主语句 {@code ExamSubmissionMapper.selectList} 的执行条数/返回行/长字段非空数（须为 0），
     * 并留证其 SQL 文本；②窄读 {@code ExamSubmissionMapper.selectFirstPaperJson} 的执行次数
     * 与 SQL 文本/返回值。任一断言口径被破坏（如投影被取消、窄读消失或膨胀）即变红。
     */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            CacheKey.class, BoundSql.class})
    })
    static class SubmissionReadGuardInterceptor implements Interceptor {

        static final String TARGET_MS = "com.exam.submission.mapper.ExamSubmissionMapper.selectList";
        static final String NARROW_MS =
                "com.exam.submission.mapper.ExamSubmissionMapper.selectFirstPaperJson";
        static final AtomicInteger TARGET_EXECUTIONS = new AtomicInteger();
        static final AtomicInteger TARGET_ROWS = new AtomicInteger();
        static final AtomicInteger LONG_FIELD_NON_NULL = new AtomicInteger();
        static final AtomicInteger NARROW_EXECUTIONS = new AtomicInteger();
        static volatile String targetSql;
        static volatile String narrowSql;
        static volatile String narrowValue;
        static volatile boolean armed;

        static void reset() {
            TARGET_EXECUTIONS.set(0);
            TARGET_ROWS.set(0);
            LONG_FIELD_NON_NULL.set(0);
            NARROW_EXECUTIONS.set(0);
            targetSql = null;
            narrowSql = null;
            narrowValue = null;
            armed = false;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
            String id = ms.getId();
            boolean observe = armed;
            if (observe && TARGET_MS.equals(id)) {
                targetSql = ms.getBoundSql(invocation.getArgs()[1]).getSql();
            }
            Object result = invocation.proceed();
            if (observe && TARGET_MS.equals(id) && result instanceof List<?> list) {
                TARGET_EXECUTIONS.incrementAndGet();
                TARGET_ROWS.addAndGet(list.size());
                for (Object o : list) {
                    if (o instanceof ExamSubmission s
                            && (s.getAnswers() != null || s.getPaperJson() != null)) {
                        LONG_FIELD_NON_NULL.incrementAndGet();
                    }
                }
            }
            if (observe && NARROW_MS.equals(id)) {
                NARROW_EXECUTIONS.incrementAndGet();
                narrowSql = ms.getBoundSql(invocation.getArgs()[1]).getSql();
                narrowValue = result == null ? null : String.valueOf(result);
            }
            return result;
        }
    }
}
