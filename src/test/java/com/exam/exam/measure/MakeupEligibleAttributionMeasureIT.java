package com.exam.exam.measure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.exam.dto.MakeupCandidateItem;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamAbsence;
import com.exam.exam.mapper.ExamAbsenceMapper;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.MakeupService;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 补考候选名单（{@code MakeupService.listEligibleStudents}）低分取数归因隔离测量工具
 * （update-makeup-eligible-score-filter-attribution 阶段 1）。
 *
 * <p><b>为什么类名以 IT 结尾</b>：Surefire 默认只收 {@code *Test}/{@code *Tests}/{@code Test*}/{@code TestCase}，
 * 本类不进 {@code mvnw.cmd clean test} 全量门禁，只有显式 {@code -Dtest=MakeupEligibleAttributionMeasureIT} 才运行。
 * 它只做测量与 oracle 校验，不构成业务断言基线。
 *
 * <p><b>测什么</b>：对可复现夹具（人数 × 低分比例 × 缺考数 × answers 大小）做三类测量：
 * <ol>
 *   <li><b>旧路径请求级</b>：直接调生产 {@code listEligibleStudents}，批量采样墙钟均值/P50/P99、吞吐、当前线程分配字节；
 *       每轮在受控臂序列前后<b>各测一次</b>（夹逼），两头比值给出顺序漂移的直接读数；</li>
 *   <li><b>分相位副本</b>（test-only，逐句镜像生产语句，仅在相位边界插桩）：归属校验读考试 → 缺考查询 →
 *       答卷查询（行数/answers 字符量/耗时）→ Java 过滤 → 姓名批量装载，并捕获每一步的 SQL 原文与返回行数；</li>
 *   <li><b>两个受控臂（互不混用，顺序逐轮轮转）</b>：{@code PUSHDOWN} = <b>仅</b>把 {@code total_score < passLine} 下推进 SQL
 *       （列不变、Java 过滤保留为冗余护栏）；{@code PROJECTION} = <b>仅</b>把答卷查询改成窄列投影
 *       （{@code id/student_id/total_score}，谓词不变、Java 过滤仍在生效）。两臂与旧路径在同一夹具、同一预热、
 *       同一批量口径下各自测请求级与分相位，并与旧结果按 (studentId, reason, name) 集合对账。
 *       受控臂 SQL 形状有硬校验：PUSHDOWN 必须且只须多出严格小于谓词，PROJECTION 必须且只须去掉 answers 列，否则本轮判无效。</li>
 * </ol>
 *
 * <p><b>口径与边界</b>（必须随结果一起回报）：
 * <ul>
 *   <li>分配量用 {@code com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes} 差分的精确字节数（单线程窗口）；</li>
 *   <li>答卷字段量是 <b>Java 侧从返回对象统计的 answers 字符数</b>（本夹具为 ASCII JSON，1 字符 = 1 字节），
 *       <b>不是</b> MySQL 线上传输字节，也不是网络/RTT 估计；</li>
 *   <li>相位耗时含 JDBC 驱动/H2 进程内执行与 MyBatis 结果映射，是墙钟而非纯 CPU；跨相位、跨轮不相减求占比；</li>
 *   <li>受控臂与相位运行都是额外一遍完整路径（探针），其耗时单列，<b>不并入</b>请求级批量样本；
 *       臂顺序逐轮轮转，且 {@code OLD} 副本臂是「与旧实现等价的工作量」的对照臂，其比值区间即噪声下限；</li>
 *   <li>结果等价判据是「(studentId, reason, name) 排序后的集合相等」，<b>不是</b>顺序相等——现行查询无 ORDER BY，
 *       不制造排序契约；同一 studentId 同时出现在缺考与低分集合时低分原因覆盖（与生产 LinkedHashMap 的 put 语义一致）；</li>
 *   <li>本工具跑隔离 H2 内存库 + 进程内直调 Service，<b>不是</b>真实 MySQL/Tomcat 生产性能，不外推 P99 或生产请求频度；
 *       不写共享 dev、不启 Docker、不改 {@code src/main}/schema/Mapper/前端/JVM 参数。</li>
 * </ul>
 *
 * <p><b>运行</b>（仓库根）：
 * <pre>
 * mvnw.cmd -o test "-Dtest=MakeupEligibleAttributionMeasureIT" "-DfailIfNoTests=false" ^
 *     "-Dmeasure.rev=&lt;sha7&gt;" "-Dmeasure.label=makeup-run1" "-Dmeasure.out=spec/changes/&lt;change&gt;/evidence"
 * </pre>
 * 机器可读结果写到 {@code ${measure.out}/makeup-eligible-attribution-${measure.label}.json}。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("补考候选低分过滤归因测量（仅显式 -Dtest 运行；不进 Surefire 全量门禁）")
class MakeupEligibleAttributionMeasureIT {

    // ==================== 固定口径 ====================

    /** 裁决用及格线：与夹具分数 50.0 / 80.0 保持严格大小关系，不制造边界并列。 */
    private static final BigDecimal PASS_LINE = new BigDecimal("60");
    private static final BigDecimal BELOW_SCORE = new BigDecimal("50.0");
    private static final BigDecimal ABOVE_SCORE = new BigDecimal("80.0");

    /** 请求级批量样本数（单线程顺序采样，每次都是完整路径调用）。 */
    private static final int BATCH = 21;
    /** 并发臂：8 线程 × 每线程 8 次（仅 OLD 与 PUSHDOWN，用于验证收益不是单线程伪影）。 */
    private static final int CONC_THREADS = 8;
    private static final int CONC_PER_THREAD = 8;
    /** 一轮内重复次数（逐轮原样上报，不挑最好一轮）。 */
    private static final int ROUNDS = 4;
    /** 每 combo 每臂预热次数（不计入测量窗口）。 */
    private static final int WARMUP = 2;

    /**
     * 轮内臂顺序轮转表：抵消「越靠后越快」的单调漂移（JIT/GC/缓存）。
     * 每轮臂序列两侧各夹一次生产入口（prodFirst/prodLast），两者之差就是顺序与漂移噪声的直接读数。
     */
    private static final Mode[][] ARM_ORDERS = {
            {Mode.OLD, Mode.PUSHDOWN, Mode.PROJECTION},
            {Mode.PROJECTION, Mode.PUSHDOWN, Mode.OLD},
            {Mode.PUSHDOWN, Mode.OLD, Mode.PROJECTION},
            {Mode.PROJECTION, Mode.OLD, Mode.PUSHDOWN}
    };

    private record Combo(String label, int submissions, double belowRatio, int absentCount,
                         int answersChars, String note) {
    }

    /**
     * 数据形状：人数 × 低分比例（passLine 之下占比）× 缺考数 × answers 字符数。
     * {@code p90} 是「低分占比高」的反例形状（下推可省的行很少）；{@code a20k} 放大答卷长字段。
     */
    private static final List<Combo> COMBOS = List.of(
            new Combo("s200-p10-a2k", 200, 0.10, 10, 2048, "小规模基线：200 份答卷、10% 低于线、2KB 答卷"),
            new Combo("s1000-p10-a2k", 1000, 0.10, 50, 2048, "中规模：1000 份、10% 低于线、2KB 答卷"),
            new Combo("s1000-p50-a2k", 1000, 0.50, 50, 2048, "低分占比中等：下推省一半行"),
            new Combo("s1000-p90-a2k", 1000, 0.90, 50, 2048, "低分占比高（反例形状）：下推几乎不省行"),
            new Combo("s1000-p10-a20k", 1000, 0.10, 50, 20480, "答卷长字段放大：20KB/份"),
            new Combo("s3000-p10-a2k", 3000, 0.10, 150, 2048, "大规模：3000 份、10% 低于线、2KB 答卷")
    );

    private static final long EXAM_ID_BASE = 951_000_000L;
    /**
     * 学生 ID 命名空间：每个 combo 独占 {@code COMBO_ID_BLOCK} 宽的块（答卷学生从块基址起，
     * 缺考学生从块内 +{@code ABSENT_BLOCK_OFFSET} 起），块与块、块与边缘/属主 ID 之间互不重叠。
     * 这是硬要求：users 主键与 uk_users_username 都是全局唯一，任何重叠都会让夹具插入直接失败。
     */
    private static final long PERF_ID_BASE = 953_000_000L;
    private static final long COMBO_ID_BLOCK = 10_000_000L;
    private static final long ABSENT_BLOCK_OFFSET = 9_000_000L;
    private static final long OWNER_ID = 951_999_999L;
    private static final long OTHER_USER_ID = 951_999_998L;

    /** 语义边界夹具：两场含缺考/低分/边界/软删/缺失姓名的考试（其一已软删）+ 一场空候选考试。 */
    private static final long EDGE_EXAM_ID = 951_900_100L;
    private static final long EMPTY_EXAM_ID = 951_900_200L;
    private static final long DELETED_EXAM_ID = 951_900_300L;
    private static final long EDGE_ID_BASE = 952_500_000L;
    private static final long MISSING_EXAM_ID = 951_999_997L;

    private enum Mode {
        /** 旧实现口径：全列取回 + Java 过滤（与生产现行代码逐句一致）。 */
        OLD,
        /** 仅分数谓词下推：库内过滤 total_score < passLine，列不变、Java 过滤保留。 */
        PUSHDOWN,
        /** 仅窄列投影：只取 id/student_id/total_score，谓词与 Java 过滤不变。 */
        PROJECTION
    }

    private static final String DATA_RULE =
            "每 combo 一场考试：exams.status=4（已发布）、created_by=合成 owner；"
            + "exam_submissions 直插 status=3、grading_status=1、total_score 前 belowRatio 比例=50.0（低于 60）、其余=80.0、"
            + "answers=定长 ASCII JSON（长度 answersChars，内容为单键长字符串，端点只整体搬运不解析）、"
            + "objective/subjective_score 同 total；缺考学生在同一 combo 独占 ID 块内、无答卷行、users 有姓名、exam_absence 各一行；"
            + "users 逐生一行姓名。边界夹具另有：边缘考试（总分 null、总分=60.0 与 60.00、缺考∩低分同 ID 覆盖、"
            + "无 users 行、users.is_deleted=1 软删姓名）、已软删考试（exams.is_deleted=1，应得 404）、"
            + "空候选考试（无缺考、唯一答卷在线上，应短路且不发起姓名查询）。"
            + "combo 之间与边缘 ID 段互不重叠（users 主键/用户名全局唯一，重叠即插入失败）。";

    private static final Map<String, String> METRIC_DEFS = Map.ofEntries(
            Map.entry("prodFirst|prodLast",
                    "生产 MakeupService.listEligibleStudents 请求级批量（BATCH 次顺序调用）的墙钟均值/P50/P99/吞吐/单次分配；"
                    + "每轮在臂序列前后各测一次（夹逼），两头之差即顺序漂移的直接读数"),
            Map.entry("arms.OLD|PUSHDOWN|PROJECTION",
                    "test-only 受控副本请求级批量：OLD 与生产逐句一致；PUSHDOWN 仅把 total_score<passLine 下推 SQL；"
                    + "PROJECTION 仅改窄列投影；三者同一夹具/预热/批量口径，互不混用；臂顺序逐轮轮转（armOrder 记录）"),
            Map.entry("instrumented.<mode>", "带相位计时+SQL 捕获的额外一遍完整路径（探针，单列，不并入请求级批量）"),
            Map.entry("instrumented.phases", "ownerLoad/absenceLoad/gradingSelect/javaFilter/nameLoad 各自墙钟与分配（含 JDBC+映射）；"
                    + "phase 占比是同窗内比值，合法；跨臂相减只作量级参照，不作为因素占比"),
            Map.entry("instrumented.gradingRowsOut/answersCharsTotal",
                    "答卷查询返回行数；answers 字符数合计为 Java 侧统计（ASCII 夹具 1 字符=1 字节），不是 MySQL 网络传输量"),
            Map.entry("instrumented.sql[]", "本次探针路径捕获的每条 SQL（原文截断至 600 字符、耗时、返回行数、原始长度），"
                    + "用于证明受控臂只改了什么；答卷 SQL 的列与谓词都在前 600 字符内"),
            Map.entry("conc8", "8 线程 × 8 次并发批量的吞吐与均值（仅 OLD/PUSHDOWN，顺序逐轮轮转），验证收益不是单线程伪影"),
            Map.entry("equivalence", "旧路径（两侧生产样本）/两臂的结果 (studentId|reason|name) 排序集合是否相等；"
                    + "不等即抛错判本轮无效；另记下推结构性消除的行数/字符量（由行数×字段长度直接可算，非时间推断）"),
            Map.entry("note", "隔离 H2 + 进程内直调 Service，非真实 MySQL/Tomcat；不跨轮跨窗口相减求占比；"
                    + "answers 字符量不是网络字节；墙钟含 GC/JIT/调度与探针插桩")
    );

    // ==================== 测试侧 SQL 捕获（仅本测试上下文） ====================

    /**
     * 捕获 MyBatis 查询（MyBatis-Plus 会收集容器内 {@link Interceptor} bean 作为插件）：
     * 记录映射语句 id、SQL 原文、执行墙钟与返回行数；{@code enabled=false} 时零开销直通
     * （请求级批量样本都在关闭状态下采集）。
     */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            CacheKey.class, BoundSql.class})
    })
    static class SqlCapture implements Interceptor {
        private static final List<Map<String, Object>> EXECUTIONS =
                Collections.synchronizedList(new ArrayList<>());
        private static volatile boolean enabled = false;

        static void reset() {
            EXECUTIONS.clear();
        }

        static List<Map<String, Object>> snapshot() {
            synchronized (EXECUTIONS) {
                return new ArrayList<>(EXECUTIONS);
            }
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            if (!enabled) {
                return invocation.proceed();
            }
            Object[] args = invocation.getArgs();
            MappedStatement ms = (MappedStatement) args[0];
            Object parameter = args[1];
            BoundSql boundSql = args.length == 6 && args[5] instanceof BoundSql b
                    ? b : ms.getBoundSql(parameter);
            long t0 = System.nanoTime();
            Object result = invocation.proceed();
            long nanos = System.nanoTime() - t0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ms.getId());
            String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();
            // 只留形状判据需要的头部（1500 人以上时 selectBatchIds 的 IN 列表可达数十 KB）：
            // 答卷查询的 SELECT 列与 WHERE 谓词都在前 600 字符内，故截断不影响臂形状校验
            row.put("sql", sql.length() <= 600 ? sql : sql.substring(0, 600));
            row.put("sqlLength", sql.length());
            row.put("sqlTruncated", sql.length() > 600);
            row.put("millis", round3(nanos / 1e6));
            row.put("rows", result instanceof List<?> list ? list.size() : -1);
            EXECUTIONS.add(row);
            return result;
        }
    }

    @TestConfiguration
    static class MeasureSqlConfig {
        @Bean
        SqlCapture makeupMeasureSqlCapture() {
            return new SqlCapture();
        }
    }

    // ==================== 依赖 ====================

    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MakeupService makeupService;
    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private ExamAbsenceMapper absenceMapper;
    @Autowired
    private GradingSubmissionMapper gradingMapper;
    @Autowired
    private UserMapper userMapper;

    // ==================== 主流程 ====================

    @Test
    @DisplayName("同夹具多轮：旧路径请求级 + 分相位 + 两受控臂 + 并发臂 + oracle 对账，落盘机器可读结果")
    void measure() throws Exception {
        String rev = System.getProperty("measure.rev", "unknown");
        String label = System.getProperty("measure.label", "rev" + rev);
        Path outDir = Paths.get(System.getProperty("measure.out", "target/measure"));
        Files.createDirectories(outDir);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", rev);
        result.put("label", label);
        result.put("startedAt", LocalDateTime.now().toString());
        result.put("jdk", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        result.put("vm", System.getProperty("java.vm.name") + " / " + System.getProperty("java.vm.version"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("maxHeapMB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        result.put("dataRule", DATA_RULE);
        result.put("metricDefinitions", METRIC_DEFS);
        result.put("loadParams", loadParams());
        result.put("runtimeFrequencyUnknown",
                "本端点真实请求频度、真实考试人数与 answers 分布无获准来源（未读共享 dev、未读未授权运行时日志、"
                + "未采信前端调用点或 dev 演示记录），记为未知；本测量不外推生产 P99 或调用量");

        // 合成登录上下文：listEligibleStudents 只做归属校验（ADMIN 层级放行），不触接口层。
        LoginUser admin = loginUser(OTHER_USER_ID, 3);
        SecurityUtil.set(admin);
        try {
            long t0 = System.currentTimeMillis();
            preparePerfFixtures();
            prepareEdgeFixtures();
            result.put("prepareDataMillis", System.currentTimeMillis() - t0);

            // 旧行为 oracle（语义边界，先于测量；不成立即证据无效）
            List<Map<String, Object>> semantics = runSemanticsOracle();
            result.put("semantics", semantics);
            System.out.println("MEASURE semantics " + semantics.size() + " cases");

            // 预热（不计入窗口）：JIT 与 MyBatis 语句缓存覆盖 4 条路径 × 全部形状
            for (Combo combo : COMBOS) {
                long examId = examIdOf(combo);
                for (int i = 0; i < WARMUP; i++) {
                    makeupService.listEligibleStudents(examId, PASS_LINE);
                    for (Mode mode : Mode.values()) {
                        runPath(examId, PASS_LINE, mode);
                    }
                }
            }

            // 每 combo 的独立 oracle：由夹具参数独立推算期望集合，与生产结果对账
            List<Map<String, Object>> comboOracle = new ArrayList<>();
            for (Combo combo : COMBOS) {
                Map<String, Object> o = validateComboOracle(combo);
                comboOracle.add(o);
                System.out.println("MEASURE oracle " + o);
                if (!Boolean.TRUE.equals(o.get("oracleMatch"))) {
                    throw new IllegalStateException(combo.label() + " 夹具 oracle 不成立，本轮证据无效: " + o);
                }
            }
            result.put("comboOracle", comboOracle);

            List<Map<String, Object>> combos = new ArrayList<>();
            for (Combo combo : COMBOS) {
                Map<String, Object> comboResult = new LinkedHashMap<>();
                comboResult.put("combo", combo.label());
                comboResult.put("submissions", combo.submissions());
                comboResult.put("belowRatio", combo.belowRatio());
                comboResult.put("absentCount", combo.absentCount());
                comboResult.put("answersCharsEach", combo.answersChars());
                List<Map<String, Object>> rounds = new ArrayList<>();
                for (int round = 1; round <= ROUNDS; round++) {
                    Map<String, Object> m = measureOne(combo, round);
                    rounds.add(m);
                    System.out.println("MEASURE " + summarize(m));
                }
                comboResult.put("rounds", rounds);
                combos.add(comboResult);
            }
            result.put("combos", combos);
        } finally {
            SecurityUtil.clear();
            SqlCapture.enabled = false;
        }

        result.put("finishedAt", LocalDateTime.now().toString());
        Path jsonPath = outDir.resolve("makeup-eligible-attribution-" + label + ".json");
        Files.writeString(jsonPath, om.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                StandardCharsets.UTF_8);
        System.out.println("MEASURE wrote " + jsonPath.toAbsolutePath());
    }

    /** 一个 combo 一轮：夹逼的两侧生产样本 + 轮转顺序的三臂 + 并发臂 + 三臂分相位探针 + 等价对账与差值。 */
    private Map<String, Object> measureOne(Combo combo, int round) {
        long examId = examIdOf(combo);
        Mode[] order = ARM_ORDERS[(round - 1) % ARM_ORDERS.length];
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("combo", combo.label());
        r.put("round", round);
        r.put("armOrder", java.util.Arrays.stream(order).map(Enum::name).toList());

        // 生产入口在臂序列前后各测一次（同轮夹逼）：两头样本之差给出顺序漂移的直接读数
        BatchResult prodFirst = batchRequest(examId, PASS_LINE, null);
        r.put("prodFirst", prodFirst.metrics());

        Map<String, Map<String, Object>> arms = new LinkedHashMap<>();
        Map<String, String> canon = new LinkedHashMap<>();
        canon.put("prodFirst", prodFirst.canonical());
        for (Mode mode : order) {
            BatchResult b = batchRequest(examId, PASS_LINE, mode);
            arms.put(mode.name(), b.metrics());
            canon.put(mode.name(), b.canonical());
        }
        BatchResult prodLast = batchRequest(examId, PASS_LINE, null);
        canon.put("prodLast", prodLast.canonical());
        r.put("prodLast", prodLast.metrics());
        r.put("arms", arms);

        // 并发臂：验证收益不是单线程伪影（顺序逐轮轮转，抵消先跑者劣势）
        Map<String, Object> conc = new LinkedHashMap<>();
        if (round % 2 == 0) {
            conc.put("order", List.of("PUSHDOWN", "OLD"));
            conc.put("PUSHDOWN", batchConcurrent(examId, PASS_LINE, Mode.PUSHDOWN));
            conc.put("OLD", batchConcurrent(examId, PASS_LINE, Mode.OLD));
        } else {
            conc.put("order", List.of("OLD", "PUSHDOWN"));
            conc.put("OLD", batchConcurrent(examId, PASS_LINE, Mode.OLD));
            conc.put("PUSHDOWN", batchConcurrent(examId, PASS_LINE, Mode.PUSHDOWN));
        }
        r.put("conc8", conc);

        // 分相位探针（额外一遍完整路径；含 SQL 捕获）
        Map<String, Map<String, Object>> instrumented = new LinkedHashMap<>();
        for (Mode mode : Mode.values()) {
            instrumented.put(mode.name(), runInstrumented(examId, PASS_LINE, mode));
        }
        r.put("instrumented", instrumented);

        // 等价对账：集合相等（不是顺序相等）；比较全部四路 canonical（含两侧生产样本）
        Map<String, Object> eq = new LinkedHashMap<>();
        String base = canon.get("prodFirst");
        boolean allEqual = true;
        List<String> unequal = new ArrayList<>();
        for (String key : List.of("prodLast", "OLD", "PUSHDOWN", "PROJECTION")) {
            boolean same = base.equals(canon.get(key));
            eq.put("prodFirstVs" + key, same);
            if (!same) {
                unequal.add(key);
                allEqual = false;
            }
        }
        eq.put("allEqual", allEqual);
        if (!allEqual) {
            throw new IllegalStateException(combo.label() + " 轮 " + round + " 受控臂结果与旧路径不等价: " + unequal
                    + " prodFirst=" + digestOf(base) + " arm=" + canon.keySet().stream()
                    .map(k -> k + ":" + digestOf(canon.get(k))).toList());
        }
        eq.put("eligibleSize", prodFirst.items().size());
        eq.put("reasonCounts", reasonCounts(prodFirst.items()));
        eq.put("canonicalDigest", digestOf(base));
        // 结构性消除量（由行数×字段长度直接可算，不是时间推断）：下推省掉的行与字符量
        long rowsOld = number(instrumented.get("OLD").get("gradingRowsOut"));
        long rowsPush = number(instrumented.get("PUSHDOWN").get("gradingRowsOut"));
        long charsMax = number(instrumented.get("OLD").get("gradingAnswersCharsMax"));
        eq.put("gradingRowsEliminatedByPushdown", rowsOld - rowsPush);
        eq.put("gradingCharsEliminatedByPushdown", (rowsOld - rowsPush) * charsMax);
        r.put("equivalence", eq);

        // 差值：同轮、同夹具、同批量口径下的请求级均值比（不做跨轮/跨窗口相减占比）
        Map<String, Object> deltas = new LinkedHashMap<>();
        deltas.put("prodLastVsFirstRatio", ratio(prodFirst.metrics(), prodLast.metrics()));
        for (Mode mode : Mode.values()) {
            deltas.put(mode.name() + "MeanRatioVsProdFirst", ratio(prodFirst.metrics(), arms.get(mode.name())));
            deltas.put(mode.name() + "MeanRatioVsProdLast", ratio(prodLast.metrics(), arms.get(mode.name())));
        }
        deltas.put("note", "比值为同一轮内 mean 的商（prod/arm）；prodFirstVsLastRatio 是不改行为的顺序漂移读数，"
                + "作为该 combo 的噪声下限参照；跨轮变异见逐轮原值");
        r.put("deltas", deltas);

        // 硬护栏：捕获必须生效，且三臂 SQL 的差异必须恰好就是各自声明的那一处
        // （否则本轮的 SQL 归因与受控臂证据无效——宁可判无效，不产出无法归因的数字）
        Map<String, Object> probeOld = probe(instrumented, Mode.OLD);
        Map<String, Object> probePush = probe(instrumented, Mode.PUSHDOWN);
        Map<String, Object> probeProj = probe(instrumented, Mode.PROJECTION);
        Map<String, Object> sqlOld = gradingSqlOf(probeOld);
        Map<String, Object> sqlPush = gradingSqlOf(probePush);
        Map<String, Object> sqlProj = gradingSqlOf(probeProj);
        if (sqlOld == null || sqlPush == null || sqlProj == null) {
            throw new IllegalStateException(combo.label() + " 轮 " + round + " 未捕获答卷 SQL，SQL 归因无效");
        }
        Map<String, Object> armCheck = new LinkedHashMap<>();
        armCheck.put("oldSql", sqlOld);
        armCheck.put("pushdownSql", sqlPush);
        armCheck.put("projectionSql", sqlProj);
        String oldText = sqlText(sqlOld);
        String pushText = sqlText(sqlPush);
        String projText = sqlText(sqlProj);
        boolean predicateOk = !hasLtPredicate(oldText) && hasLtPredicate(pushText) && !hasLtPredicate(projText);
        boolean projectionOk = hasAnswersColumn(oldText) && hasAnswersColumn(pushText) && !hasAnswersColumn(projText);
        armCheck.put("pushdownOnlyAddsPredicate", predicateOk);
        armCheck.put("projectionOnlyNarrowsColumns", projectionOk);
        if (!predicateOk) {
            throw new IllegalStateException(combo.label() + " 轮 " + round
                    + " 受控臂谓词口径不符（PUSHDOWN 应且仅应新增严格小于谓词）: " + armCheck);
        }
        if (!projectionOk) {
            throw new IllegalStateException(combo.label() + " 轮 " + round
                    + " 受控臂投影口径不符（PROJECTION 应且仅应收窄列）: " + armCheck);
        }
        r.put("armSqlCheck", armCheck);
        return r;
    }

    /** 控制台只打一行紧凑摘要（完整明细在 JSON 里；原始日志按行可读、体积可控）。 */
    @SuppressWarnings("unchecked")
    private static String summarize(Map<String, Object> m) {
        Map<String, Map<String, Object>> arms = (Map<String, Map<String, Object>>) m.get("arms");
        Map<String, Object> deltas = (Map<String, Object>) m.get("deltas");
        StringBuilder sb = new StringBuilder();
        sb.append(m.get("combo")).append(" r").append(m.get("round"))
                .append(" order=").append(m.get("armOrder"))
                .append(" prodFirst=").append(((Map<String, Object>) m.get("prodFirst")).get("meanMillis"))
                .append(" prodLast=").append(((Map<String, Object>) m.get("prodLast")).get("meanMillis"));
        for (String key : List.of("OLD", "PUSHDOWN", "PROJECTION")) {
            sb.append(' ').append(key).append('=').append(arms.get(key).get("meanMillis"));
        }
        sb.append(" ratios(prodFirst) OLD=").append(deltas.get("OLDMeanRatioVsProdFirst"))
                .append(" PUSH=").append(deltas.get("PUSHDOWNMeanRatioVsProdFirst"))
                .append(" PROJ=").append(deltas.get("PROJECTIONMeanRatioVsProdFirst"))
                .append(" prodLastVsFirst=").append(deltas.get("prodLastVsFirstRatio"));
        return sb.toString();
    }

    private static Map<String, Object> probe(Map<String, Map<String, Object>> instrumented, Mode mode) {
        return instrumented.get(mode.name());
    }

    // ==================== 受控副本路径（与生产逐句一致，仅查询口径按 mode 切换） ====================

    /**
     * test-only 受控副本：逐句镜像 {@code MakeupService.listEligibleStudents}。
     * 唯一差异是答卷查询按 {@link Mode} 切换（OLD 与生产完全一致）。
     */
    private List<MakeupCandidateItem> runPath(long examId, BigDecimal passLine, Mode mode) {
        Map<Long, String> eligible = new LinkedHashMap<>();
        absenceMapper.selectList(Wrappers.<ExamAbsence>lambdaQuery()
                        .eq(ExamAbsence::getExamId, examId))
                .forEach(a -> eligible.put(a.getStudentId(), MakeupCandidateItem.REASON_ABSENT));

        if (passLine != null) {
            gradingQuery(examId, passLine, mode).stream()
                    .filter(g -> g.getTotalScore().compareTo(passLine) < 0)
                    .forEach(g -> eligible.put(g.getStudentId(), MakeupCandidateItem.REASON_BELOW_LINE));
        }
        if (eligible.isEmpty()) {
            return List.of();
        }
        List<Long> ids = eligible.keySet().stream().toList();
        Map<Long, User> users = userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return ids.stream().map(id -> {
            User user = users.get(id);
            return new MakeupCandidateItem(id, user == null ? null : user.getName(), eligible.get(id));
        }).toList();
    }

    /** 答卷查询：OLD = 生产原句；PUSHDOWN = 仅加严格小于谓词；PROJECTION = 仅窄列投影。 */
    private List<GradingSubmission> gradingQuery(long examId, BigDecimal passLine, Mode mode) {
        return switch (mode) {
            case OLD -> gradingMapper.selectList(Wrappers.<GradingSubmission>lambdaQuery()
                    .eq(GradingSubmission::getExamId, examId)
                    .isNotNull(GradingSubmission::getTotalScore));
            case PUSHDOWN -> gradingMapper.selectList(Wrappers.<GradingSubmission>lambdaQuery()
                    .eq(GradingSubmission::getExamId, examId)
                    .isNotNull(GradingSubmission::getTotalScore)
                    .lt(GradingSubmission::getTotalScore, passLine));
            case PROJECTION -> gradingMapper.selectList(Wrappers.<GradingSubmission>lambdaQuery()
                    .select(GradingSubmission::getId, GradingSubmission::getStudentId,
                            GradingSubmission::getTotalScore)
                    .eq(GradingSubmission::getExamId, examId)
                    .isNotNull(GradingSubmission::getTotalScore));
        };
    }

    // ==================== 请求级批量（单线程顺序） ====================

    private record BatchResult(List<MakeupCandidateItem> items, Map<String, Object> metrics, String canonical) {
    }

    private BatchResult batchRequest(long examId, BigDecimal passLine, Mode mode) {
        long[] nanos = new long[BATCH];
        long allocTotal = 0;
        List<MakeupCandidateItem> last = List.of();
        for (int i = 0; i < BATCH; i++) {
            long a0 = allocated();
            long t0 = System.nanoTime();
            last = mode == null
                    ? makeupService.listEligibleStudents(examId, passLine)
                    : runPath(examId, passLine, mode);
            nanos[i] = System.nanoTime() - t0;
            allocTotal += allocated() - a0;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("iterations", BATCH);
        m.put("meanMillis", round3(mean(nanos) / 1e6));
        m.put("p50Millis", round3(percentile(nanos, 0.50) / 1e6));
        m.put("p99Millis", round3(percentile(nanos, 0.99) / 1e6));
        m.put("minMillis", round3(min(nanos) / 1e6));
        m.put("maxMillis", round3(max(nanos) / 1e6));
        m.put("throughputRps", round3(1e9 / mean(nanos)));
        m.put("allocatedMBMean", round3(allocTotal / (double) BATCH / 1048576.0));
        return new BatchResult(last, m, canonical(last));
    }

    /** 8 线程并发批量：窗口内总调用数 / 窗口时长；工作线程各自持有登录上下文。 */
    private Map<String, Object> batchConcurrent(long examId, BigDecimal passLine, Mode mode) {
        ExecutorService pool = Executors.newFixedThreadPool(CONC_THREADS);
        CountDownLatch ready = new CountDownLatch(CONC_THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < CONC_THREADS; t++) {
                futures.add(pool.submit(() -> {
                    LoginUser user = loginUser(OTHER_USER_ID, 3);
                    List<Long> samples = new ArrayList<>(CONC_PER_THREAD);
                    ready.countDown();
                    start.await();
                    SecurityUtil.set(user);
                    try {
                        for (int i = 0; i < CONC_PER_THREAD; i++) {
                            long t0 = System.nanoTime();
                            runPath(examId, passLine, mode);
                            samples.add(System.nanoTime() - t0);
                        }
                    } finally {
                        SecurityUtil.clear();
                    }
                    return samples;
                }));
            }
            ready.await();
            long wallStart = System.nanoTime();
            start.countDown();
            List<Long> all = new ArrayList<>();
            for (Future<List<Long>> f : futures) {
                all.addAll(f.get(120, TimeUnit.SECONDS));
            }
            long wallNanos = System.nanoTime() - wallStart;
            long[] arr = all.stream().mapToLong(Long::longValue).toArray();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("threads", CONC_THREADS);
            m.put("callsPerThread", CONC_PER_THREAD);
            m.put("totalCalls", arr.length);
            m.put("wallMillis", round3(wallNanos / 1e6));
            m.put("meanMillis", round3(mean(arr) / 1e6));
            m.put("p99Millis", round3(percentile(arr, 0.99) / 1e6));
            m.put("throughputRps", round3(arr.length * 1e9 / wallNanos));
            return m;
        } catch (Exception e) {
            throw new IllegalStateException("并发臂执行失败: " + e, e);
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 分相位探针（额外一遍完整路径 + SQL 捕获） ====================

    private Map<String, Object> runInstrumented(long examId, BigDecimal passLine, Mode mode) {
        SqlCapture.reset();
        SqlCapture.enabled = true;
        List<Map<String, Object>> phases = new ArrayList<>();
        long wallStart = System.nanoTime();
        long wallAllocStart = allocated();
        try {
            // 相位 1：归属校验读考试
            long a = allocated();
            long t = System.nanoTime();
            Exam exam = examMapper.selectById(examId);
            phases.add(phase("ownerLoad", System.nanoTime() - t, allocated() - a));

            // 相位 2：缺考查询
            a = allocated();
            t = System.nanoTime();
            List<ExamAbsence> absences = absenceMapper.selectList(Wrappers.<ExamAbsence>lambdaQuery()
                    .eq(ExamAbsence::getExamId, examId));
            long absenceNanos = System.nanoTime() - t;
            long absenceAlloc = allocated() - a;
            phases.add(phase("absenceLoad", absenceNanos, absenceAlloc));

            // 相位 3：答卷查询（按 mode）
            a = allocated();
            t = System.nanoTime();
            List<GradingSubmission> rows = gradingQuery(examId, passLine, mode);
            long gradingNanos = System.nanoTime() - t;
            long gradingAlloc = allocated() - a;
            long answersChars = rows.stream()
                    .mapToLong(g -> g.getAnswers() == null ? 0 : g.getAnswers().length()).sum();
            long answersCharsMax = rows.stream()
                    .mapToLong(g -> g.getAnswers() == null ? 0 : g.getAnswers().length()).max().orElse(0);
            phases.add(phase("gradingSelect", gradingNanos, gradingAlloc));

            // 相位 4：Java 过滤（与生产过程同表达式，含 LinkedHashMap put 覆盖语义）
            Map<Long, String> eligible = new LinkedHashMap<>();
            a = allocated();
            t = System.nanoTime();
            for (ExamAbsence absence : absences) {
                eligible.put(absence.getStudentId(), MakeupCandidateItem.REASON_ABSENT);
            }
            long kept = 0;
            for (GradingSubmission g : rows) {
                if (g.getTotalScore().compareTo(passLine) < 0) {
                    eligible.put(g.getStudentId(), MakeupCandidateItem.REASON_BELOW_LINE);
                    kept++;
                }
            }
            long filterNanos = System.nanoTime() - t;
            long filterAlloc = allocated() - a;
            phases.add(phase("absencePutAndJavaFilter", filterNanos, filterAlloc));

            // 相位 5：姓名批量装载
            a = allocated();
            t = System.nanoTime();
            List<MakeupCandidateItem> items = List.of();
            long usersRows = 0;
            if (!eligible.isEmpty()) {
                List<Long> ids = eligible.keySet().stream().toList();
                Map<Long, User> users = userMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
                usersRows = users.size();
                items = ids.stream().map(id -> {
                    User user = users.get(id);
                    return new MakeupCandidateItem(id, user == null ? null : user.getName(), eligible.get(id));
                }).toList();
            }
            phases.add(phase("nameLoad", System.nanoTime() - t, allocated() - a));

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", mode.name());
            m.put("probeNote", "本相位运行是额外一遍完整路径（探针，含 SQL 捕获插桩开销），不并入请求级批量样本");
            m.put("wallMillis", round3((System.nanoTime() - wallStart) / 1e6));
            m.put("allocatedMB", round3((allocated() - wallAllocStart) / 1048576.0));
            m.put("phases", phases);
            m.put("gradingRowsOut", rows.size());
            m.put("gradingAnswersCharsTotal", answersChars);
            m.put("gradingAnswersCharsMax", answersCharsMax);
            m.put("gradingKeptRows", kept);
            m.put("absenceRows", absences.size());
            m.put("nameRows", usersRows);
            m.put("eligibleSize", items.size());
            m.put("canonicalDigest", digestOf(canonical(items)));
            m.put("examLoaded", exam != null);
            m.put("sql", SqlCapture.snapshot());
            return m;
        } finally {
            SqlCapture.enabled = false;
        }
    }

    // ==================== 语义 oracle ====================

    private List<Map<String, Object>> runSemanticsOracle() {
        List<Map<String, Object>> cases = new ArrayList<>();

        // 期望串由与夹具同一组 ID 常量拼出（EDGE_ID_BASE + n），不写字面数字，避免夹具与期望漂移
        String onlyAbsent = String.join(";",
                edgeItem(1, MakeupCandidateItem.REASON_ABSENT, "边缘-A"),
                edgeItem(6, MakeupCandidateItem.REASON_ABSENT, "边缘-F"),
                edgeItem(8, MakeupCandidateItem.REASON_ABSENT, "<null>"));

        // passLine=60：严格小于才纳；等于线的 60.0/60.00 与总分 null 均排除；F/H 同 ID 低分覆盖缺考
        String atLine60 = String.join(";",
                edgeItem(1, MakeupCandidateItem.REASON_ABSENT, "边缘-A"),
                edgeItem(2, MakeupCandidateItem.REASON_BELOW_LINE, "边缘-B"),
                edgeItem(6, MakeupCandidateItem.REASON_BELOW_LINE, "边缘-F"),
                edgeItem(7, MakeupCandidateItem.REASON_BELOW_LINE, "<null>"),
                edgeItem(8, MakeupCandidateItem.REASON_BELOW_LINE, "<null>"),
                edgeItem(9, MakeupCandidateItem.REASON_BELOW_LINE, "边缘-I"));

        // passLine=1000：全部非空总分都在线下（含等于 60 的 C/E），null 仍排除
        String allNonBlank = String.join(";",
                edgeItem(1, MakeupCandidateItem.REASON_ABSENT, "边缘-A"),
                edgeItem(2, MakeupCandidateItem.REASON_BELOW_LINE, "边缘-B"),
                edgeItem(3, MakeupCandidateItem.REASON_BELOW_LINE, "边缘-C"),
                edgeItem(5, MakeupCandidateItem.REASON_BELOW_LINE, "边缘-E"),
                edgeItem(6, MakeupCandidateItem.REASON_BELOW_LINE, "边缘-F"),
                edgeItem(7, MakeupCandidateItem.REASON_BELOW_LINE, "<null>"),
                edgeItem(8, MakeupCandidateItem.REASON_BELOW_LINE, "<null>"),
                edgeItem(9, MakeupCandidateItem.REASON_BELOW_LINE, "边缘-I"));

        // 1) passLine = null：只返回缺考，不发起低分口径比较
        cases.add(semanticCase("passLine=null 只返回缺考", EDGE_EXAM_ID, null, onlyAbsent, true));

        // 2) 负值及格线：无任何成绩低于 -1 → 只剩缺考（低分分支执行但不纳行）
        cases.add(semanticCase("passLine=-1 负值不纳低分", EDGE_EXAM_ID, new BigDecimal("-1"),
                onlyAbsent, true));

        // 3) 边界与集合：等于线的 60.0/60.00 不纳入（严格小于），null 不纳入，同 ID 低分覆盖缺考
        cases.add(semanticCase("passLine=60 严格小于 + 同 ID 覆盖 + 姓名缺失/软删", EDGE_EXAM_ID, new BigDecimal("60"),
                atLine60, true));
        cases.add(semanticCase("passLine=60.00 scale 2 结果同", EDGE_EXAM_ID, new BigDecimal("60.00"),
                atLine60, true));
        cases.add(semanticCase("passLine=60.000 scale 3 结果同", EDGE_EXAM_ID, new BigDecimal("60.000"),
                atLine60, true));

        // 4) 高及格线：全部非空总分都在线下（null 仍排除）
        cases.add(semanticCase("passLine=1000 全非空总分在线下、null 排除", EDGE_EXAM_ID, new BigDecimal("1000"),
                allNonBlank, true));

        // 5) 空候选短路：不发起姓名批量查询
        cases.add(semanticCase("空候选（无缺考、全部线上）不发起姓名查询", EMPTY_EXAM_ID, PASS_LINE,
                "", false));
        cases.add(semanticCase("空候选（passLine=null 且无缺考）不发起姓名查询", EMPTY_EXAM_ID, null,
                "", false));

        // 6) 权限边界：403（非归属教师）/ 404（不存在、已软删）/ ADMIN 放行（同一结果集）
        cases.add(permissionCase("非归属教师 403", EDGE_EXAM_ID, PASS_LINE, loginUser(OTHER_USER_ID, 2), 403));
        cases.add(permissionCase("不存在考试 404", MISSING_EXAM_ID, PASS_LINE, loginUser(OTHER_USER_ID, 2), 404));
        cases.add(permissionCase("已软删考试 404", DELETED_EXAM_ID, PASS_LINE, loginUser(OTHER_USER_ID, 2), 404));
        cases.add(semanticCase("ADMIN 放行（不同归属人）", EDGE_EXAM_ID, PASS_LINE, atLine60, true));

        List<String> mismatches = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            if (!Boolean.TRUE.equals(c.get("match"))) {
                mismatches.add(c.get("case") + " expected=" + c.get("expectedCanonical")
                        + " actual=" + c.get("actualCanonical") + " extra=" + c.get("detail"));
            }
        }
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException("旧行为语义 oracle 不成立（证据无效）: " + mismatches);
        }
        return cases;
    }

    /** 边缘夹具期望条目：{@code (EDGE_ID_BASE + n)|reason|name}，name 用 {@code <null>} 表示空姓名。 */
    private static String edgeItem(int n, String reason, String name) {
        return (EDGE_ID_BASE + n) + "|" + reason + "|" + name;
    }

    /** 语义用例：期望 (studentId|reason|name) 排序集合相等；{@code expectNameQuery} 断言是否发起姓名查询。 */
    private Map<String, Object> semanticCase(String label, long examId, BigDecimal passLine,
                                             String expectedCanonical, boolean expectNameQuery) {
        SqlCapture.reset();
        SqlCapture.enabled = true;
        List<MakeupCandidateItem> actual;
        try {
            actual = makeupService.listEligibleStudents(examId, passLine);
        } finally {
            SqlCapture.enabled = false;
        }
        long nameQueries = SqlCapture.snapshot().stream()
                .filter(s -> String.valueOf(s.get("id")).contains("UserMapper"))
                .count();
        String actualCanonical = canonical(actual);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("case", label);
        m.put("examId", examId);
        m.put("passLine", passLine == null ? "<null>" : passLine.toPlainString());
        m.put("expectedCanonical", expectedCanonical);
        m.put("actualCanonical", actualCanonical);
        m.put("nameQueries", nameQueries);
        m.put("match", expectedCanonical.equals(actualCanonical) && (expectNameQuery == (nameQueries > 0)));
        m.put("detail", expectNameQuery == (nameQueries > 0) ? "" : "姓名查询次数与期望不符");
        return m;
    }

    /** 权限用例：断言抛出的业务异常码（403/404）。 */
    private Map<String, Object> permissionCase(String label, long examId, BigDecimal passLine,
                                               LoginUser operator, int expectedCode) {
        SecurityUtil.set(operator);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("case", label);
        m.put("examId", examId);
        m.put("passLine", passLine == null ? "<null>" : passLine.toPlainString());
        try {
            makeupService.listEligibleStudents(examId, passLine);
            m.put("expectedCanonical", "BusinessException(" + expectedCode + ")");
            m.put("actualCanonical", "<未抛异常>");
            m.put("match", false);
        } catch (BusinessException e) {
            m.put("expectedCanonical", "BusinessException(" + expectedCode + ")");
            m.put("actualCanonical", "BusinessException(" + e.getCode() + ")");
            m.put("match", e.getCode() == expectedCode);
        } finally {
            SecurityUtil.set(loginUser(OTHER_USER_ID, 3));
        }
        return m;
    }

    /** 由夹具参数独立推算每 combo 的期望集合，与生产结果对账（当前生产 = 旧实现）。 */
    private Map<String, Object> validateComboOracle(Combo combo) {
        long examId = examIdOf(combo);
        List<MakeupCandidateItem> actual = makeupService.listEligibleStudents(examId, PASS_LINE);
        int below = belowCount(combo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("combo", combo.label());
        m.put("expectedBelowLine", below);
        m.put("expectedAbsent", combo.absentCount());
        m.put("expectedSize", below + combo.absentCount());
        m.put("actualSize", actual.size());
        m.put("actualReasonCounts", reasonCounts(actual));
        boolean countMatch = actual.size() == below + combo.absentCount();
        long belowLine = actual.stream().filter(i -> MakeupCandidateItem.REASON_BELOW_LINE.equals(i.reason())).count();
        long absent = actual.stream().filter(i -> MakeupCandidateItem.REASON_ABSENT.equals(i.reason())).count();
        boolean reasonsMatch = belowLine == below && absent == combo.absentCount();
        boolean namesPresent = actual.stream().allMatch(i -> i.studentName() != null && !i.studentName().isBlank());
        m.put("oracleMatch", countMatch && reasonsMatch && namesPresent);
        m.put("namesPresent", namesPresent);
        return m;
    }

    // ==================== 夹具 ====================

    private void preparePerfFixtures() {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (int ci = 0; ci < COMBOS.size(); ci++) {
            Combo combo = COMBOS.get(ci);
            long examId = examIdOf(combo);
            deleteExamRows(examId);
            // users 主键与 uk_users_username 全局唯一：先清空本 combo 独占 ID 块（幂等，块间不重叠）
            jdbc.update("DELETE FROM users WHERE id BETWEEN ? AND ?",
                    comboIdBase(combo), comboIdBase(combo) + COMBO_ID_BLOCK - 1);

            jdbc.update("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes,"
                            + " status, published, created_by, version, is_deleted, created_time, updated_time)"
                            + " VALUES (?,?,?,?,?,60,4,1,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    examId, "补考归因-" + combo.label(), 951_700_000L + ci, now, now, OWNER_ID);

            String answers = answersJsonOf(combo.answersChars());
            int below = belowCount(combo);
            List<Object[]> submissions = new ArrayList<>(combo.submissions());
            List<Object[]> users = new ArrayList<>(combo.submissions() + combo.absentCount());
            for (int i = 0; i < combo.submissions(); i++) {
                long studentId = studentIdOf(combo, i);
                BigDecimal score = i < below ? BELOW_SCORE : ABOVE_SCORE;
                submissions.add(new Object[]{examId, studentId, now, now, now, answers,
                        score, score, score});
                users.add(new Object[]{studentId, "perf_" + ci + "_" + i, "x", "学生-" + ci + "-" + i, 0});
            }
            jdbc.batchUpdate("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                            + " submit_time, answers, status, objective_score, subjective_score, total_score,"
                            + " grading_status, partial_graded, version, created_time, updated_time)"
                            + " VALUES (?,?,?,?,?,?,3,?,?,?,1,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    submissions);

            List<Object[]> absences = new ArrayList<>(combo.absentCount());
            for (int i = 0; i < combo.absentCount(); i++) {
                long studentId = absentIdOf(combo, i);
                absences.add(new Object[]{examId, studentId});
                users.add(new Object[]{studentId, "perf_abs_" + ci + "_" + i, "x", "缺考-" + ci + "-" + i, 0});
            }
            jdbc.batchUpdate("INSERT INTO exam_absence (exam_id, student_id, status, marked_time, created_time)"
                    + " VALUES (?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", absences);
            jdbc.batchUpdate("INSERT INTO users (id, username, password, name, status, must_change_password,"
                            + " is_deleted, created_time, updated_time)"
                            + " VALUES (?,?,?,?,?,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    users.stream().map(u -> new Object[]{u[0], u[1], u[2], u[3], u[4], 0}).toList());
        }
    }

    private void prepareEdgeFixtures() {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        deleteExamRows(EDGE_EXAM_ID);
        deleteExamRows(EMPTY_EXAM_ID);
        deleteExamRows(DELETED_EXAM_ID);
        jdbc.update("DELETE FROM users WHERE id BETWEEN ? AND ?", EDGE_ID_BASE, EDGE_ID_BASE + 99);

        jdbc.update("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes, status,"
                        + " published, created_by, version, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,60,4,1,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                EDGE_EXAM_ID, "补考归因-边缘", 951_700_900L, now, now, OWNER_ID);
        jdbc.update("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes, status,"
                        + " published, created_by, version, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,60,4,1,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                EMPTY_EXAM_ID, "补考归因-空候选", 951_700_901L, now, now, OWNER_ID);
        // 软删考试：selectById 因 @TableLogic 过滤 → 应得 404
        jdbc.update("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes, status,"
                        + " published, created_by, version, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,60,4,1,?,0,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                DELETED_EXAM_ID, "补考归因-已软删", 951_700_902L, now, now, OWNER_ID);

        // A 缺考无答卷；B 59.9 线下；C 60.0 等于线；D 总分 null；E 60.00 等于线（scale 2）；
        // F 缺考 + 40.0（同 ID 覆盖）；G 10.0 无 users 行；H 缺考 + 20.0 且 users 软删；I 30.0 正常
        Object[][] rows = {
                {EDGE_ID_BASE + 2, new BigDecimal("59.9"), "边缘-B", 0},
                {EDGE_ID_BASE + 3, new BigDecimal("60.0"), "边缘-C", 0},
                {EDGE_ID_BASE + 4, null, "边缘-D", 0},
                {EDGE_ID_BASE + 5, new BigDecimal("60.00"), "边缘-E", 0},
                {EDGE_ID_BASE + 6, new BigDecimal("40.0"), "边缘-F", 0},
                {EDGE_ID_BASE + 7, new BigDecimal("10.0"), null, 0},
                {EDGE_ID_BASE + 8, new BigDecimal("20.0"), "边缘-H", 1},
                {EDGE_ID_BASE + 9, new BigDecimal("30.0"), "边缘-I", 0},
        };
        for (Object[] row : rows) {
            jdbc.update("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                            + " submit_time, answers, status, objective_score, subjective_score, total_score,"
                            + " grading_status, partial_graded, version, created_time, updated_time)"
                            + " VALUES (?,?,?,?,?,?,3,?,?,?,1,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    EDGE_EXAM_ID, (Long) row[0], now, now, now, "{\"1001\":\"A\"}",
                    row[1], row[1], row[1]);
            if (row[2] != null) {
                jdbc.update("INSERT INTO users (id, username, password, name, status, must_change_password,"
                                + " is_deleted, created_time, updated_time)"
                                + " VALUES (?,?,?,?,0,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                        (Long) row[0], "edge_" + row[0], "x", row[2], row[3]);
            }
        }
        // A、F、H 缺考；A 有 users 行；H 的 users 行已软删（上方 is_deleted=1）
        jdbc.update("INSERT INTO exam_absence (exam_id, student_id, status, marked_time, created_time)"
                + " VALUES (?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", EDGE_EXAM_ID, EDGE_ID_BASE + 1);
        jdbc.update("INSERT INTO exam_absence (exam_id, student_id, status, marked_time, created_time)"
                + " VALUES (?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", EDGE_EXAM_ID, EDGE_ID_BASE + 6);
        jdbc.update("INSERT INTO exam_absence (exam_id, student_id, status, marked_time, created_time)"
                + " VALUES (?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", EDGE_EXAM_ID, EDGE_ID_BASE + 8);
        jdbc.update("INSERT INTO users (id, username, password, name, status, must_change_password, is_deleted,"
                        + " created_time, updated_time)"
                        + " VALUES (?,?,?,?,0,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                EDGE_ID_BASE + 1, "edge_" + (EDGE_ID_BASE + 1), "x", "边缘-A");

        // 空候选考试：无缺考、唯一答卷在线上
        jdbc.update("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                        + " submit_time, answers, status, objective_score, subjective_score, total_score,"
                        + " grading_status, partial_graded, version, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,3,?,?,?,1,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                EMPTY_EXAM_ID, EDGE_ID_BASE + 20, now, now, now, "{\"1001\":\"A\"}",
                ABOVE_SCORE, ABOVE_SCORE, ABOVE_SCORE);
    }

    private void deleteExamRows(long examId) {
        jdbc.update("DELETE FROM exam_submissions WHERE exam_id = ?", examId);
        jdbc.update("DELETE FROM exam_absence WHERE exam_id = ?", examId);
        jdbc.update("DELETE FROM exams WHERE id = ?", examId);
    }

    // ==================== 工具 ====================

    private static LoginUser loginUser(long id, int roleLevel) {
        LoginUser user = new LoginUser();
        user.setId(id);
        user.setUsername("measure-" + id);
        user.setRoleLevel(roleLevel);
        return user;
    }

    private static long examIdOf(Combo combo) {
        return EXAM_ID_BASE + COMBOS.indexOf(combo);
    }

    private static long comboIdBase(Combo combo) {
        return PERF_ID_BASE + (long) COMBOS.indexOf(combo) * COMBO_ID_BLOCK;
    }

    private static long studentIdOf(Combo combo, int i) {
        return comboIdBase(combo) + i;
    }

    private static long absentIdOf(Combo combo, int i) {
        return comboIdBase(combo) + ABSENT_BLOCK_OFFSET + i;
    }

    private static int belowCount(Combo combo) {
        return (int) Math.round(combo.submissions() * combo.belowRatio());
    }

    /** 定长 ASCII JSON 答卷：长度恰为 chars（端点只整体搬运字符串，不解析内容）。 */
    private static String answersJsonOf(int chars) {
        StringBuilder sb = new StringBuilder("{\"1001\":\"");
        while (sb.length() < chars - 2) {
            sb.append('A');
        }
        return sb.append("\"}").toString();
    }

    /** 结果 canonical 形式：(studentId|reason|name) 排序后拼接 —— 集合语义，不假设顺序。 */
    private static String canonical(List<MakeupCandidateItem> items) {
        return items.stream()
                .map(i -> i.studentId() + "|" + i.reason() + "|" + (i.studentName() == null ? "<null>" : i.studentName()))
                .sorted()
                .collect(Collectors.joining(";"));
    }

    private static Map<String, Object> reasonCounts(List<MakeupCandidateItem> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(MakeupCandidateItem.REASON_ABSENT,
                items.stream().filter(i -> MakeupCandidateItem.REASON_ABSENT.equals(i.reason())).count());
        m.put(MakeupCandidateItem.REASON_BELOW_LINE,
                items.stream().filter(i -> MakeupCandidateItem.REASON_BELOW_LINE.equals(i.reason())).count());
        return m;
    }

    /** 从探针结果 map 里取出答卷查询（exam_submissions）的那条 SQL 记录。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> gradingSqlOf(Map<String, Object> instrumented) {
        List<Map<String, Object>> sqls = (List<Map<String, Object>>) instrumented.get("sql");
        if (sqls == null) {
            return null;
        }
        for (Map<String, Object> s : sqls) {
            if (String.valueOf(s.get("id")).contains("GradingSubmissionMapper")
                    && String.valueOf(s.get("sql")).contains("exam_submissions")) {
                return s;
            }
        }
        return null;
    }

    private static String sqlText(Map<String, Object> sqlRow) {
        return String.valueOf(sqlRow.get("sql"));
    }

    /** 是否带 total_score 的严格小于谓词（去掉空白后判，兼容 {@code < ?} 与 {@code <?} 形态）。 */
    private static boolean hasLtPredicate(String sql) {
        return sql.replaceAll("\\s+", "").contains("total_score<?");
    }

    /** 是否取回 answers 列（全列取回为真，窄列投影为假）。 */
    private static boolean hasAnswersColumn(String sql) {
        return sql.replaceAll("\\s+", "").contains("answers");
    }

    private static Map<String, Object> phase(String name, long nanos, long allocBytes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", name);
        m.put("millis", round3(nanos / 1e6));
        m.put("allocatedMB", allocBytes < 0 ? -1 : round3(allocBytes / 1048576.0));
        return m;
    }

    private static double ratio(Map<String, Object> prod, Map<String, Object> arm) {
        double p = ((Number) prod.get("meanMillis")).doubleValue();
        double a = ((Number) arm.get("meanMillis")).doubleValue();
        return a <= 0 ? -1 : round3(p / a);
    }

    /** 统一取数：JSON 里 int/long 混放，统一按 Number 走，避免 Integer→Long 直接转型。 */
    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    /** canonical 集合的短摘要（证据里存摘要不存全串：3000 人规模的全串会让 JSON 膨胀到数 MB）。 */
    private static String digestOf(String canonical) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static double mean(long[] values) {
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return sum / (double) values.length;
    }

    private static long min(long[] values) {
        long m = Long.MAX_VALUE;
        for (long v : values) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static long max(long[] values) {
        long m = Long.MIN_VALUE;
        for (long v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static long percentile(long[] values, double p) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static final com.sun.management.ThreadMXBean ALLOC_BEAN;

    static {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean sun = null;
        if (bean instanceof com.sun.management.ThreadMXBean s && s.isThreadAllocatedMemorySupported()) {
            s.setThreadAllocatedMemoryEnabled(true);
            sun = s;
        }
        ALLOC_BEAN = sun;
    }

    private static long allocated() {
        return ALLOC_BEAN == null ? -1 : ALLOC_BEAN.getCurrentThreadAllocatedBytes();
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private Map<String, Object> loadParams() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("passLine", PASS_LINE.toPlainString());
        m.put("belowScore", BELOW_SCORE.toPlainString());
        m.put("aboveScore", ABOVE_SCORE.toPlainString());
        m.put("batch", BATCH);
        m.put("concThreads", CONC_THREADS);
        m.put("concPerThread", CONC_PER_THREAD);
        m.put("rounds", ROUNDS);
        m.put("warmup", WARMUP);
        m.put("armOrderPerRound", java.util.Arrays.stream(ARM_ORDERS)
                .map(o -> java.util.Arrays.stream(o).map(Enum::name).toList()).toList());
        m.put("combos", COMBOS.stream().map(c -> Map.of(
                "label", c.label(), "submissions", c.submissions(), "belowRatio", c.belowRatio(),
                "absentCount", c.absentCount(), "answersChars", c.answersChars(), "note", c.note())).toList());
        m.put("allocMeasurement", "com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes 差分");
        m.put("answersMeasurement", "Java 侧统计返回对象的 answers 字符数（ASCII 夹具 1 字符=1 字节），不是 MySQL 网络传输量");
        m.put("sqlMeasurement", "MyBatis Executor.query Interceptor：仅探针窗口启用，记录 SQL 原文/耗时/返回行数");
        m.put("phaseSplit", "ownerLoad → absenceLoad → gradingSelect → absencePutAndJavaFilter → nameLoad；各相位含 JDBC/映射，墙钟非纯 CPU");
        m.put("probeOverhead", "分相位探针与两受控臂都是额外一遍完整路径，单列；请求级批量样本在捕获关闭状态下采集");
        m.put("orderBiasControl", "臂顺序逐轮轮转；每轮臂序列前后各夹一次生产入口，prodFirst/prodLast 的比值是该形状的"
                + "顺序漂移读数（不改行为），OLD 副本臂与两侧生产样本的比值是等价工作量的噪声带");
        m.put("noCrossWindowClaim", "不跨轮/跨窗口相减相除求占比；比值为同轮 mean 商；墙钟含 GC/JIT/调度");
        m.put("coldStartExcluded", "Spring 启动与数据准备在测量窗口外；每 combo/臂预热 " + WARMUP + " 次不计入");
        return m;
    }
}
