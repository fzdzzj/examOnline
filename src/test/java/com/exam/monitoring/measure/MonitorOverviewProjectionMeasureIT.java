package com.exam.monitoring.measure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exam.auth.service.JwtUtil;
import com.exam.submission.entity.ExamSubmission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 监考总览答卷取数再归因捕获（站点 s6：`MonitorService.overview` 的答卷主语句）。
 *
 * <p>冻结口径见 `spec/changes/reattribute-monitor-overview-projection/evidence/PREREGISTRATION.md`
 * （本 IT 只忠实执行与逐字记录：不挑轮、不改写、失败轮原样保留并同位补跑记 retry）。三臂：
 * OLD（全列）/ PROJ（投影 `student_id,status` + 同计时窗口内至多一条窄读）/ OLDrep（OLD 等价副本）。
 *
 * <p>运行（仓库根；**只跑真 MySQL 引擎**，缺 `-Dmeasure.mysql.url` 直接失败）：
 * <pre>
 * set MONITOR_MYSQL_PWD=...
 * mvnw.cmd test -Dtest=MonitorOverviewProjectionMeasureIT -DfailIfNoTests=false ^
 *     -Dmeasure.rev=&lt;sha7&gt; -Dmeasure.label=run1 ^
 *     -Dmeasure.out=D:\code\examOnline-measure\monitor-projection\raw ^
 *     "-Dmeasure.mysql.url=jdbc:mysql://127.0.0.1:13319/monitor_projection_measure?..." ^
 *     -Dmeasure.mysql.user=root
 * </pre>
 * Redis 用一次性容器经 `REDIS_HOST/REDIS_PORT` 环境变量指向（测试档默认 db15）。
 * 类名以 IT 结尾，不进 Surefire 全量门禁。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "exam.schedule.initial-delay-ms=3600000",
        "exam.schedule.fixed-delay-ms=3600000"
})
@DisplayName("监考总览答卷取数再归因捕获（仅显式 -Dtest 运行；不进 Surefire 全量门禁）")
class MonitorOverviewProjectionMeasureIT {

    // ==================== 冻结口径（与 PREREGISTRATION.md §0/§1/§2 逐字对应） ====================

    private static final int[] SIZES = {200, 1000, 3000};
    private static final long EXAM_BASE = 976_000_000L;
    private static final long STUDENT_BASE = 976_400_000L;
    private static final long PLACEHOLDER_PAPER_ID = 976_000_001L;
    private static final long D1_EXAM = 976_000_101L;
    private static final long D2_EXAM = 976_000_102L;
    private static final long D3_EXAM = 976_000_103L;

    private static final String TARGET_MS_ID = "com.exam.submission.mapper.ExamSubmissionMapper.selectList";
    private static final String FROZEN_PROJ_SELECT = "student_id,status";
    private static final String NARROW_SQL =
            "SELECT paper_json FROM exam_submissions WHERE exam_id = ? AND paper_json IS NOT NULL"
                    + " AND paper_json <> '' LIMIT 1";

    private static final int ANSWERS_CHARS = 2048;
    private static final int PAPER_JSON_CHARS = 20480;
    // 20480 − 415（骨架 415 = {"questions":[ 14 + 40 项 351〔id≥10 为 9 字符〕+ 39 逗号 + ],"pad":" 9 + "} 2）
    private static final int PAPER_PAD_CHARS = 20065;
    private static final int PAPER_QUESTIONS = 40;
    private static final int DRAFT_ENTRIES = 12;
    private static final int ONLINE_EVERY = 5;
    private static final int ONLINE_OFFSET = 4;
    private static final int WARMUPS = 2;
    private static final int TIMED_ROUNDS = 5;
    private static final String[] ARMS = {"OLD", "PROJ", "OLDrep"};

    private static final String PREREG_REL =
            "spec/changes/reattribute-monitor-overview-projection/evidence/PREREGISTRATION.md";
    private static final String PREREG_SHA_REL =
            "spec/changes/reattribute-monitor-overview-projection/evidence/preregistration.sha256.txt";

    private final List<String> problems = new ArrayList<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JwtUtil jwtUtil;

    private String ownerToken;
    private long ownerId;
    private String otherToken;
    private String adminToken;

    private final List<String> redisKeys = new ArrayList<>();

    // ==================== 测试侧拦截器（仅本测试上下文生效） ====================

    @TestConfiguration
    static class MonitorMeasureConfig {
        @Bean
        Interceptor monitorSiteInterceptor(JdbcTemplate jdbc) {
            return new MonitorSiteInterceptor(jdbc);
        }

        @Bean
        Interceptor monitorSqlCaptureInterceptor() {
            return new MonitorSqlCaptureInterceptor();
        }
    }

    /** 臂运行上下文（MockMvc 同线程串行使用；静态持有当前臂与逐次调用证据）。 */
    static final class ArmCtx {
        static String arm;
        static long examId;
        static boolean inFlight;
        static int mainQueries;
        static int mainRows;
        static int narrowQueries;
        static int narrowRows;
        static int rawAnswersNonNull;
        static int rawPaperNonNull;
        static int suppliedReads;
        static int answersReadAttempts;
        static String suppliedValue;
        static List<Long> narrowParams = new ArrayList<>();
        static final List<Integer> paperJsonReadIndices = new ArrayList<>();
        static final List<Cap> captured = Collections.synchronizedList(new ArrayList<>());

        record Cap(String msId, String sql, LinkedHashMap<String, Object> values) {
        }

        static void begin(String armCode, long examIdBeingViewed) {
            arm = armCode;
            examId = examIdBeingViewed;
            inFlight = false;
            mainQueries = 0;
            mainRows = 0;
            narrowQueries = 0;
            narrowRows = 0;
            rawAnswersNonNull = 0;
            rawPaperNonNull = 0;
            suppliedReads = 0;
            answersReadAttempts = 0;
            suppliedValue = null;
            narrowParams = new ArrayList<>();
            paperJsonReadIndices.clear();
            captured.clear();
        }

        static void end() {
            arm = null;
            suppliedValue = null;
        }
    }

    /**
     * 目标语句返回实体的守卫行（`ExamSubmission` 子类，无需 Mockito）：
     * `getAnswers()` 一经调用即抛断言错；`getPaperJson()` 在 OLD 系臂放行并记读取行号，
     * 在 PROJ 臂仅允许第 0 行以窄读供给值返回——窄读返回 0 行（退化夹具的全空扫描）时逐行返回 null。
     */
    static final class GuardedRow extends ExamSubmission {
        private final int rowIndex;

        GuardedRow(int rowIndex) {
            this.rowIndex = rowIndex;
        }

        @Override
        public String getAnswers() {
            ArmCtx.answersReadAttempts++;
            throw new AssertionError(
                    "long-field read blocked: ExamSubmission.getAnswers() called by business path");
        }

        @Override
        public String getPaperJson() {
            if ("PROJ".equals(ArmCtx.arm)) {
                ArmCtx.paperJsonReadIndices.add(rowIndex);
                if (ArmCtx.suppliedValue == null) {
                    // 窄读 0 行（库内无非空快照）：按现码口径继续全空扫描，题数 0
                    return null;
                }
                if (rowIndex == 0) {
                    ArmCtx.suppliedReads++;
                    return ArmCtx.suppliedValue;
                }
                throw new AssertionError(
                        "PROJ 臂不应在行 " + rowIndex + " 读 paper_json（供给只允许第 0 行）");
            }
            ArmCtx.paperJsonReadIndices.add(rowIndex);
            return super.getPaperJson();
        }
    }

    /**
     * 站点拦截器（Executor 层）：① 记账（主语句条数/行数、窄读条数/行数）；
     * ② PROJ 臂在 `proceed()` 前对目标 wrapper 施加冻结列投影；③ `proceed()` 后若主语句有行，
     * 同窗口内同步执行至多一条窄读并供给题数来源；④ 返回实体换成守卫行并记长字段非空计数。
     */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            CacheKey.class, BoundSql.class})
    })
    static class MonitorSiteInterceptor implements Interceptor {

        private final JdbcTemplate jdbc;

        MonitorSiteInterceptor(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
            if (ArmCtx.arm == null || !TARGET_MS_ID.equals(ms.getId()) || ArmCtx.inFlight) {
                return invocation.proceed();
            }
            ArmCtx.inFlight = true;
            try {
                boolean proj = "PROJ".equals(ArmCtx.arm);
                ArmCtx.mainQueries++;
                if (proj) {
                    applyProjection(invocation.getArgs()[1]);
                }
                Object result = invocation.proceed();
                if (!(result instanceof List<?> list)) {
                    return result;
                }
                ArmCtx.mainRows += list.size();
                int ansNN = 0;
                int paperNN = 0;
                for (Object o : list) {
                    if (o instanceof ExamSubmission s) {
                        if (s.getAnswers() != null) {
                            ansNN++;
                        }
                        if (s.getPaperJson() != null) {
                            paperNN++;
                        }
                    }
                }
                ArmCtx.rawAnswersNonNull += ansNN;
                ArmCtx.rawPaperNonNull += paperNN;
                if (proj && !list.isEmpty()) {
                    narrowReadAndSupply();
                }
                return toGuardedRows(list, proj);
            } finally {
                ArmCtx.inFlight = false;
            }
        }

        @SuppressWarnings("unchecked")
        private static void applyProjection(Object parameter) {
            if (!(parameter instanceof Map<?, ?> map)) {
                return;
            }
            Object ew = map.get("ew");
            if (!(ew instanceof LambdaQueryWrapper<?> wrapper) || wrapper.getSqlSelect() != null) {
                return;
            }
            ((LambdaQueryWrapper<ExamSubmission>) wrapper).select(
                    ExamSubmission::getStudentId, ExamSubmission::getStatus);
        }

        /**
         * 至多一条窄读（只取 paper_json，单行）；返回 0 行时供给值为 null（按现码口径题数为 0）。
         * 参数取「被查看的考试」——与生产方法参数同源（投影后返回行不含 exam_id，不得从实体回读）。
         */
        private void narrowReadAndSupply() {
            Long examId = ArmCtx.examId;
            ArmCtx.narrowQueries++;
            List<String> values = jdbc.queryForList(NARROW_SQL, String.class, examId);
            ArmCtx.narrowRows = values.size();
            ArmCtx.suppliedValue = values.isEmpty() ? null : values.get(0);
            ArmCtx.narrowParams = List.of(examId);
        }

        private static List<Object> toGuardedRows(List<?> list, boolean proj) {
            List<Object> out = new ArrayList<>(list.size());
            int i = 0;
            for (Object o : list) {
                if (o instanceof ExamSubmission s) {
                    GuardedRow g = new GuardedRow(i);
                    g.setId(s.getId());
                    g.setExamId(s.getExamId());
                    g.setStudentId(s.getStudentId());
                    g.setStartTime(s.getStartTime());
                    g.setDeadlineTime(s.getDeadlineTime());
                    g.setSubmitTime(s.getSubmitTime());
                    g.setSubmitType(s.getSubmitType());
                    g.setPaperJson(proj ? null : s.getPaperJson());
                    g.setAnswers(proj ? null : s.getAnswers());
                    g.setStatus(s.getStatus());
                    g.setVersion(s.getVersion());
                    g.setCreatedTime(s.getCreatedTime());
                    g.setUpdatedTime(s.getUpdatedTime());
                    out.add(g);
                } else {
                    out.add(o);
                }
                i++;
            }
            return out;
        }
    }

    /** 语句原文捕获（只记录不改变行为）：拦 `StatementHandler.prepare`，读最终 BoundSql 与绑定参数。 */
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    static class MonitorSqlCaptureInterceptor implements Interceptor {

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            if (ArmCtx.arm == null) {
                return invocation.proceed();
            }
            Object t = invocation.getTarget();
            for (int i = 0; i < 5 && Proxy.isProxyClass(t.getClass()); i++) {
                InvocationHandler h = Proxy.getInvocationHandler(t);
                if (!(h instanceof Plugin)) {
                    break;
                }
                t = SystemMetaObject.forObject(h).getValue("target");
            }
            MetaObject mo = SystemMetaObject.forObject(t);
            if (mo.hasGetter("delegate")) {
                mo = SystemMetaObject.forObject(mo.getValue("delegate"));
            }
            MappedStatement ms = (MappedStatement) mo.getValue("mappedStatement");
            BoundSql boundSql = (BoundSql) mo.getValue("boundSql");
            ParameterHandler parameterHandler = (ParameterHandler) mo.getValue("parameterHandler");
            Object parameterObject = parameterHandler.getParameterObject();

            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            TypeHandlerRegistry registry = ms.getConfiguration().getTypeHandlerRegistry();
            for (ParameterMapping pm : boundSql.getParameterMappings()) {
                String prop = pm.getProperty();
                Object value;
                if (boundSql.hasAdditionalParameter(prop)) {
                    value = boundSql.getAdditionalParameter(prop);
                } else if (parameterObject == null) {
                    value = null;
                } else if (registry.hasTypeHandler(parameterObject.getClass())) {
                    value = parameterObject;
                } else {
                    value = ms.getConfiguration().newMetaObject(parameterObject).getValue(prop);
                }
                values.put(prop, value);
            }
            ArmCtx.captured.add(new ArmCtx.Cap(ms.getId(), boundSql.getSql(), values));
            return invocation.proceed();
        }
    }

    // ==================== MySQL 数据源注入（只跑真引擎，fail fast） ====================

    @DynamicPropertySource
    static void mysqlDatasource(DynamicPropertyRegistry registry) {
        String url = System.getProperty("measure.mysql.url");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "measure.mysql.url 未设置：本 IT 只跑一次性 mysql:8.0 真引擎（fail fast，不在 H2 上悄悄跑）");
        }
        String user = System.getProperty("measure.mysql.user", "root");
        String password = System.getenv("MONITOR_MYSQL_PWD");
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("MONITOR_MYSQL_PWD 环境变量未设置（口令不入库、不进命令日志）");
        }
        for (String ds : new String[]{"master", "slave"}) {
            registry.add("spring.datasource.dynamic.datasource." + ds + ".url", () -> url);
            registry.add("spring.datasource.dynamic.datasource." + ds + ".username", () -> user);
            registry.add("spring.datasource.dynamic.datasource." + ds + ".password", () -> password);
            registry.add("spring.datasource.dynamic.datasource." + ds + ".driver-class-name",
                    () -> "com.mysql.cj.jdbc.Driver");
        }
    }

    // ==================== 主流程 ====================

    @Test
    @DisplayName("逐形状三臂对拍与时序落盘；退化夹具仅做 S1 语义对拍")
    void measure() throws Exception {
        String rev = System.getProperty("measure.rev", "unknown");
        String label = System.getProperty("measure.label", "run1");
        Path out = Paths.get(System.getProperty("measure.out",
                "D:/code/examOnline-measure/monitor-projection/raw"));
        Files.createDirectories(out);

        ObjectNode manifest = om.createObjectNode();
        manifest.put("rev", rev);
        manifest.put("label", label);
        manifest.put("generatedAtIso", Instant.now().toString());
        recordPreregistration(manifest);

        registerAccounts();
        manifest.put("ownerId", ownerId);

        ArrayNode seeds = om.createArrayNode();
        for (int n : SIZES) {
            long examId = seedShape(n);
            seeds.add(seedCounts(n, examId));
            writeJson(out.resolve("capture-n" + n + ".json"), semanticCapture(n, examId));
            writeJson(out.resolve("rounds-n" + n + ".json"), timedRounds(n, examId));
        }
        writeJson(out.resolve("capture-degenerates.json"), degenerateCaptures());
        writeJson(out.resolve("seed-counts.json"), seeds);

        manifest.put("redisKeysDeleted", cleanupRedisKeys());

        ArrayNode problemNodes = manifest.putArray("problems");
        problems.forEach(problemNodes::add);
        manifest.put("problemsEmpty", problems.isEmpty());
        writeJson(out.resolve("capture-manifest.json"), manifest);

        if (!problems.isEmpty()) {
            throw new AssertionError("测量问题清单非空（停手保留现场）：" + problems);
        }
    }

    private void recordPreregistration(ObjectNode manifest) throws Exception {
        Path prereg = Paths.get(PREREG_REL);
        if (!Files.exists(prereg)) {
            prereg = Paths.get("..").resolve(PREREG_REL).normalize();
        }
        if (!Files.exists(prereg)) {
            problems.add("PREREGISTRATION 文件缺失: " + Paths.get(PREREG_REL).toAbsolutePath());
            return;
        }
        String recomputed = sha256(Files.readAllBytes(prereg));
        String recorded = "";
        Path shaFile = Paths.get(PREREG_SHA_REL);
        if (!Files.exists(shaFile)) {
            shaFile = Paths.get("..").resolve(PREREG_SHA_REL).normalize();
        }
        if (Files.exists(shaFile)) {
            String firstLine = Files.readString(shaFile, StandardCharsets.UTF_8).lines()
                    .findFirst().orElse("");
            recorded = firstLine.split("\\s+")[0];
        }
        manifest.put("preregRel", PREREG_REL);
        manifest.put("preregRecordedSha256", recorded);
        manifest.put("preregRecomputedSha256", recomputed);
        boolean match = !recorded.isEmpty() && recorded.equals(recomputed);
        manifest.put("preregMatch", match);
        if (!match) {
            problems.add("PREREGISTRATION sha256 不匹配: recorded=" + recorded + " recomputed=" + recomputed);
        }
    }

    // ==================== 造数 ====================

    private long seedShape(int n) {
        long examId = EXAM_BASE + n;
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int inProgress = 6 * n / 10;
        int submittedEnd = 85 * n / 100;

        jdbc.update("DELETE FROM exam_behavior_logs WHERE exam_id = ?", examId);
        jdbc.update("DELETE FROM exam_submissions WHERE exam_id = ?", examId);
        jdbc.update("DELETE FROM exams WHERE id = ?", examId);
        jdbc.update("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version) VALUES (?,?,?,?,?,?,?,?,?,?)",
                examId, "监考再归因-" + n, PLACEHOLDER_PAPER_ID, now, now, 60, 1, 1, ownerId, 0);

        String paper = paperJson();
        String answers = answersJson();
        List<Object[]> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int status = i < inProgress ? ExamSubmission.STATUS_IN_PROGRESS
                    : (i < submittedEnd ? ExamSubmission.STATUS_SUBMITTED : ExamSubmission.STATUS_GRADED);
            boolean done = status != ExamSubmission.STATUS_IN_PROGRESS;
            rows.add(new Object[]{examId, studentId(n, i), now, now, done ? now : null, done ? 1 : null,
                    paper, done ? answers : null, status, 0});
        }
        jdbc.batchUpdate("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                + " submit_time, submit_type, paper_json, answers, status, version)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)", rows);

        List<Object> userIds = new ArrayList<>();
        for (int i = 0; i < Math.min(10, n); i++) {
            userIds.add(studentId(n, i));
        }
        jdbc.update("DELETE FROM users WHERE id IN (" + placeholders(userIds.size()) + ")",
                userIds.toArray());
        for (int i = 0; i < userIds.size(); i++) {
            jdbc.update("INSERT INTO users (id, username, password, name, email, status,"
                            + " must_change_password) VALUES (?,?,?,?,?,?,?)",
                    userIds.get(i), "mo_meas_" + n + "_" + i, "x", "测量学生" + i, null, 1, 0);
        }

        List<Object[]> logs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int[] severities = (i % 10 == 0) ? new int[]{1, 1, 3}
                    : (i % 5 == 0) ? new int[]{1, 2} : new int[0];
            for (int k = 0; k < severities.length; k++) {
                logs.add(new Object[]{examId, studentId(n, i), "SWITCH_SCREEN", "{}", severities[k],
                        Timestamp.valueOf(now.toLocalDateTime().minusSeconds(60L - k))});
            }
        }
        if (!logs.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO exam_behavior_logs (exam_id, student_id, event_type,"
                    + " event_data, severity, event_time) VALUES (?,?,?,?,?,?)", logs);
        }

        String savedTime = LocalDateTime.now().toString();
        String draft = draftJson(savedTime);
        for (int i = 0; i < inProgress; i++) {
            String kind = draftKindFor(i);
            if (!"MISSING".equals(kind)) {
                String key = "exam:draft:" + examId + ":" + studentId(n, i);
                redis.opsForValue().set(key, draftValueOf(kind, draft), Duration.ofHours(2));
                redisKeys.add(key);
            }
            if (i % ONLINE_EVERY != ONLINE_OFFSET) {
                String key = "exam:monitor:online:" + examId + ":" + studentId(n, i);
                redis.opsForValue().set(key, "1", Duration.ofHours(2));
                redisKeys.add(key);
            }
        }
        return examId;
    }

    private static long studentId(int n, int i) {
        return STUDENT_BASE + n * 1000L + i;
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private ObjectNode seedCounts(int n, long examId) {
        ObjectNode node = om.createObjectNode();
        int inProgress = 6 * n / 10;
        int expectedLogs = 0;
        for (int i = 0; i < n; i++) {
            expectedLogs += (i % 10 == 0) ? 3 : (i % 5 == 0) ? 2 : 0;
        }
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?", Integer.class, examId);
        Integer paperNonNull = jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions"
                + " WHERE exam_id = ? AND paper_json IS NOT NULL", Integer.class, examId);
        Integer paperMin = jdbc.queryForObject("SELECT MIN(LENGTH(paper_json)) FROM exam_submissions"
                + " WHERE exam_id = ?", Integer.class, examId);
        Integer paperMax = jdbc.queryForObject("SELECT MAX(LENGTH(paper_json)) FROM exam_submissions"
                + " WHERE exam_id = ?", Integer.class, examId);
        Integer answersNonNull = jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions"
                + " WHERE exam_id = ? AND answers IS NOT NULL", Integer.class, examId);
        Integer answersMin = jdbc.queryForObject("SELECT MIN(LENGTH(answers)) FROM exam_submissions"
                + " WHERE exam_id = ? AND answers IS NOT NULL", Integer.class, examId);
        Integer answersMax = jdbc.queryForObject("SELECT MAX(LENGTH(answers)) FROM exam_submissions"
                + " WHERE exam_id = ? AND answers IS NOT NULL", Integer.class, examId);
        Integer logs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_behavior_logs WHERE exam_id = ?", Integer.class, examId);
        node.put("shape", n);
        node.put("examId", examId);
        node.put("designRows", n);
        node.put("actualRows", rows == null ? -1 : rows);
        node.put("designInProgress", inProgress);
        node.put("designPaperJsonNonNull", n);
        node.put("actualPaperJsonNonNull", paperNonNull == null ? -1 : paperNonNull);
        node.put("paperJsonLenMin", paperMin == null ? -1 : paperMin);
        node.put("paperJsonLenMax", paperMax == null ? -1 : paperMax);
        node.put("designAnswersNonNull", n - inProgress);
        node.put("actualAnswersNonNull", answersNonNull == null ? -1 : answersNonNull);
        node.put("answersLenMin", answersMin == null ? -1 : answersMin);
        node.put("answersLenMax", answersMax == null ? -1 : answersMax);
        node.put("designLogs", expectedLogs);
        node.put("actualLogs", logs == null ? -1 : logs);
        boolean ok = rows != null && rows == n
                && paperNonNull != null && paperNonNull == n
                && paperMin != null && paperMin == PAPER_JSON_CHARS
                && paperMax != null && paperMax == PAPER_JSON_CHARS
                && answersNonNull != null && answersNonNull == n - inProgress
                && answersMin != null && answersMin == ANSWERS_CHARS
                && answersMax != null && answersMax == ANSWERS_CHARS
                && logs != null && logs == expectedLogs;
        node.put("ok", ok);
        if (!ok) {
            problems.add("seed 校验不符 n=" + n + ": " + node);
        }
        return node;
    }

    private static String paperJson() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < PAPER_QUESTIONS; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"id\":").append(i + 1).append('}');
        }
        String body = "{\"questions\":[" + items + "],\"pad\":\"" + "B".repeat(PAPER_PAD_CHARS) + "\"}";
        if (body.length() != PAPER_JSON_CHARS) {
            throw new IllegalStateException("paper_json 构造长度不符: " + body.length());
        }
        return body;
    }

    private static String answersJson() {
        String body = "{\"1001\":\"" + "A".repeat(ANSWERS_CHARS - 9 - 2) + "\"}";
        if (body.length() != ANSWERS_CHARS) {
            throw new IllegalStateException("answers 构造长度不符: " + body.length());
        }
        return body;
    }

    private static String draftJson(String savedTime) {
        StringBuilder sb = new StringBuilder("{\"version\":1,\"answers\":{");
        for (int i = 0; i < DRAFT_ENTRIES; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(1000 + i).append("\":\"A\"");
        }
        sb.append("},\"marked\":[],\"savedTime\":\"").append(savedTime).append("\"}");
        return sb.toString();
    }

    /** 每 60 个进行中学生取 4 个退化形态（与前一测量夹具同口径）。 */
    private static String draftKindFor(int i) {
        return switch (i % 60) {
            case 0 -> "CORRUPT";
            case 15 -> "NON_OBJECT";
            case 30 -> "MISSING";
            case 45 -> "BLANK";
            default -> "NORMAL";
        };
    }

    private static String draftValueOf(String kind, String draftJson) {
        return switch (kind) {
            case "BLANK" -> "";
            case "CORRUPT" -> "{\"version\":1,\"answers\":{\"1000\":\"A\"";
            case "NON_OBJECT" -> draftJson.replace("\"answers\":{", "\"answers\":\"oops\",\"_x\":{");
            default -> draftJson;
        };
    }

    // ==================== 语义层（S1 + 护栏） ====================

    private ObjectNode semanticCapture(int n, long examId) {
        ObjectNode shape = om.createObjectNode();
        shape.put("shape", n);
        shape.put("examId", examId);
        shape.put("expectedQuestions", PAPER_QUESTIONS);
        ObjectNode arms = shape.putObject("arms");

        Resp oldOwner = callArm("OLD", arms.putObject("OLD"), examId, ownerToken, n, PAPER_QUESTIONS, false);
        Resp projOwner = callArm("PROJ", arms.putObject("PROJ"), examId, ownerToken, n, PAPER_QUESTIONS, false);
        Resp oldAdmin = callArm("OLD", arms.putObject("adminOLD"), examId, adminToken, n, PAPER_QUESTIONS, false);
        Resp projAdmin = callArm("PROJ", arms.putObject("adminPROJ"), examId, adminToken, n, PAPER_QUESTIONS, false);
        Resp oldOther = callArm("OLD", arms.putObject("nonOwnerOLD"), examId, otherToken, 0, 0, true);
        Resp projOther = callArm("PROJ", arms.putObject("nonOwnerPROJ"), examId, otherToken, 0, 0, true);

        boolean ownerEqual = jsonEquals(oldOwner.normalized(), projOwner.normalized());
        boolean adminEqual = jsonEquals(oldAdmin.normalized(), projAdmin.normalized());
        boolean nonOwnerSame = oldOther.status() == projOther.status()
                && Objects.equals(oldOther.root(), projOther.root());
        shape.put("s1OwnerEqual", ownerEqual);
        shape.put("s1AdminEqual", adminEqual);
        shape.put("s1NonOwnerSame", nonOwnerSame);
        shape.put("nonOwnerStatus", oldOther.status());

        ObjectNode env = shape.putObject("envChecks");
        int inProgress = 6 * n / 10;
        int online = 0;
        for (int i = 0; i < inProgress; i++) {
            if (i % ONLINE_EVERY != ONLINE_OFFSET) {
                online++;
            }
        }
        int abnormal = 0;
        for (int i = 0; i < n; i++) {
            if (i % 5 == 0) {
                abnormal++;
            }
        }
        int degenerate = 0;
        for (int i = 0; i < inProgress; i++) {
            if (!"NORMAL".equals(draftKindFor(i))) {
                degenerate++;
            }
        }
        JsonNode data = oldOwner.root() == null ? null : oldOwner.root().path("data");
        int answered12 = 0;
        int submittedFull = 0;
        if (data != null && data.path("students").isArray()) {
            for (JsonNode s : data.path("students")) {
                if (s.path("answeredCount").asInt(-1) == DRAFT_ENTRIES) {
                    answered12++;
                }
                if (s.path("answeredCount").asInt(-1) == PAPER_QUESTIONS) {
                    submittedFull++;
                }
            }
        }
        env.put("totalStudents", data == null ? -1 : data.path("totalStudents").asInt(-1));
        env.put("totalQuestions", data == null ? -1 : data.path("totalQuestions").asInt(-1));
        env.put("onlineCount", data == null ? -1 : data.path("onlineCount").asInt(-1));
        env.put("offlineCount", data == null ? -1 : data.path("offlineCount").asInt(-1));
        env.put("submittedCount", data == null ? -1 : data.path("submittedCount").asInt(-1));
        env.put("abnormalCount", data == null ? -1 : data.path("abnormalCount").asInt(-1));
        env.put("answeredDraftEntries", answered12);
        env.put("answeredFull", submittedFull);
        boolean envOk = data != null
                && env.path("totalStudents").asInt(-1) == n
                && env.path("totalQuestions").asInt(-1) == PAPER_QUESTIONS
                && env.path("onlineCount").asInt(-1) == online
                && env.path("offlineCount").asInt(-1) == inProgress - online
                && env.path("submittedCount").asInt(-1) == n - inProgress
                && env.path("abnormalCount").asInt(-1) == abnormal
                && answered12 == inProgress - degenerate
                && submittedFull == n - inProgress;
        env.put("ok", envOk);
        if (!envOk) {
            problems.add("环境/口径校验不符 n=" + n + "（草稿或计数未按设计生效）: " + env);
        }
        if (!oldOwner.ok() || !projOwner.ok()) {
            problems.add("owner 调用语义不符 n=" + n + " oldOk=" + oldOwner.ok()
                    + " projOk=" + projOwner.ok() + " oldStatus=" + oldOwner.status()
                    + " projStatus=" + projOwner.status() + " oldErr=" + oldOwner.error()
                    + " projErr=" + projOwner.error());
        }
        if (!ownerEqual) {
            problems.add("S1 不成立（owner 响应两臂不等价）n=" + n);
        }
        if (!adminEqual) {
            problems.add("S1 不成立（admin 响应两臂不等价）n=" + n);
        }
        if (!nonOwnerSame || oldOther.status() != 403) {
            problems.add("S1 不成立（非 owner 两臂不一致或状态非 403）n=" + n
                    + " old=" + oldOther.status() + " proj=" + projOther.status());
        }
        return shape;
    }

    private ObjectNode degenerateCaptures() {
        ObjectNode root = om.createObjectNode();
        root.set("D1", degenerateCapture("D1", D1_EXAM, 0, 0));
        root.set("D2", degenerateCapture("D2", D2_EXAM, 3, 0));
        root.set("D3", degenerateCapture("D3", D3_EXAM, 3, PAPER_QUESTIONS));
        return root;
    }

    /** D1 空场 / D2 全空快照 / D3 全已交卷（均无进行中，presence 键不预置）。 */
    private ObjectNode degenerateCapture(String name, long examId, int rows, int expectedQuestions) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("DELETE FROM exam_behavior_logs WHERE exam_id = ?", examId);
        jdbc.update("DELETE FROM exam_submissions WHERE exam_id = ?", examId);
        jdbc.update("DELETE FROM exams WHERE id = ?", examId);
        jdbc.update("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version) VALUES (?,?,?,?,?,?,?,?,?,?)",
                examId, "监考再归因-" + name, PLACEHOLDER_PAPER_ID, now, now, 60, 1, 1, ownerId, 0);
        if (rows > 0) {
            String paper = "D2".equals(name) ? null : paperJson();
            String answers = "D2".equals(name) ? null : answersJson();
            List<Object[]> batch = new ArrayList<>(rows);
            for (int i = 0; i < rows; i++) {
                batch.add(new Object[]{examId, examId * 1000L + i, now, now, now, 1, paper, answers,
                        ExamSubmission.STATUS_SUBMITTED, 0});
            }
            jdbc.batchUpdate("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                    + " submit_time, submit_type, paper_json, answers, status, version)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)", batch);
        }

        ObjectNode node = om.createObjectNode();
        node.put("name", name);
        node.put("examId", examId);
        node.put("expectedQuestions", expectedQuestions);
        Resp oldResp = callArm("OLD", node.putObject("OLD"), examId, ownerToken, rows, expectedQuestions, false);
        Resp projResp = callArm("PROJ", node.putObject("PROJ"), examId, ownerToken, rows, expectedQuestions, false);
        boolean equal = jsonEquals(oldResp.normalized(), projResp.normalized());
        int questions = oldResp.root() == null ? -1
                : oldResp.root().path("data").path("totalQuestions").asInt(-1);
        node.put("s1Equal", equal);
        node.put("observedQuestions", questions);
        boolean ok = equal && questions == expectedQuestions && oldResp.ok() && projResp.ok();
        node.put("ok", ok);
        if (!ok) {
            problems.add("退化夹具 " + name + " S1 不成立: equal=" + equal + " questions=" + questions
                    + " oldOk=" + oldResp.ok() + " projOk=" + projResp.ok());
        }
        ObjectNode projGuard = (ObjectNode) node.path("PROJ").path("guard");
        if ("D1".equals(name) && projGuard.path("narrowQueries").asInt(-1) != 0) {
            problems.add("退化夹具 D1 主语句 0 行 ⇒ 窄读应为 0 条，实际 "
                    + projGuard.path("narrowQueries").asInt(-1));
        }
        if ("D2".equals(name) && projGuard.path("narrowRows").asInt(-1) != 0) {
            problems.add("退化夹具 D2 的窄读返回行数应为 0，实际 "
                    + projGuard.path("narrowRows").asInt(-1));
        }
        if ("D3".equals(name) && projGuard.path("narrowRows").asInt(-1) != 1) {
            problems.add("退化夹具 D3 的窄读返回行数应为 1，实际 "
                    + projGuard.path("narrowRows").asInt(-1));
        }
        return node;
    }

    // ==================== 时序层（三臂轮转） ====================

    private ObjectNode timedRounds(int n, long examId) {
        ObjectNode shape = om.createObjectNode();
        shape.put("shape", n);
        shape.put("examId", examId);
        shape.put("warmupsPerArm", WARMUPS);
        shape.put("timedRounds", TIMED_ROUNDS);
        ObjectNode armsNode = shape.putObject("arms");
        for (String arm : ARMS) {
            armsNode.putObject(arm).putArray("rounds");
        }
        for (int w = 0; w < WARMUPS; w++) {
            for (String arm : ARMS) {
                ObjectNode ignored = om.createObjectNode();
                Resp r = callArm(arm, ignored, examId, ownerToken, n, PAPER_QUESTIONS, false);
                if (!r.ok()) {
                    problems.add("预热失败 n=" + n + " arm=" + arm + " status=" + r.status()
                            + " error=" + r.error());
                }
            }
        }
        for (int r = 1; r <= TIMED_ROUNDS; r++) {
            int rot = (r - 1) % 3;
            for (int k = 0; k < 3; k++) {
                String arm = ARMS[(rot + k) % 3];
                ArrayNode rounds = (ArrayNode) armsNode.get(arm).get("rounds");
                for (int attempt = 1; attempt <= 2; attempt++) {
                    ObjectNode node = om.createObjectNode();
                    node.put("round", r);
                    node.put("attempt", attempt);
                    node.put("atIso", Instant.now().toString());
                    Resp resp = callArm(arm, node, examId, ownerToken, n, PAPER_QUESTIONS, false);
                    node.put("exitCode", resp.ok() ? 0 : 1);
                    rounds.add(node);
                    if (resp.ok()) {
                        break;
                    }
                }
            }
        }
        return shape;
    }

    // ==================== 单次臂调用与护栏记账 ====================

    private record Resp(int status, byte[] body, long nanos, String error, boolean ok,
                        int totalStudents, int totalQuestions, JsonNode root) {
        JsonNode normalized() {
            return root == null ? null : normalizeTree(root.deepCopy());
        }
    }

    private Resp callArm(String arm, ObjectNode node, long examId, String token,
                         int expectedStudents, int expectedQuestions, boolean expectForbidden) {
        ArmCtx.begin(arm, examId);
        Resp r = null;
        try {
            r = performOverview("/api/exams/" + examId + "/monitor/overview", token,
                    expectedStudents, expectedQuestions, expectForbidden);
            return r;
        } finally {
            try {
                fillGuard(node, arm, examId, expectedStudents, expectForbidden, r);
            } finally {
                ArmCtx.end();
            }
        }
    }

    private Resp performOverview(String path, String token, int expectedStudents,
                                 int expectedQuestions, boolean expectForbidden) {
        long t0 = System.nanoTime();
        int status = 0;
        byte[] body = new byte[0];
        String error = null;
        try {
            MvcResult result = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andReturn();
            status = result.getResponse().getStatus();
            body = result.getResponse().getContentAsByteArray();
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        long nanos = System.nanoTime() - t0;
        JsonNode root = null;
        try {
            root = body.length > 0 ? om.readTree(body) : null;
        } catch (Exception ignored) {
            // 非 JSON 响应（如容器错误页）留给调用侧的 ok 判定报错
        }
        int ts = -1;
        int tq = -1;
        if (root != null) {
            JsonNode data = root.path("data");
            ts = data.path("totalStudents").asInt(-1);
            tq = data.path("totalQuestions").asInt(-1);
        }
        boolean ok = error == null && (expectForbidden
                ? status == 403
                : status == 200 && ts == expectedStudents && tq == expectedQuestions);
        return new Resp(status, body, nanos, error, ok, ts, tq, root);
    }

    /** 把本次调用的护栏记账与语句捕获写入 node（node 由调用方决定落位）。 */
    private void fillGuard(ObjectNode node, String arm, long examId, int expectedStudents,
                           boolean expectForbidden, Resp r) {
        boolean proj = "PROJ".equals(arm);
        node.put("arm", arm);
        node.put("status", r == null ? -1 : r.status());
        node.put("ns", r == null ? -1 : r.nanos());
        node.put("ok", r != null && r.ok());
        node.put("totalStudents", r == null ? -1 : r.totalStudents());
        node.put("totalQuestions", r == null ? -1 : r.totalQuestions());
        if (r != null && r.error() != null) {
            node.put("error", r.error());
        }

        ObjectNode guard = node.putObject("guard");
        guard.put("arm", arm);
        guard.put("expectForbidden", expectForbidden);
        guard.put("expectedStudents", expectedStudents);
        guard.put("mainQueries", ArmCtx.mainQueries);
        guard.put("mainRows", ArmCtx.mainRows);
        guard.put("narrowQueries", ArmCtx.narrowQueries);
        guard.put("narrowRows", ArmCtx.narrowRows);
        guard.put("rawAnswersNonNull", ArmCtx.rawAnswersNonNull);
        guard.put("rawPaperNonNull", ArmCtx.rawPaperNonNull);
        guard.put("suppliedReads", ArmCtx.suppliedReads);
        guard.put("answersReadAttempts", ArmCtx.answersReadAttempts);
        ArrayNode idx = guard.putArray("paperJsonReadIndices");
        ArmCtx.paperJsonReadIndices.forEach(idx::add);
        ArrayNode narrowParams = guard.putArray("narrowParams");
        ArmCtx.narrowParams.forEach(narrowParams::add);
        guard.put("narrowSql", NARROW_SQL);

        Integer dbPaper = jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions"
                + " WHERE exam_id = ? AND paper_json IS NOT NULL AND paper_json <> ''", Integer.class, examId);
        Integer dbAnswers = jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions"
                + " WHERE exam_id = ? AND answers IS NOT NULL", Integer.class, examId);
        int paperNonNull = dbPaper == null ? -1 : dbPaper;
        int answersNonNull = dbAnswers == null ? -1 : dbAnswers;
        guard.put("dbPaperJsonNonNull", paperNonNull);
        guard.put("dbAnswersNonNull", answersNonNull);

        int expMainQueries = expectForbidden ? 0 : 1;
        int expMainRows = expectForbidden ? 0 : expectedStudents;
        int expNarrowQueries = (!expectForbidden && proj && expectedStudents > 0) ? 1 : 0;
        int expNarrowRows = expNarrowQueries == 1 ? Math.min(Math.max(paperNonNull, 0), 1) : 0;
        guard.put("expMainQueries", expMainQueries);
        guard.put("expMainRows", expMainRows);
        guard.put("expNarrowQueries", expNarrowQueries);
        guard.put("expNarrowRows", expNarrowRows);
        guard.put("expSuppliedReads", proj ? expNarrowRows : 0);

        List<Integer> indices = List.copyOf(ArmCtx.paperJsonReadIndices);
        boolean prefixOk = true;
        for (int i = 0; i < indices.size(); i++) {
            if (indices.get(i) != i) {
                prefixOk = false;
                break;
            }
        }
        guard.put("paperJsonReadPrefix", prefixOk);

        List<ArmCtx.Cap> targets = ArmCtx.captured.stream()
                .filter(c -> TARGET_MS_ID.equals(c.msId())).toList();
        ArrayNode selectLists = guard.putArray("targetSelectLists");
        boolean selectListOk = targets.size() == (expectForbidden ? 0 : 1);
        for (ArmCtx.Cap c : targets) {
            String sel = selectListOf(c.sql());
            selectLists.add(sel);
            if (proj ? !FROZEN_PROJ_SELECT.equals(sel)
                    : (FROZEN_PROJ_SELECT.equals(sel) || !sel.contains("paper_json")
                       || !sel.contains("answers"))) {
                selectListOk = false;
            }
        }
        guard.put("targetSelectListOk", selectListOk);

        boolean mainOk = ArmCtx.mainQueries == expMainQueries && ArmCtx.mainRows == expMainRows;
        boolean narrowOk = ArmCtx.narrowQueries == expNarrowQueries && ArmCtx.narrowRows == expNarrowRows;
        boolean longFieldOk;
        if (expectForbidden) {
            longFieldOk = ArmCtx.rawAnswersNonNull == 0 && ArmCtx.rawPaperNonNull == 0;
        } else if (proj) {
            longFieldOk = ArmCtx.rawAnswersNonNull == 0 && ArmCtx.rawPaperNonNull == 0
                    && ArmCtx.suppliedReads == expNarrowRows;
        } else {
            longFieldOk = ArmCtx.rawPaperNonNull == paperNonNull && ArmCtx.rawAnswersNonNull == answersNonNull;
        }
        boolean answersOk = ArmCtx.answersReadAttempts == 0;
        boolean ok = mainOk && narrowOk && longFieldOk && answersOk && prefixOk && selectListOk;
        guard.put("mainOk", mainOk);
        guard.put("narrowOk", narrowOk);
        guard.put("longFieldOk", longFieldOk);
        guard.put("answersOk", answersOk);
        guard.put("ok", ok);
        if (!ok) {
            problems.add("护栏不成立 arm=" + arm + " exam=" + examId + " guard=" + guard);
        }

        ArrayNode captures = node.putArray("captures");
        for (ArmCtx.Cap c : List.copyOf(ArmCtx.captured)) {
            ObjectNode cn = captures.addObject();
            cn.put("msId", c.msId());
            cn.put("sql", c.sql());
            cn.put("selectList", selectListOf(c.sql()));
            ObjectNode values = cn.putObject("values");
            c.values().forEach((k, v) -> values.put(k, v == null ? null : String.valueOf(v)));
        }
    }

    /** 机械取 SELECT 列表（空白规范化后无空格），供 M1 判据与字节算子用。 */
    private static String selectListOf(String sql) {
        String norm = sql.replaceAll("\\s+", " ").trim();
        String upper = norm.toUpperCase();
        int s = upper.indexOf("SELECT ");
        int f = upper.indexOf(" FROM ");
        if (s < 0 || f < 0 || f <= s) {
            return "";
        }
        return norm.substring(s + 7, f).replaceAll("\\s+", "");
    }

    private static JsonNode normalizeTree(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node instanceof ObjectNode obj) {
            JsonNode t = obj.get("lastAbnormalTime");
            if (t != null && !t.isNull()) {
                obj.put("lastAbnormalTime", "+T");
            }
            List<String> names = new ArrayList<>();
            obj.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                normalizeTree(obj.get(name));
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode child : arr) {
                normalizeTree(child);
            }
        }
        return node;
    }

    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        return Objects.equals(a, b);
    }

    private void writeJson(Path path, JsonNode node) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, om.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n",
                StandardCharsets.UTF_8);
    }

    private int cleanupRedisKeys() {
        int deleted = redisKeys.size();
        if (!redisKeys.isEmpty()) {
            redis.delete(redisKeys);
            redisKeys.clear();
        }
        return deleted;
    }

    // ==================== 账号 ====================

    private void registerAccounts() throws Exception {
        adminToken = login("admin", "admin123");
        ownerToken = registerAndLoginTeacher("mo_owner_");
        ownerId = jwtUtil.parseAccessToken(ownerToken).getId();
        if (ownerId <= 0) {
            throw new IllegalStateException("未能取到 owner 教师 user id");
        }
        otherToken = registerAndLoginTeacher("mo_other_");
    }

    /** 管理员发邀请码 → 注册教师 → 登录，返回 access token。 */
    private String registerAndLoginTeacher(String prefix) throws Exception {
        MvcResult invite = mockMvc.perform(post("/api/admin/invite-codes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        if (invite.getResponse().getStatus() != 200) {
            throw new IllegalStateException("管理员发邀请码失败: "
                    + invite.getResponse().getContentAsString());
        }
        String code = om.readTree(invite.getResponse().getContentAsString())
                .get("data").get("code").asText();

        String username = prefix + System.nanoTime();
        ObjectNode reg = om.createObjectNode();
        reg.put("username", username);
        reg.put("password", "Pass1234");
        reg.put("name", "监考测量教师");
        reg.put("email", username + "@measure.test");
        reg.put("roleType", "TEACHER");
        reg.put("inviteCode", code);
        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reg.toString()))
                .andReturn();
        if (regResult.getResponse().getStatus() != 200) {
            throw new IllegalStateException("注册测量教师失败: "
                    + regResult.getResponse().getContentAsString());
        }
        return login(username, "Pass1234");
    }

    private String login(String username, String password) throws Exception {
        ObjectNode body = om.createObjectNode();
        body.put("username", username);
        body.put("password", password);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException("登录失败(" + username + "): "
                    + result.getResponse().getContentAsString());
        }
        return om.readTree(result.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
