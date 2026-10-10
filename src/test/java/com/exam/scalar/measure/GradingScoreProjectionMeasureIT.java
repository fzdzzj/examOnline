package com.exam.scalar.measure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.exam.entity.Exam;
import com.exam.grading.dto.GradingProgressResponse;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.service.GradingQueryService;
import com.exam.score.dto.ScorePreviewResponse;
import com.exam.score.service.ScoreQueryService;
import com.exam.score.service.ScoreService;
import com.exam.submission.entity.ExamSubmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 判分／成绩读形态列投影的归因测量工具（project-grading-score-scalar-projection 阶段 3）。
 *
 * <p><b>为什么类名以 IT 结尾</b>：Surefire 默认只收 {@code *Test} 等四类命名，本类不进
 * {@code mvnw clean test} 全量门禁；只有显式 {@code -Dtest=GradingScoreProjectionMeasureIT} 才运行。
 * 它只做三臂测量与 oracle 对拍，不构成业务断言基线（内部断言只证明夹具与捕获口径自洽）。
 *
 * <p><b>一次运行做什么</b>：对三个端点（progress / summarize / publishPreview）× 三个形状
 * （n ∈ {200,1000,3000}）执行 OLD 臂（现状全列取数）、PROJ 臂（冻结列投影，测试侧拦截器施加）、
 * OLDrep 臂（OLD 等价副本）。每臂 2 次预热 + 5 个计时轮，第 r 轮臂序左转 (r−1) mod 3。
 * 逐轮记录 wall-clock、退出码、ISO 时间戳、全部语句账目与 casSummarize 逐次参数；
 * S1 以响应 DTO 全字段 oracle 对拍；长字段护栏 =「读取即失败」spy 包装 + 库内非 null 机械计数。
 * 全部落盘 JSON 供 analyze-grading-score-projection.cjs 按冻结 PREREGISTRATION 算子裁决。
 *
 * <p><b>不触碰生产代码</b>：不改 src/main、SQL、schema、Mapper、JVM/线程池配置；拦截器只在
 * 本测试的 {@code @TestConfiguration} 内注册，仅本进程生效。PROJ 臂的施加方式与阶段 4 的生产
 * {@code .select(...)} 同源（冻结列一字不差），测量期间 src/main 零改动。
 *
 * <p><b>运行</b>（仓库根，经 run-grading-score-projection-mysql.mjs --phase rounds 驱动）：
 * <pre>
 * mvnw.cmd test -Dtest=GradingScoreProjectionMeasureIT -DfailIfNoTests=false
 *     -Dmeasure.rev=&lt;sha7&gt; -Dmeasure.label=rounds1 -Dmeasure.out=D:\code\examOnline-measure\grading-score-projection
 *     -Dmeasure.mysql.url=jdbc:mysql://127.0.0.1:13321/grading_score_projection_measure?...
 * </pre>
 * MySQL master/slave 经 @DynamicPropertySource 指向同一一次性容器（progress 挂 @DS("slave")，
 * 本装置下 slave==master 同引擎）；缺失 -Dmeasure.mysql.url 直接失败，不在 H2 上悄悄跑。
 */
@SpringBootTest
@ActiveProfiles("test")
// 冻结期间的调度隔离：application-test.yml 只把 taking.sweep 拉到 1h，考试状态机（exam.schedule）
// 仍是 10s tick——其 autoAdvance 会写 exams/exam_submissions 并可能触发 markAbsence 级联，
// 一旦在臂运行窗口内触发就会污染条数/行数记账与计时。本 IT 把状态机 tick 同样拉到 1h。
@TestPropertySource(properties = {
        "exam.schedule.initial-delay-ms=3600000",
        "exam.schedule.fixed-delay-ms=3600000"
})
@DisplayName("判分/成绩读形态列投影三臂测量（仅显式 -Dtest 运行；不进 Surefire 全量门禁）")
class GradingScoreProjectionMeasureIT {

    // ==================== 冻结口径（与 PREREGISTRATION.md §0/§1 逐字对应） ====================

    private static final int[] SIZES = {200, 1000, 3000};
    private static final List<String> ENDPOINTS = List.of("progress", "summarize", "preview");
    private static final List<String> ARM_CYCLE = List.of("OLD", "PROJ", "OLDrep");
    private static final int WARMUPS = 2;
    private static final int TIMED_ROUNDS = 5;

    private static final long EXAM_P_BASE = 981_100_000L;
    private static final long EXAM_S_BASE = 981_200_000L;
    private static final long EXAM_V_BASE = 981_300_000L;
    private static final long OWNER_ID = 981_000_001L;
    private static final long STUDENT_BASE = 982_000_000L;
    private static final long QUESTION_1 = 981_999_901L;
    private static final long QUESTION_2 = 981_999_902L;
    private static final long PAPER_ID = 981_999_900L;

    /** 长字段（全 ASCII ⇒ 字符数 = 字节数）；student_answer 及以下为设计值（PREREG §1）。 */
    private static final int ANSWERS_CHARS = 2048;
    private static final int PAPER_JSON_CHARS = 20480;
    private static final int STUDENT_ANSWER_CHARS = 512;
    private static final int DETAIL_CHARS = 64;
    private static final int COMMENT_CHARS = 64;

    private static final String TARGET_GRADING_MS =
            "com.exam.grading.mapper.GradingSubmissionMapper.selectList";
    private static final String TARGET_SUBJECTIVE_MS =
            "com.exam.grading.mapper.SubjectiveGradeMapper.selectList";
    private static final String CAS_MS =
            "com.exam.grading.mapper.GradingSubmissionMapper.casSummarize";

    /** 冻结 PROJ 列（实体声明序；M5 与指导草案的差异＝含 objective_score，见 PREREG §0）。 */
    private static final Map<String, String> FROZEN_GRADING_COLS = Map.of(
            "progress", "id,grading_status",
            "summarize", "id,grading_status,objective_score",
            "preview", "student_id,objective_score,subjective_score,total_score,partial_graded");
    private static final Map<String, String> FROZEN_SUBJECTIVE_COLS = Map.of(
            "progress", "submission_id,score",
            "summarize", "submission_id,question_id,score");

    /** 端点 → 单元编码（PREREG §0；analyze 脚本按同一编码出逐单元裁决）。 */
    private static final Map<String, String> UNIT_OF_GRADING = Map.of(
            "progress", "M1", "summarize", "M3", "preview", "M5");
    private static final Map<String, String> UNIT_OF_SUBJECTIVE = Map.of(
            "progress", "M2", "summarize", "M4");

    private static final String PREREG_ACTIVE =
            "spec/changes/project-grading-score-scalar-projection/evidence/PREREGISTRATION.md";
    private static final String PREREG_ARCHIVE =
            "spec/changes/archive/project-grading-score-scalar-projection/evidence/PREREGISTRATION.md";

    private final List<String> problems = new ArrayList<>();

    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private GradingQueryService gradingQueryService;
    @Autowired
    private ScoreService scoreService;
    @Autowired
    private ScoreQueryService scoreQueryService;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String port = System.getProperty("measure.mysql.port");
        String database = System.getProperty("measure.mysql.database", "grading_score_projection_measure");
        if (port == null || port.isBlank()) {
            throw new IllegalStateException(
                    "-Dmeasure.mysql.port missing: refusing to run the measure IT on H2 (fail fast)");
        }
        // 与 dev 同款 URL 参数（PREREG §2.0 冻结）；不经命令行传 '&'（E2/cmd 安全）
        String url = "jdbc:mysql://127.0.0.1:" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&rewriteBatchedStatements=true&useSSL=false&allowPublicKeyRetrieval=true";
        String username = System.getProperty("measure.mysql.username", "root");
        String password = System.getenv("MEASURE_MYSQL_PWD");
        if (password == null) {
            throw new IllegalStateException("env MEASURE_MYSQL_PWD missing (not logged to disk)");
        }
        registry.add("spring.datasource.dynamic.datasource.master.url", () -> url);
        registry.add("spring.datasource.dynamic.datasource.master.username", () -> username);
        registry.add("spring.datasource.dynamic.datasource.master.password", () -> password);
        registry.add("spring.datasource.dynamic.datasource.master.driver-class-name",
                () -> "com.mysql.cj.jdbc.Driver");
        // slave 指向同一容器：progress 挂 @DS("slave")，本装置下 slave==master 同引擎（PREREG §2.0）
        registry.add("spring.datasource.dynamic.datasource.slave.url", () -> url);
        registry.add("spring.datasource.dynamic.datasource.slave.username", () -> username);
        registry.add("spring.datasource.dynamic.datasource.slave.password", () -> password);
        registry.add("spring.datasource.dynamic.datasource.slave.driver-class-name",
                () -> "com.mysql.cj.jdbc.Driver");
    }

    @TestConfiguration
    static class MeasureConfig {
        @Bean
        Interceptor measureSiteInterceptor() {
            return new MeasureSiteInterceptor();
        }

        @Bean
        Interceptor measureSqlCaptureInterceptor() {
            return new MeasureSqlCaptureInterceptor();
        }
    }

    // ==================== 臂运行状态（单线程串行） ====================

    static final class RunState {
        static String endpoint;
        static String arm;
        static final List<Captured> CAPTURED = Collections.synchronizedList(new ArrayList<>());
        static final List<GuardEvent> GUARD_EVENTS = Collections.synchronizedList(new ArrayList<>());

        static void begin(String endpointCode, String armCode) {
            endpoint = endpointCode;
            arm = armCode;
            CAPTURED.clear();
            GUARD_EVENTS.clear();
        }

        static void end() {
            endpoint = null;
            arm = null;
        }
    }

    record Captured(String msId, String sql, LinkedHashMap<String, Object> values) {
    }

    /** 长字段护栏事件：被包裹前在原始实体上读到的非空计数。 */
    record GuardEvent(String msId, int rows, int answersNonNull, int studentAnswerNonNull, int commentNonNull) {
    }

    /** 目标语句返回实体的「读取即失败」包装：长字段 getter 一经调用即抛断言错。 */
    static final class ReadFailGuard {
        private ReadFailGuard() {
        }

        static Object wrap(Object entity) {
            if (entity instanceof GradingSubmission g) {
                GradingSubmission spy = Mockito.mock(GradingSubmission.class,
                        Mockito.withSettings().spiedInstance(g).defaultAnswer(Mockito.CALLS_REAL_METHODS).stubOnly());
                Mockito.doThrow(new AssertionError(
                                "long-field read blocked: GradingSubmission.getAnswers() called by business path"))
                        .when(spy).getAnswers();
                return spy;
            }
            if (entity instanceof SubjectiveGrade s) {
                SubjectiveGrade spy = Mockito.mock(SubjectiveGrade.class,
                        Mockito.withSettings().spiedInstance(s).defaultAnswer(Mockito.CALLS_REAL_METHODS).stubOnly());
                Mockito.doThrow(new AssertionError(
                                "long-field read blocked: SubjectiveGrade.getStudentAnswer() called by business path"))
                        .when(spy).getStudentAnswer();
                Mockito.doThrow(new AssertionError(
                                "long-field read blocked: SubjectiveGrade.getComment() called by business path"))
                        .when(spy).getComment();
                return spy;
            }
            return entity;
        }
    }

    private static boolean canaryThrowsGradingAnswers() {
        GradingSubmission g = new GradingSubmission();
        g.setAnswers("canary-answers");
        try {
            ((GradingSubmission) ReadFailGuard.wrap(g)).getAnswers();
            return false;
        } catch (AssertionError expected) {
            return true;
        }
    }

    private static boolean canaryThrowsSubjectiveLongFields() {
        SubjectiveGrade s = new SubjectiveGrade();
        s.setStudentAnswer("canary-student-answer");
        s.setComment("canary-comment");
        boolean answerThrows;
        boolean commentThrows;
        try {
            ((SubjectiveGrade) ReadFailGuard.wrap(s)).getStudentAnswer();
            answerThrows = false;
        } catch (AssertionError expected) {
            answerThrows = true;
        }
        try {
            ((SubjectiveGrade) ReadFailGuard.wrap(s)).getComment();
            commentThrows = false;
        } catch (AssertionError expected) {
            commentThrows = true;
        }
        return answerThrows && commentThrows;
    }

    // ==================== 站点拦截器（Executor 层，测试侧） ====================

    /**
     * 三件事：① 记账（按 msId 累计条数/返回行数）；② PROJ 臂施加：对目标语句参数里的
     * LambdaQueryWrapper 在 proceed() 前调用 .select(...)（冻结列；仅当 wrapper 尚未显式 select）；
     * ③ 长字段护栏：目标语句返回实体包裹为「读取即失败」spy，并记录逐语句非空计数。
     */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            CacheKey.class, BoundSql.class})
    })
    static class MeasureSiteInterceptor implements Interceptor {

        static final Map<String, long[]> ACCOUNTING = new LinkedHashMap<>();

        static void reset() {
            ACCOUNTING.clear();
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
            Object parameter = invocation.getArgs()[1];
            String endpoint = RunState.endpoint;
            String arm = RunState.arm;
            String msId = ms.getId();
            boolean targetGrading = endpoint != null && msId.equals(TARGET_GRADING_MS);
            boolean targetSubjective = endpoint != null && msId.equals(TARGET_SUBJECTIVE_MS);

            if (("PROJ".equals(arm)) && (targetGrading || targetSubjective)) {
                applyProjection(endpoint, targetGrading, parameter);
            }
            Object result = invocation.proceed();

            synchronized (ACCOUNTING) {
                long[] acc = ACCOUNTING.computeIfAbsent(msId, k -> new long[2]);
                acc[0]++;
                if (result instanceof java.util.Collection<?> c) {
                    acc[1] += c.size();
                }
            }

            if ((targetGrading || targetSubjective) && result instanceof List<?> list && !list.isEmpty()) {
                List<Object> wrapped = new ArrayList<>(list.size());
                int answersNonNull = 0;
                int studentAnswerNonNull = 0;
                int commentNonNull = 0;
                for (Object o : list) {
                    if (o instanceof GradingSubmission g) {
                        if (g.getAnswers() != null) {
                            answersNonNull++;
                        }
                    } else if (o instanceof SubjectiveGrade s) {
                        if (s.getStudentAnswer() != null) {
                            studentAnswerNonNull++;
                        }
                        if (s.getComment() != null) {
                            commentNonNull++;
                        }
                    }
                    wrapped.add(o == null ? null : ReadFailGuard.wrap(o));
                }
                RunState.GUARD_EVENTS.add(new GuardEvent(msId, list.size(),
                        answersNonNull, studentAnswerNonNull, commentNonNull));
                return wrapped;
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private static void applyProjection(String endpoint, boolean grading, Object parameter) {
            if (!(parameter instanceof Map<?, ?> map)) {
                return;
            }
            Object ew = map.get("ew");
            if (!(ew instanceof LambdaQueryWrapper<?> wrapper) || wrapper.getSqlSelect() != null) {
                return;
            }
            if (grading) {
                switch (endpoint) {
                    case "progress" -> ((LambdaQueryWrapper<GradingSubmission>) wrapper).select(
                            GradingSubmission::getId, GradingSubmission::getGradingStatus);
                    case "summarize" -> ((LambdaQueryWrapper<GradingSubmission>) wrapper).select(
                            GradingSubmission::getId, GradingSubmission::getGradingStatus,
                            GradingSubmission::getObjectiveScore);
                    case "preview" -> ((LambdaQueryWrapper<GradingSubmission>) wrapper).select(
                            GradingSubmission::getStudentId, GradingSubmission::getObjectiveScore,
                            GradingSubmission::getSubjectiveScore, GradingSubmission::getTotalScore,
                            GradingSubmission::getPartialGraded);
                    default -> throw new IllegalStateException("unknown endpoint " + endpoint);
                }
            } else {
                switch (endpoint) {
                    case "progress" -> ((LambdaQueryWrapper<SubjectiveGrade>) wrapper).select(
                            SubjectiveGrade::getSubmissionId, SubjectiveGrade::getScore);
                    case "summarize" -> ((LambdaQueryWrapper<SubjectiveGrade>) wrapper).select(
                            SubjectiveGrade::getSubmissionId, SubjectiveGrade::getQuestionId,
                            SubjectiveGrade::getScore);
                    default -> throw new IllegalStateException("subjective projection for " + endpoint);
                }
            }
        }
    }

    /** 语句原文捕获（测试侧，只记录不改变行为）：拦 StatementHandler.prepare。 */
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    static class MeasureSqlCaptureInterceptor implements Interceptor {

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            if (RunState.endpoint == null) {
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
            RunState.CAPTURED.add(new Captured(ms.getId(), boundSql.getSql(), values));
            return invocation.proceed();
        }
    }

    // ==================== 主流程 ====================

    @Test
    @DisplayName("逐形状逐端点三臂轮转测量并落盘机器可读 JSON")
    void measure() throws Exception {
        String rev = System.getProperty("measure.rev", "unknown");
        String label = System.getProperty("measure.label", "rounds1");
        Path outDir = Paths.get(System.getProperty("measure.out",
                "D:\\code\\examOnline-measure\\grading-score-projection"));
        Files.createDirectories(outDir);

        ObjectNode manifest = om.createObjectNode();
        manifest.put("rev", rev);
        manifest.put("label", label);
        manifest.put("generatedAtIso", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        manifest.put("measureClass", "GradingScoreProjectionMeasureIT");
        manifest.put("timingMechanism", "System.nanoTime around the in-process service call "
                + "(Spring proxy, @DS routing, @Transactional, MyBatis, interceptor wrapping all inside the window; "
                + "identical across arms).");
        manifest.put("projArmMechanism", "test-side Executor.query interceptor calls .select(frozen columns) "
                + "on the LambdaQueryWrapper of each target statement before proceed(); OLD/OLDrep untouched. "
                + "No src/main change during measurement.");
        manifest.put("guardMechanism", "target entities wrapped in Mockito spies whose getAnswers()/"
                + "getStudentAnswer()/getComment() throw AssertionError when called; per-statement non-null counts "
                + "recorded pre-wrap; PROJ arm must show all-null entity long fields while the DB rows under the "
                + "same predicate are non-null (mechanical count > 0); canary proves the wrapper throws.");

        ObjectNode prereg = manifest.putObject("preregistration");
        Path preregPath = findExisting(PREREG_ACTIVE, PREREG_ARCHIVE);
        Path shaPath = findExisting(
                "spec/changes/project-grading-score-scalar-projection/evidence/preregistration.sha256.txt",
                "spec/changes/archive/project-grading-score-scalar-projection/evidence/preregistration.sha256.txt");
        if (preregPath == null || shaPath == null) {
            prereg.put("error", "preregistration file or hash record missing");
            problem("prereg-file-missing");
        } else {
            prereg.put("file", preregPath.toString().replace('\\', '/'));
            String recomputed = sha256Hex(Files.readAllBytes(preregPath));
            String recorded = Files.readString(shaPath, StandardCharsets.UTF_8).trim().split("\\s+")[0];
            prereg.put("recordedSha256", recorded);
            prereg.put("recomputedSha256", recomputed);
            prereg.put("match", recorded.equalsIgnoreCase(recomputed));
            if (!recorded.equalsIgnoreCase(recomputed)) {
                problem("prereg-sha-mismatch");
            }
        }

        ObjectNode canary = manifest.putObject("guardCanary");
        canary.put("gradingAnswersThrows", canaryThrowsGradingAnswers());
        canary.put("subjectiveLongFieldsThrow", canaryThrowsSubjectiveLongFields());
        if (!canary.get("gradingAnswersThrows").asBoolean()
                || !canary.get("subjectiveLongFieldsThrow").asBoolean()) {
            problem("guard-canary-failed");
        }

        ArrayNode shapes = manifest.putArray("shapesDone");
        try {
            for (int n : SIZES) {
                seedShape(n);
                ObjectNode shapeNode = om.createObjectNode();
                shapeNode.put("n", n);
                ObjectNode endpointsNode = shapeNode.putObject("endpoints");
                for (String endpoint : ENDPOINTS) {
                    runEndpoint(endpoint, n, endpointsNode.putObject(endpoint));
                }
                Files.writeString(outDir.resolve("rounds-n" + n + ".json"),
                        om.writerWithDefaultPrettyPrinter().writeValueAsString(shapeNode),
                        StandardCharsets.UTF_8);
                shapes.add(n);
            }
        } finally {
            manifest.set("problems", om.valueToTree(problems));
            Files.writeString(outDir.resolve("capture-manifest.json"),
                    om.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                    StandardCharsets.UTF_8);
            System.out.println("MEASURE manifest written " + outDir.toAbsolutePath());
        }

        if (!problems.isEmpty()) {
            fail("measure problems " + problems.size() + ": " + String.join(" | ", problems));
        }
    }

    private static Path findExisting(String... candidates) {
        for (String c : candidates) {
            Path p = Paths.get(c);
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    // ==================== 造数（PREREG §1 冻结分布） ====================

    private static long examP(int n) {
        return EXAM_P_BASE + n;
    }

    private static long examS(int n) {
        return EXAM_S_BASE + n;
    }

    private static long examV(int n) {
        return EXAM_V_BASE + n;
    }

    private static long sid(int n, int i) {
        return STUDENT_BASE + (long) n * 100_000 + i;
    }

    private static BigDecimal objScore(int i) {
        return BigDecimal.valueOf(400 + (i * 7919L) % 300, 1);
    }

    private static BigDecimal subjScore(int i) {
        return BigDecimal.valueOf((i % 7) * 10L, 1);
    }

    private static BigDecimal gradedScore(int i) {
        return BigDecimal.valueOf((400 + (i * 7919L) % 300) / 2, 1);
    }

    private static String answersJson() {
        return "{\"1001\":\"" + "A".repeat(ANSWERS_CHARS - 11) + "\"}";
    }

    private static String paperJson() {
        return "{\"snapshot\":\"" + "B".repeat(PAPER_JSON_CHARS - 15) + "\"}";
    }

    private static String studentAnswer() {
        return "C".repeat(STUDENT_ANSWER_CHARS);
    }

    private static String suggestedDetail() {
        return "D".repeat(DETAIL_CHARS);
    }

    private static String commentText() {
        return "E".repeat(COMMENT_CHARS);
    }

    private static String snapshotPaperJson() {
        return "{\"paperId\":" + PAPER_ID + ",\"title\":\"gsproj-measure\",\"totalScore\":100.0,"
                + "\"questions\":["
                + "{\"number\":1,\"questionId\":" + QUESTION_1 + ",\"type\":4,\"content\":\"sa1\","
                + "\"correctAnswer\":\"REF\",\"score\":50.0},"
                + "{\"number\":2,\"questionId\":" + QUESTION_2 + ",\"type\":4,\"content\":\"sa2\","
                + "\"correctAnswer\":\"REF\",\"score\":50.0}]}";
    }

    private void seedShape(int n) throws Exception {
        long p = examP(n);
        long s = examS(n);
        long v = examV(n);
        Timestamp start = Timestamp.valueOf(LocalDateTime.now().minusHours(4));
        Timestamp deadline = Timestamp.valueOf(LocalDateTime.now().plusHours(2));
        Timestamp submit = Timestamp.valueOf(LocalDateTime.now().minusHours(2));

        jdbc.update("DELETE FROM subjective_grades WHERE exam_id IN (?,?,?)", p, s, v);
        jdbc.update("DELETE FROM exam_submissions WHERE exam_id IN (?,?,?)", p, s, v);
        jdbc.update("DELETE FROM exam_snapshots WHERE exam_id IN (?,?,?)", p, s, v);
        jdbc.update("DELETE FROM exams WHERE id IN (?,?,?)", p, s, v);
        jdbc.update("DELETE FROM users WHERE id BETWEEN ? AND ?", sid(n, 0), sid(n, n - 1));

        insertExam(p, "gsproj-progress-n" + n, Exam.STATUS_ENDED);
        insertExam(s, "gsproj-summarize-n" + n, Exam.STATUS_ENDED);
        insertExam(v, "gsproj-preview-n" + n, Exam.STATUS_GRADED);
        jdbc.update("INSERT INTO exam_snapshots (exam_id, exam_json, paper_json, version, created_by)"
                        + " VALUES (?,?,?,?,?)", s, "{}", snapshotPaperJson(), 1, OWNER_ID);

        String longAnswers = answersJson();
        String longPaper = paperJson();
        String saText = studentAnswer();
        String detail = suggestedDetail();

        List<Object[]> rows = new ArrayList<>(3 * n);
        for (int i = 0; i < n; i++) {
            BigDecimal obj = objScore(i);
            rows.add(new Object[]{p, sid(n, i), start, deadline, submit, longPaper, longAnswers,
                    ExamSubmission.STATUS_SUBMITTED, obj, null, null,
                    (i % 10 == 9) ? 2 : (i % 10 == 8) ? 0 : 1, 0});
            rows.add(new Object[]{s, sid(n, i), start, deadline, submit, longPaper, longAnswers,
                    ExamSubmission.STATUS_SUBMITTED, obj, null, null, 1, 0});
            BigDecimal subj = subjScore(i);
            rows.add(new Object[]{v, sid(n, i), start, deadline, submit, longPaper, longAnswers,
                    ExamSubmission.STATUS_GRADED, obj, subj, obj.add(subj), 1, (i % 4 == 0) ? 1 : 0});
        }
        // 分块批插（每 1000 行）：rewriteBatchedStatements 下整批会在客户端拼出
        // 行数 × 行宽的巨型语句串（9000 行 ≈ 200MB），分块把峰值降到 ~25MB；
        // 行数据与冻结公式完全不变（apparatus-correction-note.md §3）
        batched("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                + " submit_time, paper_json, answers, status, objective_score, subjective_score,"
                + " total_score, grading_status, partial_graded, version, created_time, updated_time)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", rows);

        // subjective_grades 需要 submission_id：机械回读（同 batch 顺序下的 (exam, student) → id）
        Map<Long, Long> submissionIdByStudent = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, student_id FROM exam_submissions WHERE exam_id = ?", p)) {
            submissionIdByStudent.put(((Number) row.get("student_id")).longValue(),
                    ((Number) row.get("id")).longValue());
        }
        Map<Long, Long> sIds = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, student_id FROM exam_submissions WHERE exam_id = ?", s)) {
            sIds.put(((Number) row.get("student_id")).longValue(), ((Number) row.get("id")).longValue());
        }
        assertEquals(n, submissionIdByStudent.size(), "progress 答卷行数");
        assertEquals(n, sIds.size(), "summarize 答卷行数");

        List<Object[]> gradeRows = new ArrayList<>(4 * n);
        for (long examId : new long[]{p, s}) {
            Map<Long, Long> ids = examId == p ? submissionIdByStudent : sIds;
            for (int i = 0; i < n; i++) {
                for (int q = 0; q < 2; q++) {
                    BigDecimal score = ((i + q) % 4 != 0) ? gradedScore(i) : null;
                    gradeRows.add(new Object[]{ids.get(sid(n, i)), examId, sid(n, i),
                            q == 0 ? QUESTION_1 : QUESTION_2, q + 1, saText,
                            BigDecimal.valueOf(30.0), detail, score,
                            score == null ? null : commentText(),
                            score == null ? null : OWNER_ID,
                            score == null ? null : submit});
                }
            }
        }
        batched("INSERT INTO subjective_grades (submission_id, exam_id, student_id, question_id,"
                + " question_number, student_answer, suggested_score, suggested_detail, score,"
                + " comment, grader_id, graded_time, version, created_time, updated_time)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", gradeRows);

        List<Object[]> userRows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            userRows.add(new Object[]{sid(n, i), "mu" + n + "_" + i, "x", "stud-" + i});
        }
        batched("INSERT INTO users (id, username, password, name, status, must_change_password,"
                + " is_deleted) VALUES (?,?,?,?,0,0,0)", userRows);

        // V1 造数即实：机械查询取证（不靠日志叙述），落 seed-counts-n{X}.json
        ObjectNode v1 = om.createObjectNode();
        v1.put("n", n);
        v1.put("progressRows", count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?", p));
        v1.put("summarizeRows", count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?", s));
        v1.put("previewRows", count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?", v));
        v1.put("progressSubjectiveRows", count("SELECT COUNT(*) FROM subjective_grades WHERE exam_id = ?", p));
        v1.put("summarizeSubjectiveRows", count("SELECT COUNT(*) FROM subjective_grades WHERE exam_id = ?", s));
        v1.put("usersRows", count("SELECT COUNT(*) FROM users WHERE id BETWEEN ? AND ?", sid(n, 0), sid(n, n - 1)));
        v1.put("answersNonNull", count("SELECT COUNT(*) FROM exam_submissions"
                + " WHERE exam_id IN (?,?,?) AND answers IS NOT NULL", p, s, v));
        v1.put("answersMinLen", minLen("answers", p, s, v));
        v1.put("answersMaxLen", maxLen("answers", p, s, v));
        v1.put("paperJsonNonNull", count("SELECT COUNT(*) FROM exam_submissions"
                + " WHERE exam_id IN (?,?,?) AND paper_json IS NOT NULL", p, s, v));
        v1.put("paperJsonMinLen", minLen("paper_json", p, s, v));
        v1.put("paperJsonMaxLen", maxLen("paper_json", p, s, v));
        v1.put("studentAnswerNonNull", count("SELECT COUNT(*) FROM subjective_grades"
                + " WHERE exam_id IN (?,?) AND student_answer IS NOT NULL", p, s));
        v1.put("studentAnswerMinLen", minLenSubj("student_answer", p, s));
        v1.put("studentAnswerMaxLen", maxLenSubj("student_answer", p, s));
        v1.put("commentNonNull", count("SELECT COUNT(*) FROM subjective_grades"
                + " WHERE exam_id IN (?,?) AND comment IS NOT NULL", p, s));
        v1.put("gradedRows", count("SELECT COUNT(*) FROM subjective_grades"
                + " WHERE exam_id IN (?,?) AND score IS NOT NULL", p, s));
        v1.put("previewTotalNonNull", count("SELECT COUNT(*) FROM exam_submissions"
                + " WHERE exam_id = ? AND total_score IS NOT NULL", v));

        boolean ok = v1.path("progressRows").asLong() == n
                && v1.path("summarizeRows").asLong() == n
                && v1.path("previewRows").asLong() == n
                && v1.path("progressSubjectiveRows").asLong() == 2L * n
                && v1.path("summarizeSubjectiveRows").asLong() == 2L * n
                && v1.path("usersRows").asLong() == n
                && v1.path("answersNonNull").asLong() == 3L * n
                && v1.path("answersMinLen").asLong() == ANSWERS_CHARS
                && v1.path("answersMaxLen").asLong() == ANSWERS_CHARS
                && v1.path("paperJsonNonNull").asLong() == 3L * n
                && v1.path("paperJsonMinLen").asLong() == PAPER_JSON_CHARS
                && v1.path("paperJsonMaxLen").asLong() == PAPER_JSON_CHARS
                && v1.path("studentAnswerNonNull").asLong() == 4L * n
                && v1.path("studentAnswerMinLen").asLong() == STUDENT_ANSWER_CHARS
                && v1.path("studentAnswerMaxLen").asLong() == STUDENT_ANSWER_CHARS
                && v1.path("commentNonNull").asLong() == v1.path("gradedRows").asLong()
                && v1.path("previewTotalNonNull").asLong() == n;
        v1.put("ok", ok);
        if (!ok) {
            problem("v1-failed-n" + n);
        }
        Path outDir = Paths.get(System.getProperty("measure.out",
                "D:\\code\\examOnline-measure\\grading-score-projection"));
        Files.writeString(outDir.resolve("seed-counts-n" + n + ".json"),
                om.writerWithDefaultPrettyPrinter().writeValueAsString(v1), StandardCharsets.UTF_8);
    }

    /** 分块批插（每 CHUNK_ROWS 行一次 batchUpdate），控制客户端语句串峰值内存。 */
    private void batched(String sql, List<Object[]> rows) {
        final int chunk = 1000;
        for (int from = 0; from < rows.size(); from += chunk) {
            jdbc.batchUpdate(sql, rows.subList(from, Math.min(from + chunk, rows.size())));
        }
    }

    private void insertExam(long id, String title, int status) {        jdbc.update("INSERT INTO exams (id, title, paper_id, class_id, start_time, end_time,"
                        + " duration_minutes, status, published, created_by, version, is_deleted,"
                        + " created_time, updated_time)"
                        + " VALUES (?,?,?,?,CURRENT_TIMESTAMP,"
                        + "DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 2 HOUR),60,?,1,?,0,0,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, title, PAPER_ID, null, status, OWNER_ID);
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? -1 : v;
    }

    private long minLen(String col, Object... examIds) {
        Number v = jdbc.queryForObject("SELECT MIN(LENGTH(" + col + ")) FROM exam_submissions"
                + " WHERE exam_id IN (?,?,?) AND " + col + " IS NOT NULL", Number.class, examIds);
        return v == null ? -1 : v.longValue();
    }

    private long maxLen(String col, Object... examIds) {
        Number v = jdbc.queryForObject("SELECT MAX(LENGTH(" + col + ")) FROM exam_submissions"
                + " WHERE exam_id IN (?,?,?) AND " + col + " IS NOT NULL", Number.class, examIds);
        return v == null ? -1 : v.longValue();
    }

    private long minLenSubj(String col, Object... examIds) {
        Number v = jdbc.queryForObject("SELECT MIN(LENGTH(" + col + ")) FROM subjective_grades"
                + " WHERE exam_id IN (?,?) AND " + col + " IS NOT NULL", Number.class, examIds);
        return v == null ? -1 : v.longValue();
    }

    private long maxLenSubj(String col, Object... examIds) {
        Number v = jdbc.queryForObject("SELECT MAX(LENGTH(" + col + ")) FROM subjective_grades"
                + " WHERE exam_id IN (?,?) AND " + col + " IS NOT NULL", Number.class, examIds);
        return v == null ? -1 : v.longValue();
    }

    // ==================== 端点编排（PREREG §2.1） ====================

    private void runEndpoint(String endpoint, int n, ObjectNode node) throws Exception {
        node.set("units", om.valueToTree(endpointUnitList(endpoint)));
        ObjectNode armsNode = node.putObject("arms");

        // S1 oracle（只读端点，计时段外执行 OLD/PROJ 各一次并逐字段对拍）
        if (!"summarize".equals(endpoint)) {
            ArmResult oldOracle = runArm(endpoint, n, "OLD");
            ArmResult projOracle = runArm(endpoint, n, "PROJ");
            ObjectNode oracle = node.putObject("s1Oracle");
            oracle.set("OLD", oldOracle.json().path("output").deepCopy());
            oracle.set("PROJ", projOracle.json().path("output").deepCopy());
            boolean equal = Objects.equals(oldOracle.json().path("output"), projOracle.json().path("output"));
            oracle.put("equal", equal);
            if (!equal) {
                oracle.put("diff", String.valueOf(oldOracle.json().path("output")) + " vs "
                        + projOracle.json().path("output"));
                problem(endpoint + "-n" + n + " s1-oracle-mismatch");
            }
            armsNode.set("ORACLE_OLD", oldOracle.json());
            armsNode.set("ORACLE_PROJ", projOracle.json());
        }

        // 每臂 2 次预热（不计时，summarize 复位）+ 5 计时轮；第 r 轮臂序左转 (r−1) mod 3
        for (String arm : ARM_CYCLE) {
            for (int w = 0; w < WARMUPS; w++) {
                resetIfSummarize(endpoint, n);
                runArm(endpoint, n, arm);
            }
        }
        ObjectNode roundsNode = node.putObject("rounds");
        for (int r = 1; r <= TIMED_ROUNDS; r++) {
            List<String> order = new ArrayList<>(ARM_CYCLE);
            java.util.Collections.rotate(order, -(r - 1));
            for (String arm : order) {
                int attempt = 0;
                ObjectNode roundNode = null;
                while (attempt < 2) {
                    attempt++;
                    resetIfSummarize(endpoint, n);
                    roundNode = runTimedRound(endpoint, n, arm, r, attempt);
                    if (roundNode.path("exit").asInt(1) == 0) {
                        break;
                    }
                    // 失败轮原样保留（不重跑取胜）；同位补跑一次记 attempt=2
                }
                roundsNode.set(arm + "#r" + r + "#a" + attempt, roundNode);
            }
        }

        // 汇总逐臂成功计时轮（PREREG §2.1）：M3 检查由 analyze 脚本按 ≥5 判
        node.put("armCycle", String.join(",", order0()));
    }

    private static List<String> order0() {
        return new ArrayList<>(ARM_CYCLE);
    }

    private static List<String> endpointUnitList(String endpoint) {
        return switch (endpoint) {
            case "progress" -> List.of("M1", "M2");
            case "summarize" -> List.of("M3", "M4");
            case "preview" -> List.of("M5");
            default -> throw new IllegalStateException(endpoint);
        };
    }

    private void resetIfSummarize(String endpoint, int n) {
        if ("summarize".equals(endpoint)) {
            // PREREG §2.1 冻结复位算子（窗口外）
            jdbc.update("UPDATE exam_submissions SET status = 2, subjective_score = NULL,"
                    + " total_score = NULL, partial_graded = 0, grading_status = 1, version = 0"
                    + " WHERE exam_id = ?", examS(n));
            jdbc.update("UPDATE exams SET status = 2, version = 0 WHERE id = ?", examS(n));
        }
    }

    /** 一次计时执行：RunState 包住调用，窗口 = Service 调用本体（拦截器/包装在内，三臂同摊）。 */
    private ObjectNode runTimedRound(String endpoint, int n, String arm, int round, int attempt) {
        RunState.begin(endpoint, arm);
        MeasureSiteInterceptor.reset();
        long t0 = System.nanoTime();
        Object result = null;
        Exception error = null;
        try {
            result = invoke(endpoint, n);
        } catch (Exception e) {
            error = e;
        }
        long t1 = System.nanoTime();
        long elapsed = t1 - t0;
        List<Captured> all = new ArrayList<>(RunState.CAPTURED);
        List<GuardEvent> guardEvents = new ArrayList<>(RunState.GUARD_EVENTS);
        Map<String, long[]> accounting = new LinkedHashMap<>(MeasureSiteInterceptor.ACCOUNTING);
        RunState.end();
        // inline mockmaker 保留每条 spy 调用记录（含栈快照 Location）：实测 559k InterceptedInvocation
        // 累积至 GB 级；每次臂执行后释放拦截状态（窗口外，护栏语义不变）。
        Mockito.framework().clearInlineMocks();

        int exit;
        if (error != null) {
            exit = 2;
        } else {
            exit = sanityOk(endpoint, n, result) ? 0 : 1;
        }

        ObjectNode roundNode = om.createObjectNode();
        roundNode.put("round", round);
        roundNode.put("arm", arm);
        roundNode.put("attempt", attempt);
        roundNode.put("ns", elapsed);
        roundNode.put("exit", exit);
        roundNode.put("iso", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        if (error != null) {
            roundNode.put("error", String.valueOf(error));
        } else {
            roundNode.set("output", buildOutput(endpoint, result));
        }

        ObjectNode accNode = roundNode.putObject("accounting");
        for (Map.Entry<String, long[]> e : accounting.entrySet()) {
            ObjectNode an = accNode.putObject(e.getKey());
            an.put("queries", e.getValue()[0]);
            an.put("rows", e.getValue()[1]);
        }
        roundNode.put("totalStatements", all.size());

        // casSummarize 逐次参数序列（S1 写行为）
        ArrayNode casNode = roundNode.putArray("casSummarize");
        for (Captured c : all) {
            if (c.msId().equals(CAS_MS)) {
                ObjectNode cn = casNode.addObject();
                cn.put("id", str(c.values().get("id")));
                cn.put("subjectiveScore", str(c.values().get("subjectiveScore")));
                cn.put("totalScore", str(c.values().get("totalScore")));
                cn.put("partialGraded", str(c.values().get("partialGraded")));
            }
        }

        // 目标语句捕获（M1 有效性 + S2 输入）：目标 msId 的逐条原文 + literalSql
        ArrayNode targets = roundNode.putObject("targets").putArray("statements");
        Map<String, Integer> targetCounts = new LinkedHashMap<>();
        for (Captured c : all) {
            boolean gradingTarget = c.msId().equals(TARGET_GRADING_MS);
            boolean subjectiveTarget = c.msId().equals(TARGET_SUBJECTIVE_MS);
            if (!gradingTarget && !subjectiveTarget) {
                continue;
            }
            targetCounts.merge(unitOf(endpoint, gradingTarget), 1, Integer::sum);
            ObjectNode tn = targets.addObject();
            tn.put("unit", unitOf(endpoint, gradingTarget));
            tn.put("msId", c.msId());
            tn.put("sql", c.sql());
            tn.put("literalSql", toLiteralSql(c));
            ObjectNode vn = tn.putObject("values");
            for (Map.Entry<String, Object> e : c.values().entrySet()) {
                vn.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
        }
        roundNode.put("targetCountsOk", targetCountsOk(endpoint, targetCounts));
        if (!targetCountsOk(endpoint, targetCounts)) {
            problem(endpoint + "-round" + round + "-arm" + arm + " targetCounts " + targetCounts);
        }

        // 护栏汇总
        ObjectNode guardNode = roundNode.putObject("guard");
        int answersTotal = 0;
        int saTotal = 0;
        int commentTotal = 0;
        int rowsTotal = 0;
        for (GuardEvent g : guardEvents) {
            answersTotal += g.answersNonNull();
            saTotal += g.studentAnswerNonNull();
            commentTotal += g.commentNonNull();
            rowsTotal += g.rows();
        }
        guardNode.put("answersNonNullTotal", answersTotal);
        guardNode.put("studentAnswerNonNullTotal", saTotal);
        guardNode.put("commentNonNullTotal", commentTotal);
        guardNode.put("rowsTotal", rowsTotal);
        guardNode.put("dbLongRows", dbLongRows(endpoint, n));
        return roundNode;
    }

    private static boolean targetCountsOk(String endpoint, Map<String, Integer> counts) {
        return switch (endpoint) {
            case "progress" -> counts.getOrDefault("M1", 0) == 1 && counts.getOrDefault("M2", 0) == 1;
            case "summarize" -> counts.getOrDefault("M3", 0) == 1 && counts.getOrDefault("M4", 0) == 1;
            case "preview" -> counts.getOrDefault("M5", 0) == 1;
            default -> false;
        };
    }

    private static String unitOf(String endpoint, boolean grading) {
        if (grading) {
            return UNIT_OF_GRADING.get(endpoint);
        }
        return UNIT_OF_SUBJECTIVE.get(endpoint);
    }

    /** 库内同谓词行长字段非 null 机械计数（PREREG §2.2；PROJ 臂须 > 0）。 */
    private long dbLongRows(String endpoint, int n) {
        return switch (endpoint) {
            case "progress" -> count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?"
                    + " AND answers IS NOT NULL", examP(n));
            case "summarize" -> count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?"
                    + " AND answers IS NOT NULL", examS(n));
            case "preview" -> count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?"
                    + " AND answers IS NOT NULL", examV(n));
            default -> -1;
        };
    }

    private boolean sanityOk(String endpoint, int n, Object result) {
        return switch (endpoint) {
            case "progress" -> {
                GradingProgressResponse r = (GradingProgressResponse) result;
                yield r != null && r.getSubmittedCount() == n;
            }
            case "summarize" -> {
                ScoreService.SummarizeStats r = (ScoreService.SummarizeStats) result;
                yield r != null && r.summarized() == n && r.skipped() == 0 && r.examGraded();
            }
            case "preview" -> {
                ScorePreviewResponse r = (ScorePreviewResponse) result;
                yield r != null && r.getSummarizedCount() == n && r.getItems() != null
                        && r.getItems().size() == n;
            }
            default -> false;
        };
    }

    private Object invoke(String endpoint, int n) {
        return switch (endpoint) {
            case "progress" -> {
                SecurityUtil.set(teacherLogin());
                try {
                    yield gradingQueryService.progress(examP(n));
                } finally {
                    SecurityUtil.clear();
                }
            }
            case "summarize" -> {
                SecurityUtil.set(teacherLogin());
                try {
                    yield scoreService.summarize(examS(n));
                } finally {
                    SecurityUtil.clear();
                }
            }
            case "preview" -> {
                SecurityUtil.set(teacherLogin());
                try {
                    yield scoreQueryService.publishPreview(examV(n));
                } finally {
                    SecurityUtil.clear();
                }
            }
            default -> throw new IllegalStateException(endpoint);
        };
    }

    /** 单臂执行（oracle / 预热用；不计入轮次）：返回捕获 JSON 概要与输出快照。 */
    private ArmResult runArm(String endpoint, int n, String arm) {
        RunState.begin(endpoint, arm);
        MeasureSiteInterceptor.reset();
        Object result = null;
        String error = null;
        try {
            result = invoke(endpoint, n);
        } catch (Throwable t) {
            error = String.valueOf(t);
            problem(endpoint + "-n" + n + " arm " + arm + " failed: " + error);
        }
        List<Captured> all = new ArrayList<>(RunState.CAPTURED);
        List<GuardEvent> guardEvents = new ArrayList<>(RunState.GUARD_EVENTS);
        RunState.end();
        Mockito.framework().clearInlineMocks();

        ObjectNode json = om.createObjectNode();
        json.put("arm", arm);
        json.put("status", error == null ? "OK" : "ERROR");
        if (error != null) {
            json.put("error", error);
        }
        ArrayNode targets = json.putObject("targets").putArray("statements");
        for (Captured c : all) {
            boolean gradingTarget = c.msId().equals(TARGET_GRADING_MS);
            boolean subjectiveTarget = c.msId().equals(TARGET_SUBJECTIVE_MS);
            if (!gradingTarget && !subjectiveTarget) {
                continue;
            }
            ObjectNode tn = targets.addObject();
            tn.put("unit", unitOf(endpoint, gradingTarget));
            tn.put("msId", c.msId());
            tn.put("sql", c.sql());
            tn.put("literalSql", toLiteralSql(c));
        }
        ObjectNode guardNode = json.putObject("guard");
        int answersTotal = 0;
        int saTotal = 0;
        for (GuardEvent g : guardEvents) {
            answersTotal += g.answersNonNull();
            saTotal += g.studentAnswerNonNull();
        }
        guardNode.put("answersNonNullTotal", answersTotal);
        guardNode.put("studentAnswerNonNullTotal", saTotal);
        guardNode.put("dbLongRows", dbLongRows(endpoint, n));

        ObjectNode output = null;
        if (error == null) {
            output = buildOutput(endpoint, result);
        }
        if (output != null) {
            json.set("output", output);
        }
        return new ArmResult(json, output);
    }

    private record ArmResult(ObjectNode json, ObjectNode output) {
    }

    private ObjectNode buildOutput(String endpoint, Object result) {
        ObjectNode out = om.createObjectNode();
        switch (endpoint) {
            case "progress" -> {
                GradingProgressResponse r = (GradingProgressResponse) result;
                out.put("submittedCount", r.getSubmittedCount());
                out.put("gradedCount", r.getGradedCount());
                out.put("failedCount", r.getFailedCount());
                out.put("pendingCount", r.getPendingCount());
                out.put("subjectiveTotal", r.getSubjectiveTotal());
                out.put("subjectiveGraded", r.getSubjectiveGraded());
                out.put("partialGradedCount", r.getPartialGradedCount());
            }
            case "summarize" -> {
                ScoreService.SummarizeStats r = (ScoreService.SummarizeStats) result;
                out.put("summarized", r.summarized());
                out.put("skipped", r.skipped());
                out.put("examGraded", r.examGraded());
            }
            case "preview" -> {
                return om.valueToTree(result);
            }
            default -> throw new IllegalStateException(endpoint);
        }
        return out;
    }

    // ==================== 工具 ====================

    /** 「? → 字面量」机械替换（替换次数必须与参数数一致）。 */
    private static String toLiteralSql(Captured c) {
        String sql = c.sql();
        int q = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                q++;
            }
        }
        assertEquals(c.values().size(), q,
                "parameter/? count mismatch: " + c.values().size() + " vs " + q);
        StringBuilder sb = new StringBuilder(sql.length() + 64);
        int idx = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '?') {
                sb.append(formatLiteral((Object) c.values().values().toArray()[idx++]));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String formatLiteral(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        if (v instanceof Timestamp ts) {
            return "'" + ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "'";
        }
        String s = v.toString().replace("'", "''");
        return "'" + s + "'";
    }

    /** 机械解析捕获 SQL 的 SELECT 列表（SELECT 与 FROM 之间）。 */
    static String selectListOf(String sql) {
        int from = sql.indexOf(" FROM ");
        if (!sql.startsWith("SELECT ") || from < 0) {
            return "";
        }
        return sql.substring("SELECT ".length(), from);
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        return String.valueOf(v);
    }

    private static LoginUser teacherLogin() {
        LoginUser user = new LoginUser();
        user.setId(OWNER_ID);
        user.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.TEACHER));
        return user;
    }

    private void problem(String p) {
        System.out.println("MEASURE PROBLEM " + p);
        problems.add(p);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }
}
