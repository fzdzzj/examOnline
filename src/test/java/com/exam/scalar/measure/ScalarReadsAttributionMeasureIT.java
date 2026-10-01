package com.exam.scalar.measure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.exam.entity.Exam;
import com.exam.exam.service.AbsenceService;
import com.exam.exam.service.MakeupScoreService;
import com.exam.grading.entity.GradingSubmission;
import com.exam.score.dto.MyScoreResponse;
import com.exam.score.dto.ReviewHandleRequest;
import com.exam.score.service.ScoreReviewService;
import com.exam.score.service.ScoreService;
import com.exam.submission.entity.ExamSubmission;
import com.exam.taking.dto.ExamListItem;
import com.exam.taking.service.ExamTakingService;
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
 * 标量只读站点的列投影归因捕获工具（project-scalar-only-submission-reads 阶段 1 证据工具）。
 *
 * <p><b>为什么类名以 IT 结尾</b>：Surefire 默认只收 {@code *Test} 等四类命名，本类不进
 * {@code mvnw clean test} 全量门禁；只有显式 {@code -Dtest=ScalarReadsAttributionMeasureIT} 才运行。
 * 它只做捕获与逐站点两臂对拍、不构成任何业务断言基线（内部断言只用于证明夹具与捕获口径自洽）。
 *
 * <p><b>一次运行做什么</b>：对五个标量只读站点（{@code PREREGISTRATION.md} §0 冻结清单）逐站点逐形状
 * （n ∈ {200,1000,3000}）执行 OLD 臂（现状全列取数）与 PROJ 臂（在冻结谓词与排序下把 SELECT 列表
 * 限定为冻结列）。PROJ 臂的施加方式是测试侧 {@code Executor.query} 拦截器对参数中的
 * {@code LambdaQueryWrapper} 调用 {@code .select(...)}——与阶段 3 生产实现同源，不改一行 {@code src/main}。
 * 每臂记录：目标语句原文与绑定参数（{@code StatementHandler.prepare} 捕获）、全部语句的条数/行数账目
 * （{@code Executor.query} 记账）、长字段护栏（返回实体被「读取即失败」包装 + 逐臂非空计数 + 库内同谓词
 * 非空行数）、两臂输出/生效快照，机械落盘为 JSON 供 {@code analyze-scalar-reads.cjs} 按冻结算子裁决。
 *
 * <p><b>运行时护栏</b>：目标语句返回的实体一律被 Mockito spy 包装——{@code getAnswers()}/
 * {@code getPaperJson()} 一经调用即抛 {@code AssertionError}；任何路径若真用到长字段，该臂即记录为
 * ERROR 并使本次运行变红。PROJ 臂另断言全部返回实体长字段为 null，且库内同谓词行该列非 null 的计数 &gt; 0。
 *
 * <p><b>不触碰生产代码</b>：不改 {@code src/main}、SQL、schema、Mapper、JVM/线程池配置；拦截器只在
 * 本测试的 {@code @TestConfiguration} 内注册，仅本进程生效。<b>H2 只是承载捕获与语义对拍的传输层</b>：
 * 字节与时间类归因证据一律来自阶段 2 的一次性 MySQL 容器，本工具的 H2 数据与计时都不是证据。
 *
 * <p><b>运行</b>（仓库根）：
 * <pre>
 * mvnw.cmd -q test -Dtest=ScalarReadsAttributionMeasureIT -DfailIfNoTests=false ^
 *     -Dmeasure.rev=&lt;sha7&gt; -Dmeasure.label=cap1 -Dmeasure.out=D:\code\examOnline-measure\project-scalar-reads
 * </pre>
 * 机器可读结果写到 {@code ${measure.out}/scalar-reads-capture-${measure.label}.json}；
 * 运行前机械核对 {@code evidence/PREREGISTRATION.md} 的 sha256 与预登记记录一致，不一致即判测量无效。
 */
@SpringBootTest
@ActiveProfiles("test")
// 冻结期间的调度隔离：application-test.yml 只把 taking.sweep 拉到 1h，考试状态机（exam.schedule）
// 仍是 10s tick——其 autoAdvance 的「进行中→已结束」分支会调用 absenceService.markAbsence，
// 而 markAbsence 的扫描语句正是站点 1/2 的目标语句（ExamSubmissionMapper.selectList），
// 一旦在臂运行窗口内触发就会污染条数/行数记账。本 IT 把状态机 tick 同样拉到 1h，
// 使测量窗口内不存在任何定时写入或目标语句级联流量。
@TestPropertySource(properties = {
        "exam.schedule.initial-delay-ms=3600000",
        "exam.schedule.fixed-delay-ms=3600000"
})
@DisplayName("标量只读站点列投影归因捕获（仅显式 -Dtest 运行；不进 Surefire 全量门禁）")
class ScalarReadsAttributionMeasureIT {

    // ==================== 冻结口径（与 PREREGISTRATION.md §0/§1 逐字对应） ====================

    private static final int[] SIZES = {200, 1000, 3000};

    /** 站点 1 区块：50 场已发布考试（970_100_000 + k）。 */
    private static final long S1_EXAM_BASE = 970_100_000L;
    private static final int S1_EXAM_COUNT = 50;
    /** 站点 1 的真实登录学生（本人 45 行答卷）。 */
    private static final long S1_STUDENT = 973_100_000L;
    /** 站点 1 填充行学生段位：974_000_000 + j（无 users 行）。 */
    private static final long S1_FILLER_BASE = 974_000_000L;
    /** 共享区块：主考 E(n) = 970_200_000 + n、补考 F(n) = 970_300_000 + n、班级 C(n) = 972_200_000 + n。 */
    private static final long SHARED_E_BASE = 970_200_000L;
    private static final long SHARED_F_BASE = 970_300_000L;
    private static final long SHARED_CLASS_BASE = 972_200_000L;
    /** 共享区块学生名单段位：973_200_000 + i（i=0..n+4 共 n+5 人）。 */
    private static final long SHARED_STUDENT_BASE = 973_200_000L;
    /** 考试归属教师（站点 4 以 ADMIN 身份处理，归属校验放行；仍写真实 created_by）。 */
    private static final long OWNER_ID = 973_999_999L;

    /** 长字段长度（全 ASCII ⇒ 字符数=字节数；与阶段 2 容器的 CONCAT('{"1001":"',REPEAT('A',2037),'"}') 同构）。 */
    private static final int ANSWERS_CHARS = 2048;
    private static final int PAPER_JSON_CHARS = 20480;

    /** 站点 1 的 T 类字段：两臂比较允许 |Δ| ≤ 1 秒（调用时钟相关）。 */
    private static final String T_FIELD_S1 = "remainingSeconds";

    /** 五站点目标语句 ms id（capture 运行时另有断言核对，见 targetMsId）。 */
    private static final Map<String, String> TARGET_MS = Map.of(
            "s1", "com.exam.submission.mapper.ExamSubmissionMapper.selectList",
            "s2", "com.exam.submission.mapper.ExamSubmissionMapper.selectList",
            "s3", "com.exam.grading.mapper.GradingSubmissionMapper.selectList",
            "s4", "com.exam.grading.mapper.GradingSubmissionMapper.selectList",
            "s5", "com.exam.grading.mapper.GradingSubmissionMapper.selectList");

    /** 冻结的 PROJ 列（按冻结顺序；比较时去除空白后逐字相等）。 */
    private static final Map<String, String> FROZEN_PROJ_COLUMNS = Map.of(
            "s1", "exam_id,status,deadline_time",
            "s2", "student_id",
            "s3", "status,objective_score,subjective_score,total_score,partial_graded",
            "s4", "id",
            "s5", "total_score,submit_time");

    /** 各站点目标语句预期条数 / 返回行数（PREREGISTRATION §2.1 S4）。 */
    private static final Map<String, Integer> EXPECTED_QUERIES = Map.of(
            "s1", 1, "s2", 1, "s3", 1, "s4", 1, "s5", 2);

    private static final String PREREG_REL = "spec/changes/project-scalar-only-submission-reads/evidence/PREREGISTRATION.md";
    private static final String PREREG_SHA_REL = "spec/changes/project-scalar-only-submission-reads/evidence/preregistration.sha256.txt";

    private final List<String> problems = new ArrayList<>();

    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ExamTakingService examTakingService;
    @Autowired
    private AbsenceService absenceService;
    @Autowired
    private ScoreService scoreService;
    @Autowired
    private ScoreReviewService scoreReviewService;
    @Autowired
    private MakeupScoreService makeupScoreService;

    // ==================== 测试侧拦截器（仅本测试上下文生效） ====================

    @TestConfiguration
    static class ScalarMeasureConfig {
        @Bean
        Interceptor scalarSiteInterceptor() {
            return new ScalarSiteInterceptor();
        }

        @Bean
        Interceptor scalarSqlCaptureInterceptor() {
            return new ScalarSqlCaptureInterceptor();
        }
    }

    /** 臂运行状态：单线程串行使用，静态持有当前站点/臂与逐语句证据。 */
    static final class RunState {
        static String site;
        static String arm;
        static final List<Captured> CAPTURED = Collections.synchronizedList(new ArrayList<>());
        static final List<GuardEvent> GUARD_EVENTS = Collections.synchronizedList(new ArrayList<>());

        static void begin(String siteCode, String armCode) {
            site = siteCode;
            arm = armCode;
            CAPTURED.clear();
            GUARD_EVENTS.clear();
        }

        static void end() {
            site = null;
            arm = null;
        }
    }

    record Captured(String msId, String sql, LinkedHashMap<String, Object> values) {
    }

    /** 长字段护栏事件：被包裹前在原始实体上读到的非空计数。 */
    record GuardEvent(String msId, int rows, int answersNonNull, int paperJsonNonNull) {
    }

    /** 目标语句返回实体的「读取即失败」包装：长字段 getter 一经调用即抛断言错。 */
    static final class ReadFailGuard {
        private ReadFailGuard() {
        }

        static Object wrap(Object entity) {
            if (entity instanceof ExamSubmission s) {
                ExamSubmission spy = Mockito.spy(s);
                Mockito.doThrow(new AssertionError(
                                "long-field read blocked: ExamSubmission.getAnswers() called by business path"))
                        .when(spy).getAnswers();
                Mockito.doThrow(new AssertionError(
                                "long-field read blocked: ExamSubmission.getPaperJson() called by business path"))
                        .when(spy).getPaperJson();
                return spy;
            }
            if (entity instanceof GradingSubmission g) {
                GradingSubmission spy = Mockito.spy(g);
                Mockito.doThrow(new AssertionError(
                                "long-field read blocked: GradingSubmission.getAnswers() called by business path"))
                        .when(spy).getAnswers();
                return spy;
            }
            return entity;
        }
    }

    /** canary：wrap 后的 ExamSubmission 调 getAnswers() 必须抛（护栏「读取即失败」语义自证）。 */
    private static boolean canaryThrowsExamAnswers() {
        ExamSubmission s = new ExamSubmission();
        s.setAnswers("canary-answers");
        try {
            ((ExamSubmission) ReadFailGuard.wrap(s)).getAnswers();
            return false;
        } catch (AssertionError expected) {
            return true;
        }
    }

    /** canary：wrap 后的 GradingSubmission 调 getAnswers() 必须抛。 */
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

    /**
     * 站点拦截器（Executor 层，测试侧）：三件事——
     * ① 记账：按 ms id 累计条数/返回行数（行数取包裹前的结果规模，含 selectOne 委托到 selectList 的行）；
     * ② PROJ 臂施加：对目标语句参数里的 {@code LambdaQueryWrapper} 在 {@code proceed()} 前调用
     *    {@code .select(...)}（冻结列；仅当 wrapper 尚未显式 select，幂等防重复）；OLD 臂不改任何 wrapper；
     * ③ 长字段护栏：目标语句返回的实体包裹为「读取即失败」spy，并记录逐语句非空计数。
     */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            CacheKey.class, BoundSql.class})
    })
    static class ScalarSiteInterceptor implements Interceptor {

        static final Map<String, long[]> ACCOUNTING = new LinkedHashMap<>();

        static void reset() {
            ACCOUNTING.clear();
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
            Object parameter = invocation.getArgs()[1];
            String site = RunState.site;
            String arm = RunState.arm;
            boolean target = site != null && ms.getId().equals(TARGET_MS.get(site));

            if (target && "PROJ".equals(arm)) {
                applyProjection(site, parameter);
            }
            Object result = invocation.proceed();

            synchronized (ACCOUNTING) {
                long[] acc = ACCOUNTING.computeIfAbsent(ms.getId(), k -> new long[2]);
                acc[0]++;
                if (result instanceof java.util.Collection<?> c) {
                    acc[1] += c.size();
                }
            }

            if (target && result instanceof List<?> list && !list.isEmpty()) {
                List<Object> wrapped = new ArrayList<>(list.size());
                int answersNonNull = 0;
                int paperNonNull = 0;
                for (Object o : list) {
                    if (o instanceof ExamSubmission s) {
                        if (s.getAnswers() != null) {
                            answersNonNull++;
                        }
                        if (s.getPaperJson() != null) {
                            paperNonNull++;
                        }
                    } else if (o instanceof GradingSubmission g) {
                        if (g.getAnswers() != null) {
                            answersNonNull++;
                        }
                    }
                    wrapped.add(o == null ? null : ReadFailGuard.wrap(o));
                }
                RunState.GUARD_EVENTS.add(new GuardEvent(ms.getId(), list.size(), answersNonNull, paperNonNull));
                return wrapped;
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private static void applyProjection(String site, Object parameter) {
            if (!(parameter instanceof Map<?, ?> map)) {
                return;
            }
            Object ew = map.get("ew");
            if (!(ew instanceof LambdaQueryWrapper<?> wrapper) || wrapper.getSqlSelect() != null) {
                return;
            }
            switch (site) {
                case "s1" -> ((LambdaQueryWrapper<ExamSubmission>) wrapper).select(
                        ExamSubmission::getExamId, ExamSubmission::getStatus, ExamSubmission::getDeadlineTime);
                case "s2" -> ((LambdaQueryWrapper<ExamSubmission>) wrapper).select(
                        ExamSubmission::getStudentId);
                case "s3" -> ((LambdaQueryWrapper<GradingSubmission>) wrapper).select(
                        GradingSubmission::getStatus, GradingSubmission::getObjectiveScore,
                        GradingSubmission::getSubjectiveScore, GradingSubmission::getTotalScore,
                        GradingSubmission::getPartialGraded);
                case "s4" -> ((LambdaQueryWrapper<GradingSubmission>) wrapper).select(
                        GradingSubmission::getId);
                case "s5" -> ((LambdaQueryWrapper<GradingSubmission>) wrapper).select(
                        GradingSubmission::getTotalScore, GradingSubmission::getSubmitTime);
                default -> throw new IllegalStateException("unknown site " + site);
            }
        }
    }

    /**
     * 语句原文捕获（测试侧，只记录不改变行为）：拦 {@code StatementHandler.prepare}，
     * 读取最终 BoundSql 与绑定参数（与 DefaultParameterHandler 同一判定顺序）。
     */
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    static class ScalarSqlCaptureInterceptor implements Interceptor {

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            if (RunState.site == null) {
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
    @DisplayName("逐站点逐形状两臂对拍并落盘机器可读 JSON")
    void capture() throws Exception {
        String rev = System.getProperty("measure.rev", "unknown");
        String label = System.getProperty("measure.label", "cap1");
        Path outDir = Paths.get(System.getProperty("measure.out", "D:\\code\\examOnline-measure\\project-scalar-reads"));
        Files.createDirectories(outDir);

        ObjectNode root = om.createObjectNode();
        root.put("rev", rev);
        root.put("label", label);
        root.put("generatedAtIso", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.put("captureMechanism", "StatementHandler.prepare interceptor reading the final BoundSql + "
                + "DefaultParameterHandler-equivalent value resolution; no hand-written SQL.");
        root.put("projArmMechanism", "test-side Executor.query interceptor calls .select(frozen columns) on the "
                + "LambdaQueryWrapper of the target statement before proceed(); OLD arm untouched. "
                + "No src/main change in phase 1.");
        root.put("guardMechanism", "target entities wrapped in Mockito spies whose getAnswers()/getPaperJson() "
                + "throw AssertionError when called; per-statement non-null counts recorded pre-wrap; "
                + "PROJ arm must show all-null entity long fields while the DB rows under the same predicate "
                + "are non-null (mechanical count > 0).");
        root.put("dataRule", dataRule());

        ObjectNode prereg = root.putObject("preregistration");
        Path preregPath = Paths.get(PREREG_REL);
        Path shaPath = Paths.get(PREREG_SHA_REL);
        prereg.put("file", PREREG_REL);
        if (Files.exists(preregPath) && Files.exists(shaPath)) {
            String recomputed = sha256Hex(Files.readAllBytes(preregPath));
            String recorded = Files.readString(shaPath, StandardCharsets.UTF_8).trim().split("\\s+")[0];
            prereg.put("recordedSha256", recorded);
            prereg.put("recomputedSha256", recomputed);
            prereg.put("match", recorded.equalsIgnoreCase(recomputed));
            if (!recorded.equalsIgnoreCase(recomputed)) {
                problem("PREREGISTRATION.md sha256 与预登记记录不一致，测量无效");
            }
        } else {
            prereg.put("error", "preregistration file or hash record missing");
            problem("预登记文件或 sha256 记录缺失，测量无效");
        }

        // 护栏 canary：证明 wrap 后的实体「一旦真读到长字段必抛」——否则 PROJ 臂全绿
        // 只说明「没读到」，不能排除「护栏本身没接上」。canary 失败即测量无效。
        ObjectNode canary = root.putObject("guardCanary");
        canary.put("examSubmissionAnswersThrows", canaryThrowsExamAnswers());
        canary.put("gradingSubmissionAnswersThrows", canaryThrowsGradingAnswers());
        if (!canary.get("examSubmissionAnswersThrows").asBoolean()
                || !canary.get("gradingSubmissionAnswersThrows").asBoolean()) {
            problem("护栏 canary 失败：wrap 后长字段 getter 未抛 AssertionError，本次测量的护栏证据不成立");
        }

        ArrayNode shapes = root.putArray("shapes");
        try {
            for (int n : SIZES) {
                ObjectNode shapeNode = shapes.addObject();
                shapeNode.put("n", n);
                try {
                    seedShape(n, shapeNode.putObject("fixture"));
                    // 注意：Jackson 的 putObject(field) 会「替换」已有同名子节点，
                    // 若每轮都 shapeNode.putObject("sites")，前四个站点的证据节点会被逐轮清掉。
                    ObjectNode sitesNode = shapeNode.putObject("sites");
                    for (String site : List.of("s1", "s2", "s3", "s4", "s5")) {
                        runSite(site, n, sitesNode.putObject(site));
                    }
                } catch (Throwable t) {
                    problem("shape n=" + n + " 整体失败: " + describe(t));
                }
            }
        } finally {
            root.set("problems", om.valueToTree(problems));
            Path out = outDir.resolve("scalar-reads-capture-" + label + ".json");
            Files.writeString(out, om.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            System.out.println("MEASURE capture written " + out.toAbsolutePath());
        }

        if (!problems.isEmpty()) {
            fail("捕获/对拍存在问题 " + problems.size() + " 条: " + String.join(" | ", problems));
        }
    }

    // ==================== 站点编排 ====================

    private void runSite(String site, int n, ObjectNode node) throws Exception {
        node.put("targetMsId", TARGET_MS.get(site));
        node.put("frozenProjColumns", FROZEN_PROJ_COLUMNS.get(site));
        node.put("expectedQueries", EXPECTED_QUERIES.get(site));
        node.put("expectedRows", expectedRows(site, n));
        ObjectNode arms = node.putObject("arms");

        // PROJ 臂前把写站点复位到与 OLD 臂相同的臂前状态（护栏：两臂臂前快照必须逐字段相同）
        String preOld;
        String preProj;
        if ("s2".equals(site)) {
            resetS2(n);
            preOld = snapS2(n).toString();
            ArmResult oldArm = runArm(site, n, "OLD");
            String postOld = snapS2(n).toString();
            resetS2(n);
            preProj = snapS2(n).toString();
            ArmResult projArm = runArm(site, n, "PROJ");
            String postProj = snapS2(n).toString();
            arms.set("OLD", oldArm.json());
            arms.set("PROJ", projArm.json());
            node.put("preArmSnapshotsEqual", preOld.equals(preProj));
            node.put("postSnapshotsEqual", postOld.equals(postProj));
            node.put("postSnapshotOLD", postOld);
            node.put("postSnapshotPROJ", postProj);
            if (!preOld.equals(preProj)) {
                problem("s2 n=" + n + " 两臂臂前快照不同");
            }
            if (!postOld.equals(postProj)) {
                problem("s2 n=" + n + " 两臂生效快照不同（S1 语义不等价）");
            }
            node.put("s1Equivalent", postOld.equals(postProj));
        } else if ("s4".equals(site)) {
            resetS4(n);
            preOld = snapS4(n).toString();
            ArmResult oldArm = runArm(site, n, "OLD");
            String postOld = snapS4(n).toString();
            resetS4(n);
            preProj = snapS4(n).toString();
            ArmResult projArm = runArm(site, n, "PROJ");
            String postProj = snapS4(n).toString();
            arms.set("OLD", oldArm.json());
            arms.set("PROJ", projArm.json());
            node.put("preArmSnapshotsEqual", preOld.equals(preProj));
            node.put("postSnapshotsEqual", postOld.equals(postProj));
            node.put("postSnapshotOLD", postOld);
            node.put("postSnapshotPROJ", postProj);
            if (!preOld.equals(preProj)) {
                problem("s4 n=" + n + " 两臂臂前快照不同");
            }
            if (!postOld.equals(postProj)) {
                problem("s4 n=" + n + " 两臂生效快照不同（S1 语义不等价）");
            }
            node.put("s1Equivalent", postOld.equals(postProj));
        } else {
            ArmResult oldArm = runArm(site, n, "OLD");
            ArmResult projArm = runArm(site, n, "PROJ");
            arms.set("OLD", oldArm.json());
            arms.set("PROJ", projArm.json());
            boolean equivalent;
            String diff = null;
            if (oldArm.json().path("status").asText().equals("ERROR")
                    || projArm.json().path("status").asText().equals("ERROR")) {
                equivalent = false;
                diff = "arm error(s): " + oldArm.json().path("error").asText("")
                        + " / " + projArm.json().path("error").asText("");
            } else if ("s1".equals(site)) {
                diff = compareS1Items(oldArm.output(), projArm.output());
                equivalent = diff == null;
            } else if ("s3".equals(site)) {
                diff = Objects.equals(oldArm.output(), projArm.output())
                        ? null : "s3 output differs: " + oldArm.output() + " vs " + projArm.output();
                equivalent = diff == null;
                checkS3Expected(n, projArm, node);
            } else if ("s5".equals(site)) {
                diff = Objects.equals(oldArm.output(), projArm.output())
                        ? null : "s5 output differs: " + oldArm.output() + " vs " + projArm.output();
                equivalent = diff == null;
                checkS5Expected(n, projArm, node);
            } else {
                equivalent = false;
                diff = "unknown site";
            }
            node.put("s1Equivalent", equivalent);
            if (!equivalent) {
                node.put("s1Diff", diff == null ? "" : diff);
                problem(site + " n=" + n + " 语义不等价: " + diff);
            }
        }

        // 逐臂 S4 账目与护栏核对（已在 runArm 内做捕获断言；此处补跨臂等值核对）
        // 两臂节点恒存在（runArm 无论成败都会写入）；缺失时用空节点兜底，
        // 让下方 expected 对照如实记 -1 问题，而不是抛 ClassCastException 掩盖原因。
        ObjectNode oldNode = arms.path("OLD") instanceof ObjectNode o ? o : om.createObjectNode();
        ObjectNode projNode = arms.path("PROJ") instanceof ObjectNode p ? p : om.createObjectNode();
        checkArmPair(site, n, oldNode, projNode, node);
    }

    private void checkS3Expected(int n, ArmResult projArm, ObjectNode node) {
        int expected = expectedRank(n);
        node.put("expectedRank", expected);
        if (projArm.output() != null) {
            node.put("actualRank", projArm.output().path("rank").asInt(-1));
            if (projArm.output().path("rank").asInt(-1) != expected) {
                problem("s3 n=" + n + " rank 与独立推算不符 expected=" + expected
                        + " actual=" + projArm.output().path("rank").asInt(-1));
            }
        }
    }

    private void checkS5Expected(int n, ArmResult projArm, ObjectNode node) {
        node.put("expectedPlain", "30.0");
        if (projArm.output() != null) {
            String plain = projArm.output().path("plain").asText(null);
            node.put("actualPlain", plain);
            if (!"30.0".equals(plain)) {
                problem("s5 n=" + n + " 期望 takeLatest=30.0，实际 " + plain
                        + "（submit_time 或总分读取与夹具前提不符）");
            }
        }
    }

    private void checkArmPair(String site, int n, ObjectNode oldArm, ObjectNode projArm, ObjectNode node) {
        int expectedQ = EXPECTED_QUERIES.get(site);
        int expectedR = expectedRows(site, n);
        String msId = TARGET_MS.get(site);
        long oldQ = oldArm.path("accounting").path(msId).path("queries").asLong(-1);
        long oldR = oldArm.path("accounting").path(msId).path("rows").asLong(-1);
        long projQ = projArm.path("accounting").path(msId).path("queries").asLong(-1);
        long projR = projArm.path("accounting").path(msId).path("rows").asLong(-1);
        node.put("oldQueries", oldQ);
        node.put("oldRows", oldR);
        node.put("projQueries", projQ);
        node.put("projRows", projR);
        if (oldQ != expectedQ || projQ != expectedQ) {
            problem(site + " n=" + n + " 目标语句条数不符 expected=" + expectedQ
                    + " old=" + oldQ + " proj=" + projQ);
        }
        if (oldR != expectedR || projR != expectedR) {
            problem(site + " n=" + n + " 目标语句返回行数不符 expected=" + expectedR
                    + " old=" + oldR + " proj=" + projR + "（S4）");
        }
        // M1：PROJ 臂捕获 SELECT 列表 == 冻结列；OLD 列表 != PROJ 列表
        String projSelect = selectListOf(projArm.path("targetStatements").path(0).path("sql").asText(""));
        String oldSelect = selectListOf(oldArm.path("targetStatements").path(0).path("sql").asText(""));
        node.put("oldSelectList", oldSelect);
        node.put("projSelectList", projSelect);
        if (!normalizeCols(FROZEN_PROJ_COLUMNS.get(site)).equals(normalizeCols(projSelect))) {
            problem(site + " n=" + n + " PROJ 捕获 SELECT 列表 != 冻结列: " + projSelect);
        }
        if (normalizeCols(oldSelect).equals(normalizeCols(projSelect))) {
            problem(site + " n=" + n + " OLD 与 PROJ 的 SELECT 列表相同，投影未生效");
        }
        // 护栏：PROJ 臂返回实体长字段全 null；库内同谓词行非空 > 0
        long projAns = projArm.path("guard").path("answersNonNullTotal").asLong(-1);
        long projPaper = projArm.path("guard").path("paperJsonNonNullTotal").asLong(-1);
        if (projAns != 0 || projPaper != 0) {
            problem(site + " n=" + n + " PROJ 臂返回实体长字段非 null（answers=" + projAns
                    + ", paperJson=" + projPaper + "）");
        }
        long dbLong = projArm.path("guard").path("dbLongRows").asLong(-1);
        if (dbLong <= 0) {
            problem(site + " n=" + n + " 库内同谓词行长字段非空计数不 > 0（" + dbLong + "）");
        }
        long oldAns = oldArm.path("guard").path("answersNonNullTotal").asLong(-1);
        if (oldAns <= 0) {
            problem(site + " n=" + n + " OLD 臂未取回任何非空 answers（长字段未过传输层，护栏前提不成立）");
        }
    }

    // ==================== 单臂执行 ====================

    record ArmResult(ObjectNode json, ObjectNode output) {
    }

    private ArmResult runArm(String site, int n, String arm) {
        RunState.begin(site, arm);
        ScalarSiteInterceptor.reset();
        Object result = null;
        String error = null;
        try {
            result = invoke(site, n);
        } catch (Throwable t) {
            error = describe(t);
            problem(site + " n=" + n + " " + arm + " 臂执行失败: " + error);
        }
        List<Captured> all = new ArrayList<>(RunState.CAPTURED);
        List<GuardEvent> guardEvents = new ArrayList<>(RunState.GUARD_EVENTS);
        Map<String, long[]> accounting = new LinkedHashMap<>(ScalarSiteInterceptor.ACCOUNTING);
        RunState.end();

        ObjectNode json = om.createObjectNode();
        json.put("arm", arm);
        json.put("status", error == null ? "OK" : "ERROR");
        if (error != null) {
            json.put("error", error);
        }

        String targetMsId = TARGET_MS.get(site);
        ArrayNode stmts = json.putArray("targetStatements");
        ArrayNode allStmts = json.putArray("allStatements");
        int targetCount = 0;
        for (Captured c : all) {
            ObjectNode sn = allStmts.addObject();
            sn.put("msId", c.msId());
            sn.put("sql", c.sql());
            if (c.msId().equals(targetMsId)) {
                targetCount++;
                ObjectNode tn = stmts.addObject();
                tn.put("msId", c.msId());
                tn.put("sql", c.sql());
                ObjectNode vn = tn.putObject("values");
                for (Map.Entry<String, Object> e : c.values().entrySet()) {
                    vn.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
                }
                tn.put("paramCount", c.values().size());
                tn.put("literalSql", toLiteralSql(c));
            }
        }
        if (error == null && targetCount != EXPECTED_QUERIES.get(site)) {
            problem(site + " n=" + n + " " + arm + " 目标语句捕获条数=" + targetCount
                    + " 期望=" + EXPECTED_QUERIES.get(site) + "；全部捕获 msId=" + all.stream()
                    .map(Captured::msId).toList());
        }

        ObjectNode accNode = json.putObject("accounting");
        for (Map.Entry<String, long[]> e : accounting.entrySet()) {
            ObjectNode an = accNode.putObject(e.getKey());
            an.put("queries", e.getValue()[0]);
            an.put("rows", e.getValue()[1]);
        }

        ObjectNode guardNode = json.putObject("guard");
        int ansTotal = 0;
        int paperTotal = 0;
        int rowsTotal = 0;
        ArrayNode ge = guardNode.putArray("events");
        for (GuardEvent g : guardEvents) {
            ObjectNode gn = ge.addObject();
            gn.put("msId", g.msId());
            gn.put("rows", g.rows());
            gn.put("answersNonNull", g.answersNonNull());
            gn.put("paperJsonNonNull", g.paperJsonNonNull());
            ansTotal += g.answersNonNull();
            paperTotal += g.paperJsonNonNull();
            rowsTotal += g.rows();
        }
        guardNode.put("answersNonNullTotal", ansTotal);
        guardNode.put("paperJsonNonNullTotal", paperTotal);
        guardNode.put("rowsTotal", rowsTotal);
        guardNode.put("dbLongRows", dbLongRows(site, n));

        // 输出（语义快照）
        ObjectNode output = null;
        if (error == null) {
            try {
                output = buildOutput(site, n, result);
            } catch (Throwable t) {
                problem(site + " n=" + n + " " + arm + " 输出快照构建失败: " + describe(t));
            }
        }
        if (output != null) {
            json.set("output", output);
        } else {
            json.putNull("output");
        }
        return new ArmResult(json, output);
    }

    private Object invoke(String site, int n) {
        switch (site) {
            case "s1" -> {
                SecurityUtil.set(studentLogin(S1_STUDENT));
                try {
                    return examTakingService.myExams();
                } finally {
                    SecurityUtil.clear();
                }
            }
            case "s2" -> {
                SecurityUtil.set(studentLogin(S1_STUDENT));
                try {
                    return absenceService.markAbsence(E(n));
                } finally {
                    SecurityUtil.clear();
                }
            }
            case "s3" -> {
                SecurityUtil.set(studentLogin(sharedStudent(n, 0)));
                try {
                    return scoreService.myScore(E(n));
                } finally {
                    SecurityUtil.clear();
                }
            }
            case "s4" -> {
                SecurityUtil.set(adminLogin());
                try {
                    Long reviewId = jdbc.queryForObject(
                            "SELECT id FROM score_review WHERE exam_id = ? AND student_id = ?",
                            Long.class, E(n), sharedStudent(n, 1));
                    assertNotNull(reviewId, "复核行不存在");
                    ReviewHandleRequest req = new ReviewHandleRequest();
                    req.setAction("AGREE");
                    req.setAdjustedTotalScore(new BigDecimal("55.0"));
                    req.setReason("归因测量-同意调分");
                    scoreReviewService.handle(reviewId, req);
                    return "VOID";
                } finally {
                    SecurityUtil.clear();
                }
            }
            case "s5" -> {
                return makeupScoreService.finalScore(E(n), sharedStudent(n, 2));
            }
            default -> throw new IllegalStateException("unknown site " + site);
        }
    }

    private ObjectNode buildOutput(String site, int n, Object result) {
        ObjectNode out = om.createObjectNode();
        switch (site) {
            case "s1" -> {
                @SuppressWarnings("unchecked")
                List<ExamListItem> items = (List<ExamListItem>) result;
                out.put("rows", items.size());
                ArrayNode arr = out.putArray("items");
                for (ExamListItem it : items) {
                    ObjectNode o = arr.addObject();
                    o.put("examId", it.getExamId());
                    o.put("title", it.getTitle());
                    o.put("startTime", iso(it.getStartTime()));
                    o.put("endTime", iso(it.getEndTime()));
                    o.put("durationMinutes", it.getDurationMinutes());
                    o.put("examStatus", it.getExamStatus());
                    o.put("group", it.getGroup());
                    o.put("canEnter", it.isCanEnter());
                    o.put("submissionStatus", it.getSubmissionStatus());
                    o.put("remainingSeconds", it.getRemainingSeconds());
                }
            }
            case "s2" -> {
                out.put("inserted", (Integer) result);
                out.set("absences", snapS2(n));
            }
            case "s3" -> {
                MyScoreResponse r = (MyScoreResponse) result;
                out.put("examId", r.getExamId());
                out.put("examTitle", r.getExamTitle());
                out.put("objectiveScore", plain(r.getObjectiveScore()));
                out.put("subjectiveScore", plain(r.getSubjectiveScore()));
                out.put("totalScore", plain(r.getTotalScore()));
                out.put("rank", r.getRank());
                out.put("partialGraded", r.getPartialGraded());
                out.put("reviewing", Boolean.TRUE.equals(r.getReviewing()));
            }
            case "s4" -> out.set("postState", snapS4(n));
            case "s5" -> {
                BigDecimal v = (BigDecimal) result;
                out.put("plain", v == null ? null : v.toPlainString());
                out.put("compareToExpected",
                        v == null ? null : v.compareTo(new BigDecimal("30.0")));
            }
            default -> throw new IllegalStateException("unknown site " + site);
        }
        return out;
    }

    // ==================== 站点 2/4 复位与快照 ====================

    private void resetS2(int n) {
        jdbc.update("DELETE FROM exam_absence WHERE exam_id = ?", E(n));
    }

    private ArrayNode snapS2(int n) {
        ArrayNode arr = om.createArrayNode();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT student_id, status FROM exam_absence WHERE exam_id = ? ORDER BY student_id", E(n))) {
            ObjectNode o = arr.addObject();
            o.put("studentId", String.valueOf(row.get("student_id")));
            o.put("status", String.valueOf(row.get("status")));
        }
        return arr;
    }

    private void resetS4(int n) {
        jdbc.update("UPDATE score_review SET status = 0, result = NULL, handle_time = NULL, handler_id = NULL"
                + " WHERE exam_id = ? AND student_id = ?", E(n), sharedStudent(n, 1));
        jdbc.update("UPDATE exam_submissions SET total_score = ? WHERE exam_id = ? AND student_id = ?",
                score(1), E(n), sharedStudent(n, 1));
    }

    /** 站点 4 生效快照（比对集不选 T 类字段 score_review.handle_time 与 exam_submissions.updated_time）。 */
    private ObjectNode snapS4(int n) {
        ObjectNode o = om.createObjectNode();
        Map<String, Object> review = jdbc.queryForMap(
                "SELECT status, result, handler_id, apply_time, created_time FROM score_review"
                        + " WHERE exam_id = ? AND student_id = ?", E(n), sharedStudent(n, 1));
        ObjectNode rn = o.putObject("review");
        rn.put("status", str(review.get("status")));
        rn.put("result", str(review.get("result")));
        rn.put("handlerId", str(review.get("handler_id")));
        rn.put("applyTime", str(review.get("apply_time")));
        rn.put("createdTime", str(review.get("created_time")));
        Map<String, Object> sub = jdbc.queryForMap(
                "SELECT id, exam_id, student_id, start_time, deadline_time, submit_time, submit_type,"
                        + " paper_json, answers, status, version, objective_score, subjective_score,"
                        + " total_score, grading_status, grading_error, partial_graded, created_time"
                        + " FROM exam_submissions WHERE exam_id = ? AND student_id = ?",
                E(n), sharedStudent(n, 1));
        ObjectNode sn = o.putObject("submission");
        for (Map.Entry<String, Object> e : sub.entrySet()) {
            sn.put(e.getKey(), str(e.getValue()));
        }
        return o;
    }

    // ==================== 库内长字段计数（同谓词） ====================

    private long dbLongRows(String site, int n) {
        return switch (site) {
            case "s1" -> count("SELECT COUNT(*) FROM exam_submissions WHERE student_id = ?"
                    + " AND exam_id BETWEEN ? AND ? AND answers IS NOT NULL AND paper_json IS NOT NULL",
                    S1_STUDENT, S1_EXAM_BASE, S1_EXAM_BASE + S1_EXAM_COUNT - 1);
            case "s2" -> count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?"
                    + " AND student_id BETWEEN ? AND ? AND answers IS NOT NULL",
                    E(n), sharedStudent(n, 0), sharedStudent(n, n + 4));
            case "s3" -> count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ? AND student_id = ?"
                    + " AND answers IS NOT NULL", E(n), sharedStudent(n, 0));
            case "s4" -> count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ? AND student_id = ?"
                    + " AND answers IS NOT NULL", E(n), sharedStudent(n, 1));
            case "s5" -> count("SELECT COUNT(*) FROM exam_submissions WHERE answers IS NOT NULL"
                    + " AND (exam_id = ? OR exam_id = ?)", E(n), F(n));
            default -> throw new IllegalStateException("unknown site " + site);
        };
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? -1 : v;
    }

    // ==================== 夹具 ====================

    private void seedShape(int n, ObjectNode fixture) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Timestamp nowTs = Timestamp.valueOf(now);

        // ---- 站点 1 区块：50 场考试（start_time = T0 + k 分钟，保证分页 top-50 恰为本区块）----
        jdbc.update("DELETE FROM exam_submissions WHERE exam_id BETWEEN ? AND ?",
                S1_EXAM_BASE, S1_EXAM_BASE + S1_EXAM_COUNT - 1);
        jdbc.update("DELETE FROM exams WHERE id BETWEEN ? AND ?",
                S1_EXAM_BASE, S1_EXAM_BASE + S1_EXAM_COUNT - 1);
        List<Object[]> exams = new ArrayList<>(S1_EXAM_COUNT);
        for (int k = 0; k < S1_EXAM_COUNT; k++) {
            int status;
            if (k <= 19) {
                status = Exam.STATUS_ENDED;
            } else if (k <= 29) {
                status = Exam.STATUS_IN_PROGRESS;
            } else if (k <= 34) {
                status = Exam.STATUS_ENDED;
            } else if (k <= 39) {
                status = Exam.STATUS_ENDED;
            } else if (k <= 44) {
                status = Exam.STATUS_IN_PROGRESS;
            } else if (k <= 46) {
                status = Exam.STATUS_IN_PROGRESS;
            } else if (k <= 48) {
                status = Exam.STATUS_NOT_STARTED;
            } else {
                status = Exam.STATUS_ENDED;
            }
            LocalDateTime start = now.plusMinutes(k);
            exams.add(new Object[]{S1_EXAM_BASE + k, "标量读取-s1-" + k, 970_100_900L,
                    Timestamp.valueOf(start), Timestamp.valueOf(start.plusMinutes(60)), status, OWNER_ID});
        }
        jdbc.batchUpdate("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,60,?,1,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", exams);

        // 本人 45 行（k=0..44，状态分布见 PREREGISTRATION §1）
        String longAnswers = answersJson();
        String longPaper = paperJson();
        assertEquals(ANSWERS_CHARS, longAnswers.length(), "answers 长度口径");
        assertEquals(PAPER_JSON_CHARS, longPaper.length(), "paper_json 长度口径");
        List<Object[]> own = new ArrayList<>(45);
        for (int k = 0; k <= 44; k++) {
            int status;
            Timestamp deadline;
            if (k <= 19) {
                status = ExamSubmission.STATUS_SUBMITTED;
                deadline = Timestamp.valueOf(now.minusMinutes(60));
            } else if (k <= 29) {
                status = ExamSubmission.STATUS_IN_PROGRESS;
                deadline = Timestamp.valueOf(now.minusMinutes(60));
            } else if (k <= 34) {
                status = ExamSubmission.STATUS_IN_PROGRESS;
                deadline = Timestamp.valueOf(now.plusMinutes(60));
            } else if (k <= 39) {
                status = ExamSubmission.STATUS_GRADED;
                deadline = Timestamp.valueOf(now.plusMinutes(60));
            } else {
                status = ExamSubmission.STATUS_IN_PROGRESS;
                deadline = Timestamp.valueOf(now.plusMinutes(60));
            }
            own.add(new Object[]{S1_EXAM_BASE + k, S1_STUDENT, nowTs, deadline, longAnswers, longPaper, status});
        }
        jdbc.batchUpdate("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                        + " answers, paper_json, status, version, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", own);

        // 填充行（长字段 NULL；站点 SQL 按 student_id 过滤不到，仅供表规模/容器行数口径）
        int fillerCount = n - 45;
        if (fillerCount > 0) {
            List<Object[]> fillers = new ArrayList<>(fillerCount);
            for (int j = 0; j < fillerCount; j++) {
                fillers.add(new Object[]{S1_EXAM_BASE + (j % S1_EXAM_COUNT), S1_FILLER_BASE + j,
                        nowTs, Timestamp.valueOf(now.plusMinutes(60))});
            }
            jdbc.batchUpdate("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                            + " status, version, created_time, updated_time)"
                            + " VALUES (?,?,?,?,3,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", fillers);
        }

        // ---- 共享区块：E(n) 主考 / F(n) 补考 / 班级 / 应考名单 / 答卷 / 复核行 ----
        long e = E(n);
        long f = F(n);
        long c = C(n);
        jdbc.update("DELETE FROM exam_submissions WHERE exam_id IN (?,?)", e, f);
        jdbc.update("DELETE FROM exam_absence WHERE exam_id = ?", e);
        jdbc.update("DELETE FROM score_review WHERE exam_id = ?", e);
        jdbc.update("DELETE FROM user_class WHERE class_id = ?", c);
        jdbc.update("DELETE FROM classes WHERE id = ?", c);
        jdbc.update("DELETE FROM exams WHERE id IN (?,?)", e, f);

        jdbc.update("INSERT INTO exams (id, title, paper_id, class_id, parent_exam_id, makeup_score_rule,"
                        + " start_time, end_time, duration_minutes, status, published, created_by, version,"
                        + " is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,?,NULL,?,?,?,60,4,1,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                e, "标量读取-主考-n" + n, 970_200_900L, c, Exam.MAKEUP_TAKE_LATEST,
                Timestamp.valueOf(now.minusDays(10)), Timestamp.valueOf(now.minusDays(10).plusMinutes(60)), OWNER_ID);
        jdbc.update("INSERT INTO exams (id, title, paper_id, class_id, parent_exam_id, makeup_score_rule,"
                        + " start_time, end_time, duration_minutes, status, published, created_by, version,"
                        + " is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,NULL,?,?,?,?,60,3,1,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                f, "标量读取-补考-n" + n, 970_300_900L, e, Exam.MAKEUP_TAKE_LATEST,
                Timestamp.valueOf(now.minusDays(9)), Timestamp.valueOf(now.minusDays(9).plusMinutes(60)), OWNER_ID);

        jdbc.update("INSERT INTO classes (id, name, course_id, teacher_id, created_by, created_time,"
                        + " updated_time, is_deleted) VALUES (?,?,NULL,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                c, "标量读取-测量班级-n" + n, OWNER_ID, OWNER_ID);
        List<Object[]> roster = new ArrayList<>(n + 5);
        for (int i = 0; i <= n + 4; i++) {
            roster.add(new Object[]{sharedStudent(n, i), c});
        }
        jdbc.batchUpdate("INSERT INTO user_class (user_id, class_id, joined_time)"
                + " VALUES (?,?,CURRENT_TIMESTAMP)", roster);

        List<Object[]> eRows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BigDecimal s = score(i);
            eRows.add(new Object[]{e, sharedStudent(n, i), Timestamp.valueOf(now.minusHours(4)),
                    Timestamp.valueOf(now.plusHours(2)), Timestamp.valueOf(now.minusHours(2)),
                    longAnswers, longPaper, ExamSubmission.STATUS_GRADED, s, s});
        }
        jdbc.batchUpdate("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                        + " submit_time, answers, paper_json, status, objective_score, subjective_score,"
                        + " total_score, grading_status, partial_graded, version, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,0.0,?,1,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", eRows);
        jdbc.update("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                        + " submit_time, answers, paper_json, status, objective_score, subjective_score,"
                        + " total_score, grading_status, partial_graded, version, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,?,3,30.0,0.0,30.0,1,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                f, sharedStudent(n, 2), Timestamp.valueOf(now.minusHours(3)),
                Timestamp.valueOf(now.plusHours(1)), Timestamp.valueOf(now.minusHours(1)),
                longAnswers, longPaper);

        jdbc.update("INSERT INTO score_review (exam_id, student_id, status, reason, apply_time,"
                        + " handle_time, handler_id, created_time)"
                        + " VALUES (?,?,0,?,?,NULL,NULL,?)",
                e, sharedStudent(n, 1), "归因测量复核申请",
                Timestamp.valueOf(now.minusHours(1)), Timestamp.valueOf(now.minusHours(1)));

        // ---- 夹具核对落盘 ----
        fixture.put("s1BlockRows", count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id BETWEEN ? AND ?",
                S1_EXAM_BASE, S1_EXAM_BASE + S1_EXAM_COUNT - 1));
        fixture.put("s1OwnRows", count("SELECT COUNT(*) FROM exam_submissions WHERE student_id = ?",
                S1_STUDENT));
        fixture.put("e", e);
        fixture.put("f", f);
        fixture.put("classId", c);
        fixture.put("eRows", count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?", e));
        fixture.put("fRows", count("SELECT COUNT(*) FROM exam_submissions WHERE exam_id = ?", f));
        fixture.put("rosterRows", count("SELECT COUNT(*) FROM user_class WHERE class_id = ?", c));
        fixture.put("answersChars", ANSWERS_CHARS);
        fixture.put("paperJsonChars", PAPER_JSON_CHARS);
        fixture.put("answersIsNull", count("SELECT COUNT(*) FROM exam_submissions WHERE answers IS NULL"));
        long block = fixture.path("s1BlockRows").asLong();
        long eRowsN = fixture.path("eRows").asLong();
        long rosterN = fixture.path("rosterRows").asLong();
        if (block != n) {
            problem("n=" + n + " 站点 1 区块行数 " + block + " != n");
        }
        if (eRowsN != n) {
            problem("n=" + n + " E 区块行数 " + eRowsN + " != n");
        }
        if (rosterN != n + 5) {
            problem("n=" + n + " 应考名单行数 " + rosterN + " != n+5");
        }
    }

    // ==================== 比较与小工具 ====================

    /** S1 站点 1：逐项比较（remainingSeconds 允许 |Δ|≤1s，其余严格相等）。返回 null=等价。 */
    private String compareS1Items(ObjectNode a, ObjectNode b) {
        if (a == null || b == null) {
            return "output missing (old=" + (a == null) + ", proj=" + (b == null) + ")";
        }
        ArrayNode ia = (ArrayNode) a.path("items");
        ArrayNode ib = (ArrayNode) b.path("items");
        if (ia.size() != ib.size()) {
            return "item count " + ia.size() + " vs " + ib.size();
        }
        for (int i = 0; i < ia.size(); i++) {
            ObjectNode x = (ObjectNode) ia.get(i);
            ObjectNode y = (ObjectNode) ib.get(i);
            for (String f : List.of("examId", "title", "startTime", "endTime", "durationMinutes",
                    "examStatus", "group", "canEnter", "submissionStatus")) {
                if (!Objects.equals(x.path(f), y.path(f))) {
                    return "item[" + i + "]." + f + " " + x.path(f) + " vs " + y.path(f);
                }
            }
            // T 类字段：remainingSeconds（同为 null 或同非 null 且 |Δ| ≤ 1 秒）
            if (x.path(T_FIELD_S1).isNull() != y.path(T_FIELD_S1).isNull()) {
                return "item[" + i + "].remainingSeconds nullness differs";
            }
            if (!x.path(T_FIELD_S1).isNull()) {
                long dx = x.path(T_FIELD_S1).asLong();
                long dy = y.path(T_FIELD_S1).asLong();
                if (Math.abs(dx - dy) > 1) {
                    return "item[" + i + "].remainingSeconds delta " + Math.abs(dx - dy) + "s > 1s";
                }
            }
        }
        return null;
    }

    private long E(int n) {
        return SHARED_E_BASE + n;
    }

    private long F(int n) {
        return SHARED_F_BASE + n;
    }

    private long C(int n) {
        return SHARED_CLASS_BASE + n;
    }

    private static long sharedStudent(int n, int i) {
        return SHARED_STUDENT_BASE + i;
    }

    private static BigDecimal score(int i) {
        return BigDecimal.valueOf(400 + (i * 7919L) % 300, 1);
    }

    /** 名次预期 = n − floor((n−1)/300)（本人分 40.0 为全场最低分，并列同名次口径）。 */
    private static int expectedRank(int n) {
        return n - (n - 1) / 300;
    }

    private static int expectedRows(String site, int n) {
        return switch (site) {
            case "s1" -> 45;
            case "s2" -> n;
            case "s3", "s4" -> 1;
            case "s5" -> 2;
            default -> throw new IllegalStateException("unknown site " + site);
        };
    }

    private static String answersJson() {
        return "{\"1001\":\"" + "A".repeat(ANSWERS_CHARS - 11) + "\"}";
    }

    private static String paperJson() {
        return "{\"snapshot\":\"" + "B".repeat(PAPER_JSON_CHARS - 15) + "\"}";
    }

    private static LoginUser studentLogin(long studentId) {
        LoginUser user = new LoginUser();
        user.setId(studentId);
        user.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.STUDENT));
        return user;
    }

    private static LoginUser adminLogin() {
        LoginUser user = new LoginUser();
        user.setId(OWNER_ID);
        user.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.ADMIN));
        return user;
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String describe(Throwable t) {
        return t.getClass().getName() + ": " + t.getMessage();
    }

    private void problem(String p) {
        System.out.println("MEASURE PROBLEM " + p);
        problems.add(p);
    }

    private String dataRule() {
        return "H2 test context（传输层，非证据）：s1 区块 50 场考试（970100000+k，start_time=T0+k 分钟、"
                + "published=1）+ 本人 45 行（student=973100000，状态分布含 ONGOING/FINISHED 各分支，"
                + "answers/paper_json 恰 2048/20480 字符）+ 填充行至 n 行（974000000+j 轮转 50 场，长字段 NULL）；"
                + "共享区块 E(n)=970200000+n（status=4/published=1/class=972200000+n/takeLatest）、"
                + "F(n)=970300000+n（parent=E/status=3）、应考名单 n+5 人（973200000+i，i=0..n+4）、"
                + "E 上 n 行答卷（status=3、score(i)=BigDecimal(400+(i*7919)%300,1)、answers/paper_json 全长）、"
                + "F 上仅 i=2 一行（30.0、submit_time 晚于主考）、score_review(E, i=1, status=0)。"
                + "身份：s1/s2=合成 STUDENT 973100000；s3=973200000；s4=ADMIN 973999999；s5 不校验身份。";
    }

    /** 「? → 字面量」机械替换（替换次数必须与参数数一致）。 */
    private static String toLiteralSql(Captured c) {
        String sql = c.sql();
        int q = countOccurrences(sql, '?');
        assertEquals(c.values().size(), q,
                "参数位数与 ? 数不一致：" + c.values().size() + " vs " + q);
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

    private static int countOccurrences(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                count++;
            }
        }
        return count;
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

    private static String normalizeCols(String csv) {
        return csv.replaceAll("\\s+", "").toLowerCase();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }
}
