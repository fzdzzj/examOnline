package com.exam.monitoring.measure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.service.JwtUtil;
import com.exam.monitoring.service.OnlinePresenceService;
import com.exam.submission.dto.AbnormalBehaviorStat;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamBehaviorLogMapper;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.taking.service.ExamDraftService;
import com.exam.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 监考总览（MonitorService.overview）性能归因隔离测量工具
 * —— 变更 {@code update-monitor-overview-submission-projection} 阶段 1/2（以及 GO 后的阶段 4 复测）。
 *
 * <p><b>为什么类名以 IT 结尾</b>：Surefire 默认只收 {@code *Test}/{@code *Tests}/{@code Test*}/{@code *TestCase}
 * 四类命名，本类名以 {@code IT} 结尾<b>不会</b>进入 {@code mvnw.cmd clean test} 全量门禁，只有显式
 * {@code -Dtest=MonitorAttributionMeasureIT} 才运行。它只做测量、不构成任何业务断言基线。
 *
 * <p><b>一次运行 = 一轮</b>（同一 label 下逐轮跑三次，用于比较轮间波动）。同负载做两件事：
 * <ol>
 *   <li><b>请求内分账</b>：分别单独计时「主答卷取数（全列）」「主答卷取数（仅短列投影）」「学生姓名批查」
 *       「窄读一份个人快照」「逐人草稿 GET（N 次 Redis 往返）」「仅原始草稿 MGET」
 *       「完整草稿批取（MGET + 逐值解析 + 学生关联，并与逐人 get 断言等价）」
 *       「在线状态 MGET」「异常聚合 SQL」，以及 MockMvc 端到端 {@code GET /api/exams/{id}/monitor/overview}
 *       （并发 1 与 8）的 p50/p95/p99/mean/吞吐、响应字节、堆峰值与 Redis 命令数；</li>
 *   <li><b>JFR 逐帧归因</b>：{@code jdk.ExecutionSample} 采样中栈内含 MonitorService / ExamDraftService /
 *       OnlinePresenceService / Redis 客户端 / H2 / MyBatis / Jackson 帧的比例 + 栈顶帧直方图，
 *       以及 {@code jdk.CPULoad} / {@code jdk.GCPhasePause} 汇总。</li>
 * </ol>
 *
 * <p><b>不触碰生产代码</b>：不改 {@code src/main}、SQL、Mapper、schema、JVM/线程池配置、Redis 协议；
 * 「仅短列投影」这一臂用 MyBatis-Plus 的 {@code select(...)} 在测量侧模拟拟实施的取数边界，
 * 不新增生产方法。
 *
 * <p><b>环境隔离</b>：{@code @ActiveProfiles("test")} → 隔离 H2 内存库（{@code mem:exam}）+ Redis <b>db15</b>；
 * 本机 6379 被其它项目占用，测量须用<b>专用实例</b>：{@code REDIS_PORT}（application-test.yml 的占位符）指向
 * 临时 Redis（如 {@code docker run -d --name ... -p 6390:6379 redis:7.2-alpine}），跑完即删；不写共享 dev 数据。
 * 不启本项目 docker-compose、不做跨宿主压测。仅删除本工具自建的
 * {@code exam:draft:{examId}:*} / {@code exam:monitor:online:{examId}:*} 键，不动其它键。
 *
 * <p><b>口径提醒</b>：本工具跑隔离 H2 + MockMvc <b>同进程</b>，字段「字节量」是按生成内容估算的<b>估计值</b>，
 * 不是真实网络传输字节；各分账臂与端到端的<b>分母不同</b>（组件臂是单独计时、端到端含安全过滤链与序列化），
 * 份额只作相互佐证，不可直接等值相加。
 *
 * <p><b>运行</b>（仓库根，Windows）：
 * <pre>
 * mvnw.cmd -q test -Dtest=MonitorAttributionMeasureIT -DfailIfNoTests=false ^
 *     -Dmeasure.rev=&lt;sha7&gt; -Dmeasure.label=old-run1 -Dmeasure.out=target/measure
 * </pre>
 * 机器可读结果写到 {@code ${measure.out}/monitor-attribution-${measure.label}.json}，
 * JFR 落到 {@code ${measure.out}/jfr/}（体积大，不入版本库，只为现场复看）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("监考总览归因隔离测量（仅显式 -Dtest 运行；不进 Surefire 全量门禁）")
class MonitorAttributionMeasureIT {

    // ==================== 固定口径（跨 revision 逐次可比） ====================

    /** 单场学生数（班级规模口径）。 */
    private static final int N_STUDENTS = 200;
    /** 状态比例：进行中 60% / 已交卷 25% / 已批改 15%（与真实考场忙时同形）。 */
    private static final int IN_PROGRESS_COUNT = 120;
    private static final int SUBMITTED_COUNT = 50;
    /** 进行中学生中「在线」比例（4/5）：用于在线/离线计数与 MGET。 */
    private static final int ONLINE_EVERY = 5;
    private static final int ONLINE_OFFSET = 4;
    /** 异常行为造数：i%10==0 → 严重度{1,1,3}；i%5==0 其余 → {1,2}；其余无事件。 */
    private static final int ABNORMAL_STRIDE = 5;
    /** 进行中学生的草稿答案条目数（进度 = 12/题数）。 */
    private static final int DRAFT_ANSWER_ENTRIES = 12;

    /** 数据变体：题数梯度 → 个人快照（paper_json）字节规模梯度，用于「长字段规模敏感性」。 */
    private static final String VARIANT_REPRESENTATIVE = "rep";
    private static final int VARIANT_REP_QCOUNT = 40;   // ≈ 15KB 快照（40 题 × 约 370 字符）
    private static final String VARIANT_SMALL = "small";
    private static final int VARIANT_SMALL_QCOUNT = 5;  // ≈ 1.9KB 快照

    /** 考试 ID 段位（避开 API 造数的自增小 ID）。 */
    private static final long EXAM_ID_BASE = 941_000_000L;
    private static final long STUDENT_ID_BASE = 941_000_000L;
    private static final long PLACEHOLDER_PAPER_ID = 941_000_001L;

    /** 组件臂：预热与测量次数。 */
    private static final int SQL_WARMUP = 5;
    private static final int SQL_REPS = 20;
    /** 逐人草稿 GET：预热轮数与计时轮数（每轮 = 全部进行中学生各一次）。 */
    private static final int DRAFT_WARMUP_PASSES = 2;
    private static final int DRAFT_PASSES = 10;
    /** 端到端：每臂预热请求数与计时请求数。 */
    private static final int E2E_WARMUP = 10;
    private static final int E2E_REQUESTS = 60;
    private static final int[] E2E_CONCURRENCY_REP = {1, 8};
    private static final int[] E2E_CONCURRENCY_SMALL = {1};

    /** JFR 归因用的类名子串。 */
    private static final Map<String, String> FOCUS_CLASSES = Map.ofEntries(
            Map.entry("MonitorService", "com.exam.monitoring.service.MonitorService"),
            Map.entry("ExamDraftService", "com.exam.taking.service.ExamDraftService"),
            Map.entry("OnlinePresenceService", "com.exam.monitoring.service.OnlinePresenceService"),
            Map.entry("RedisDriver", "io.lettuce"),
            Map.entry("RedisClientOther", "redis.clients"),
            Map.entry("RedisTemplate", "org.springframework.data.redis"),
            Map.entry("H2", "org.h2"),
            Map.entry("Mybatis", "org.apache.ibatis"),
            Map.entry("MybatisPlus", "com.baomidou"),
            Map.entry("Jackson", "com.fasterxml.jackson"));

    private static final String DATA_RULE =
            "隔离 H2 内存库（application-test.yml test profile）+ Redis db15；单场 " + N_STUDENTS + " 份答卷，"
            + "状态比 " + IN_PROGRESS_COUNT + "/" + SUBMITTED_COUNT + "/" + (N_STUDENTS - IN_PROGRESS_COUNT - SUBMITTED_COUNT)
            + "（进行中/已交卷/已批改）；进行中 answers=NULL（真实链路：答案交卷才落库），"
            + "已交卷/已批改 answers 为含少量长文本的 JSON；paper_json 为合法快照（questions 数组长度=题数），"
            + "字符量随题数梯度（rep=" + VARIANT_REP_QCOUNT + " 题，small=" + VARIANT_SMALL_QCOUNT + " 题）；"
            + "进行中学生逐个写入草稿键 exam:draft:{examId}:{sid}（answers " + DRAFT_ANSWER_ENTRIES + " 条），"
            + "其中每 60 个进行中学生里有 2 个分别为「损坏 JSON / 非对象 answers / 缺键 / 空白值」四种形态"
            + "（i%60 = 0/15/30/45，与本变更要守住的语义分支一一对应，批取必须与单份 get 同判）——其余为正常草稿；"
            + (ONLINE_EVERY - ONLINE_OFFSET) + "/" + ONLINE_EVERY + " 写入在线键 exam:monitor:online:{examId}:{sid}"
            + "（TTL 2h，仅为测量稳定，非生产 60s）；异常日志按 i%" + ABNORMAL_STRIDE + " 步长造 severity>=2 聚合；"
            + "学生无 users 行（姓名批查走空结果，与真实同名回退路径等价）；teacher 为真实注册登录教师并拥有该考试。";

    private static final Map<String, String> METRIC_DEFS = Map.ofEntries(
            Map.entry("sqlFull", "主答卷取数（与现有 overview 同语句：exam_id 等值 selectList 全列，含 paper_json/answers）逐次耗时"),
            Map.entry("sqlProjected", "同语句但 select(...) 只取短列（拟实施投影）逐次耗时；同时校验返回行 paper_json/answers 均为 null"),
            Map.entry("namesSql", "resolveNames 的 userMapper.selectBatchIds(N 个学生) 逐次耗时"),
            Map.entry("narrowSnapshot", "只读一份个人快照的窄查询（SELECT paper_json ... LIMIT 1）逐次耗时，即拟实施新增的至多一次取数"),
            Map.entry("draftGetEach", "逐人草稿 GET：全部进行中学生各调一次 draftService.get（含 Redis 往返 + JSON 解析）每轮总耗时与单次分位"),
            Map.entry("draftMGetInfo", "仅原始 multiGet 取回全部进行中学生的草稿键耗时（不解析，只证明网络取数下限）"),
            Map.entry("draftBatchFull", "完整批取＝一次 multiGet + 逐值按 draftService.get 同口径解析 + 按学生下标关联，"
                    + "并逐学生与单份 get 断言结果等价（version/answers/marked/savedTime 全等），每轮总耗时与单次解析分位"),
            Map.entry("draftBatchVsGetEachRatio", "同一轮内 draftGetEach 与 draftBatchFull 的 passMedian 比值（同工具同数据同时刻，可相除）"),
            Map.entry("redisCalls", "Redis INFO commandstats 前后差值，量出「逐人 get 一轮＝N 次 GET 命令」与「批取一轮＝1 次 MGET 命令」"
                    + "以及端到端每请求命令数（server 级全局计数，需确认无其它客户端并发写同一实例）"),
            Map.entry("onlineMGet", "OnlinePresenceService.onlineOf 的一次 MGET 耗时（已批读，不属本提案因素）"),
            Map.entry("abnormalAgg", "ExamBehaviorLogMapper.selectAbnormalStats 的一条 GROUP BY 聚合耗时"),
            Map.entry("e2e", "MockMvc GET /api/exams/{id}/monitor/overview 请求墙钟（含安全过滤链/JSON 序列化/全部组件）"),
            Map.entry("longFieldDelta", "sqlFull 与 sqlProjected 的耗时差 = 载入 paper_json/answers 的可控增量（同语句同数据，仅列集不同）"),
            Map.entry("sharePct", "各分账臂中位耗时 / e2e(并发1) mean 的百分比；分母不同，仅作排序佐证，不可等值相加"),
            Map.entry("fieldChars", "按生成内容估算的 paper_json / answers 字符量（估计值，不是真实网络字节）"),
            Map.entry("jfr", "jdk.ExecutionSample 中栈内含目标类帧的样本占比 + 栈顶帧直方图；与墙钟份额分母不同"));

    // ==================== 依赖 ====================

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ExamSubmissionMapper submissionMapper;
    @Autowired
    private ExamBehaviorLogMapper behaviorLogMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ExamDraftService draftService;
    @Autowired
    private OnlinePresenceService presenceService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JwtUtil jwtUtil;

    private String teacherToken;
    private long teacherId;

    /** 本工具自建的 Redis 键，收尾时逐个删除（不动其它键）。 */
    private final List<String> createdRedisKeys = new ArrayList<>();

    // ==================== 主流程 ====================

    @Test
    @DisplayName("同口径两变体分账测量并落盘机器可读结果")
    void measure() throws Exception {
        String rev = System.getProperty("measure.rev", "unknown");
        String label = System.getProperty("measure.label", "rev" + rev);
        Path outDir = Paths.get(System.getProperty("measure.out", "target/measure"));
        Files.createDirectories(outDir);
        Path jfrDir = outDir.resolve("jfr");
        Files.createDirectories(jfrDir);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", rev);
        result.put("label", label);
        result.put("startedAt", LocalDateTime.now().toString());
        result.put("jdk", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        result.put("vm", System.getProperty("java.vm.name") + " / " + System.getProperty("java.vm.version"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("maxHeapMB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        result.put("redisEnv", Map.of(
                "REDIS_HOST", String.valueOf(System.getenv("REDIS_HOST")),
                "REDIS_PORT", String.valueOf(System.getenv("REDIS_PORT")),
                "effectiveNote", "application-test.yml 的 ${REDIS_HOST:127.0.0.1}/${REDIS_PORT:6379} + db15；"
                        + "专用隔离实例由 REDIS_PORT 注入（不写其它客户端共用的实例）"));
        result.put("dataRule", DATA_RULE);
        result.put("metricDefinitions", METRIC_DEFS);
        result.put("load", loadParams());

        Path wholeJfr = jfrDir.resolve(label + "-whole.jfr");
        Recording whole = newRecording("whole");
        whole.start();

        long t0 = System.currentTimeMillis();
        registerAndLoginTeacher();
        result.put("setupLoginMillis", System.currentTimeMillis() - t0);

        List<Map<String, Object>> variants = new ArrayList<>();
        Path focusedJfr = jfrDir.resolve(label + "-focused.jfr");
        Recording focused = null;
        try {
            // 变体 1：代表规模快照（rep）——含 focused JFR（仅覆盖它的 e2e 两臂）
            focused = newRecording("focused");
            focused.start();
            Map<String, Object> rep = oneVariant(VARIANT_REPRESENTATIVE, VARIANT_REP_QCOUNT,
                    EXAM_ID_BASE + 1, E2E_CONCURRENCY_REP, true);
            focused.stop();
            focused.dump(focusedJfr);
            focused.close();
            focused = null;
            variants.add(rep);

            // 变体 2：小快照（small）——仅并发 1 端到端，用于长字段规模敏感性
            Map<String, Object> small = oneVariant(VARIANT_SMALL, VARIANT_SMALL_QCOUNT,
                    EXAM_ID_BASE + 2, E2E_CONCURRENCY_SMALL, false);
            variants.add(small);
        } finally {
            if (focused != null) {
                focused.stop();
                focused.close();
            }
            whole.stop();
            whole.dump(wholeJfr);
            whole.close();
            cleanupRedisKeys();
        }
        result.put("variants", variants);

        Map<String, Object> jfr = new LinkedHashMap<>();
        jfr.put("whole", summarizeJfr(wholeJfr));
        jfr.put("focused", summarizeJfr(focusedJfr));
        result.put("jfr", jfr);

        // 收尾：一次 Full GC 后的堆占用（粗粒度峰值参考，非精确峰值）
        System.gc();
        Thread.sleep(200);
        Runtime rt = Runtime.getRuntime();
        result.put("heapUsedAfterGcMB", round3((rt.totalMemory() - rt.freeMemory()) / 1024.0 / 1024.0));
        result.put("finishedAt", LocalDateTime.now().toString());

        Path jsonPath = outDir.resolve("monitor-attribution-" + label + ".json");
        Files.writeString(jsonPath, om.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                StandardCharsets.UTF_8);
        System.out.println("MEASURE wrote " + jsonPath.toAbsolutePath());
        System.out.println("MEASURE jfrDir " + jfrDir.toAbsolutePath());
        System.out.println("MEASURE jfr.whole " + jfr.get("whole"));
        System.out.println("MEASURE jfr.focused " + jfr.get("focused"));
    }

    /** 单个变体：造数 + 全部组件臂 + 端到端臂 + 份额推导。 */
    private Map<String, Object> oneVariant(String name, int qCount, long examId, int[] concurrencies,
                                           boolean logArms) throws Exception {
        long t0 = System.currentTimeMillis();
        VariantData data = buildData(name, qCount, examId);
        long buildMillis = System.currentTimeMillis() - t0;

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("variant", name);
        v.put("examId", examId);
        v.put("n", N_STUDENTS);
        v.put("qCount", qCount);
        v.put("buildDataMillis", buildMillis);
        v.put("statusMix", Map.of("inProgress", IN_PROGRESS_COUNT,
                "submitted", SUBMITTED_COUNT,
                "graded", N_STUDENTS - IN_PROGRESS_COUNT - SUBMITTED_COUNT));
        v.put("draftKindMix", draftKindMix(data.draftKinds()));
        v.put("paperCharsPerRow", data.paperJson().length());
        v.put("answersCharsPerRow", data.answersJson() == null ? 0 : data.answersJson().length());
        v.put("paperCharsTotal", (long) data.paperJson().length() * N_STUDENTS);
        v.put("answersCharsTotal", data.answersJson() == null ? 0L
                : (long) data.answersJson().length() * (N_STUDENTS - IN_PROGRESS_COUNT));
        v.put("fieldCharsNote", "按生成内容字符数估算（估计值），非真实网络字节");

        Map<String, Object> sqlFull = measureSql(examId, false);
        Map<String, Object> sqlProjected = measureSql(examId, true);
        Map<String, Object> namesSql = measureSqlNames(examId, data.studentIds());
        Map<String, Object> narrow = measureNarrowSnapshot(examId);
        Map<String, Object> draftGet = measureDraftGetEach(examId, data.inProgressIds());
        Map<String, Object> draftMGet = measureDraftMGet(examId, data.inProgressIds());
        Map<String, Object> draftBatch = measureDraftBatchFull(examId, data.inProgressIds(), data.draftKinds());
        Map<String, Object> onlineMGet = measureOnlineMGet(examId, data.inProgressIds());
        Map<String, Object> abnormalAgg = measureAbnormalAgg(examId);

        v.put("sqlFull", sqlFull);
        v.put("sqlProjected", sqlProjected);
        v.put("namesSql", namesSql);
        v.put("narrowSnapshot", narrow);
        v.put("draftGetEach", draftGet);
        v.put("draftMGetInfo", draftMGet);
        v.put("draftBatchFull", draftBatch);
        v.put("onlineMGet", onlineMGet);
        v.put("abnormalAgg", abnormalAgg);
        v.put("draftBatchVsGetEachRatio", Map.of(
                "denominator", "同一轮 draftGetEach.passMedianMs（同工具同数据同时刻，与批取臂可相除）",
                "getEachPassMedianMs", draftGet.get("passMedianMs"),
                "batchPassMedianMs", draftBatch.get("passMedianMs"),
                "ratioGetEachOverBatch", round3(ratioNumerator(draftGet.get("passMedianMs"), draftBatch.get("passMedianMs"))),
                "ratioBatchOverGetEach", round3(ratioNumerator(draftBatch.get("passMedianMs"), draftGet.get("passMedianMs")))));

        List<Map<String, Object>> e2e = new ArrayList<>();
        for (int c : concurrencies) {
            Map<String, Object> arm = e2eArm(examId, c, E2E_REQUESTS, data);
            e2e.add(arm);
            System.out.println("MEASURE e2e " + name + " " + arm);
        }
        v.put("e2e", e2e);

        // 份额推导：以 e2e（并发 1）mean 为分母（口径见 metricDefinitions.sharePct）
        Map<String, Object> c1 = e2e.stream().filter(m -> ((Integer) m.get("concurrency")) == 1)
                .findFirst().orElse(null);
        if (c1 != null) {
            double e2eMean = (Double) c1.get("meanMs");
            double fullMedian = (Double) sqlFull.get("medianMs");
            double projMedian = (Double) sqlProjected.get("medianMs");
            double draftPassMedian = (Double) draftGet.get("passMedianMs");
            double onlineMedian = (Double) onlineMGet.get("medianMs");
            double abnormalMedian = (Double) abnormalAgg.get("medianMs");
            double namesMedian = (Double) namesSql.get("medianMs");
            double narrowMedian = (Double) narrow.get("medianMs");
            double longFieldDelta = fullMedian - projMedian;

            List<Map<String, Object>> ranking = new ArrayList<>();
            ranking.add(factor("draftGetEach(逐人 Redis GET)", draftPassMedian, e2eMean));
            ranking.add(factor("sqlLongFieldDelta(paper_json+answers 增量)", longFieldDelta, e2eMean));
            ranking.add(factor("namesSql(姓名批查)", namesMedian, e2eMean));
            ranking.add(factor("abnormalAgg(异常聚合)", abnormalMedian, e2eMean));
            ranking.add(factor("onlineMGet(在线 MGET，已批读)", onlineMedian, e2eMean));
            ranking.add(factor("narrowSnapshot(拟新增窄读一次)", narrowMedian, e2eMean));
            ranking.sort(Comparator.comparingDouble(
                    (Map<String, Object> m) -> (Double) m.get("perRequestMs")).reversed());

            Map<String, Object> partition = new LinkedHashMap<>();
            partition.put("denominator", "e2e 并发1 meanMs（含安全过滤链/序列化，故各臂份额之和 < 100%）");
            partition.put("e2eMeanMs", e2eMean);
            partition.put("longFieldDeltaMs", round3(longFieldDelta));
            partition.put("longFieldSharePctOfE2eMean", round3(pctOf(longFieldDelta, e2eMean)));
            partition.put("draftGetEachMs", round3(draftPassMedian));
            partition.put("draftSharePctOfE2eMean", round3(pctOf(draftPassMedian, e2eMean)));
            partition.put("draftBatchFullMs", draftBatch.get("passMedianMs"));
            partition.put("draftBatchSharePctOfE2eMean", round3(pctOf(
                    ((Number) draftBatch.get("passMedianMs")).doubleValue(), e2eMean)));
            partition.put("projectedPlusNarrowMs", round3(projMedian + narrowMedian));
            v.put("e2ePartition", partition);
            v.put("factorRanking", ranking);
            System.out.println("MEASURE partition " + name + " " + partition);
            System.out.println("MEASURE ranking " + name + " " + ranking);
        }

        if (logArms) {
            System.out.println("MEASURE sqlFull " + sqlFull);
            System.out.println("MEASURE sqlProjected " + sqlProjected);
            System.out.println("MEASURE namesSql " + namesSql);
            System.out.println("MEASURE narrowSnapshot " + narrow);
            System.out.println("MEASURE draftGetEach " + draftGet);
            System.out.println("MEASURE draftMGetInfo " + draftMGet);
            System.out.println("MEASURE draftBatchFull " + draftBatch);
            System.out.println("MEASURE onlineMGet " + onlineMGet);
            System.out.println("MEASURE abnormalAgg " + abnormalAgg);
        }
        return v;
    }

    private static Map<String, Object> factor(String name, double ms, double e2eMean) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("factor", name);
        m.put("perRequestMs", round3(ms));
        m.put("sharePctOfE2eMean", round3(pctOf(ms, e2eMean)));
        return m;
    }

    private static double pctOf(double part, double whole) {
        return whole <= 0 ? 0 : 100.0 * part / whole;
    }

    // ==================== 组件臂实现 ====================

    /** 主答卷取数：full=true 走现有全列语句；false 走短列投影（并校验长字段确未载入）。 */
    private Map<String, Object> measureSql(long examId, boolean projected) {
        int rows = 0;
        boolean longFieldsAbsent = true;
        for (int i = 0; i < SQL_WARMUP; i++) {
            List<ExamSubmission> l = projected ? projectedList(examId) : fullList(examId);
            rows = l.size();
        }
        long[] ns = new long[SQL_REPS];
        for (int i = 0; i < SQL_REPS; i++) {
            long t = System.nanoTime();
            List<ExamSubmission> l = projected ? projectedList(examId) : fullList(examId);
            ns[i] = System.nanoTime() - t;
            rows = l.size();
            if (projected) {
                for (ExamSubmission s : l) {
                    if (s.getPaperJson() != null || s.getAnswers() != null) {
                        longFieldsAbsent = false;
                    }
                }
            }
        }
        long[] sorted = ns.clone();
        Arrays.sort(sorted);
        Map<String, Object> m = statBlock(ns, sorted, SQL_WARMUP, SQL_REPS);
        m.put("rowCount", rows);
        if (projected) {
            m.put("longFieldsAbsent", longFieldsAbsent);
        }
        return m;
    }

    private List<ExamSubmission> fullList(long examId) {
        return submissionMapper.selectList(
                Wrappers.<ExamSubmission>lambdaQuery().eq(ExamSubmission::getExamId, examId));
    }

    /** 短列投影：不含 paper_json / answers，模拟拟实施的取数边界。 */
    private List<ExamSubmission> projectedList(long examId) {
        return submissionMapper.selectList(Wrappers.<ExamSubmission>lambdaQuery()
                .select(ExamSubmission::getId, ExamSubmission::getExamId, ExamSubmission::getStudentId,
                        ExamSubmission::getStartTime, ExamSubmission::getDeadlineTime,
                        ExamSubmission::getSubmitTime, ExamSubmission::getSubmitType,
                        ExamSubmission::getStatus, ExamSubmission::getVersion)
                .eq(ExamSubmission::getExamId, examId));
    }

    private Map<String, Object> measureSqlNames(long examId, List<Long> studentIds) {
        int rows = 0;
        for (int i = 0; i < SQL_WARMUP; i++) {
            rows = userMapper.selectBatchIds(studentIds).size();
        }
        long[] ns = new long[SQL_REPS];
        for (int i = 0; i < SQL_REPS; i++) {
            long t = System.nanoTime();
            rows = userMapper.selectBatchIds(studentIds).size();
            ns[i] = System.nanoTime() - t;
        }
        long[] sorted = ns.clone();
        Arrays.sort(sorted);
        Map<String, Object> m = statBlock(ns, sorted, SQL_WARMUP, SQL_REPS);
        m.put("rowCount", rows);
        return m;
    }

    private Map<String, Object> measureNarrowSnapshot(long examId) {
        String sql = "SELECT paper_json FROM exam_submissions WHERE exam_id = ? AND paper_json IS NOT NULL LIMIT 1";
        int rows = 0;
        for (int i = 0; i < SQL_WARMUP; i++) {
            rows = jdbc.queryForList(sql, String.class, examId).size();
        }
        long[] ns = new long[SQL_REPS];
        for (int i = 0; i < SQL_REPS; i++) {
            long t = System.nanoTime();
            rows = jdbc.queryForList(sql, String.class, examId).size();
            ns[i] = System.nanoTime() - t;
        }
        long[] sorted = ns.clone();
        Arrays.sort(sorted);
        Map<String, Object> m = statBlock(ns, sorted, SQL_WARMUP, SQL_REPS);
        m.put("rowCount", rows);
        m.put("sql", sql);
        return m;
    }

    private Map<String, Object> measureDraftGetEach(long examId, List<Long> inProgressIds) {
        for (int w = 0; w < DRAFT_WARMUP_PASSES; w++) {
            for (Long sid : inProgressIds) {
                draftService.get(examId, sid);
            }
        }
        long[] passNs = new long[DRAFT_PASSES];
        List<Long> callNs = new ArrayList<>(DRAFT_PASSES * inProgressIds.size());
        int misses = 0;
        long answeredSum = 0;
        Map<String, Long> cmdDelta = null;
        for (int p = 0; p < DRAFT_PASSES; p++) {
            Map<String, Long> before = p == 0 ? commandCallCounts() : null;
            long t0 = System.nanoTime();
            for (Long sid : inProgressIds) {
                long c0 = System.nanoTime();
                ExamDraftService.DraftState st = draftService.get(examId, sid);
                callNs.add(System.nanoTime() - c0);
                if (st == null) {
                    misses++;
                } else if (st.answers() != null && st.answers().isObject()) {
                    answeredSum += st.answers().size();
                }
            }
            passNs[p] = System.nanoTime() - t0;
            if (before != null) {
                cmdDelta = cacheOnlyDelta(before, commandCallCounts());
            }
        }
        long[] calls = callNs.stream().mapToLong(Long::longValue).toArray();
        long[] callsSorted = calls.clone();
        Arrays.sort(callsSorted);
        long[] passSorted = passNs.clone();
        Arrays.sort(passSorted);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inProgressStudents", inProgressIds.size());
        m.put("warmupPasses", DRAFT_WARMUP_PASSES);
        m.put("passes", DRAFT_PASSES);
        m.put("totalCalls", calls.length);
        m.put("misses", misses);
        m.put("answeredEntriesTotal", answeredSum);
        m.put("passBestMs", round3(passSorted[0] / 1e6));
        m.put("passMedianMs", round3(percentile(passSorted, 0.50)));
        m.put("passMeanMs", round3(mean(passNs)));
        m.put("passWorstMs", round3(passSorted[passSorted.length - 1] / 1e6));
        m.put("callP50Ms", round3(percentile(callsSorted, 0.50)));
        m.put("callP95Ms", round3(percentile(callsSorted, 0.95)));
        m.put("callMeanMs", round3(mean(calls)));
        m.put("perStudentRoundTripsPerRequest", inProgressIds.size());
        m.put("redisCommandDeltaPerPass", cmdDelta);
        return m;
    }

    /**
     * 完整批取臂：一次 multiGet + 逐值按 {@code ExamDraftService.get} 同口径解析 + 按学生下标关联。
     * 每轮计时窗口内只做「批取 + 解析 + 关联」；与单份 get 的等价校验放在计时窗口之后，避免污染计时。
     * 等价判据：逐学生的 version / answers / marked / savedTime 与 {@code draftService.get} 全等，
     * 且「无草稿/损坏按 null」的缺失集合一致——不成立即断言失败（测量结论无效）。
     */
    private Map<String, Object> measureDraftBatchFull(Long examId, List<Long> inProgressIds, List<DraftKind> kinds) {
        List<String> keys = new ArrayList<>(inProgressIds.size());
        for (Long sid : inProgressIds) {
            keys.add("exam:draft:" + examId + ":" + sid);
        }
        for (int w = 0; w < DRAFT_WARMUP_PASSES; w++) {
            batchPass(keys);
        }
        long[] passNs = new long[DRAFT_PASSES];
        List<Long> parseNs = new ArrayList<>(DRAFT_PASSES * inProgressIds.size());
        int nonNull = -1;
        int parseFail = 0;
        long answeredSum = 0;
        Map<String, Long> cmdDelta = null;
        for (int p = 0; p < DRAFT_PASSES; p++) {
            Map<String, Long> before = p == 0 ? commandCallCounts() : null;
            long t0 = System.nanoTime();
            BatchPassResult r = batchPass(keys);
            passNs[p] = System.nanoTime() - t0;
            parseNs.addAll(r.parseNanos());
            nonNull = r.nonNull();
            parseFail += r.parseFail();
            answeredSum += r.answeredEntries();
            if (before != null) {
                cmdDelta = cacheOnlyDelta(before, commandCallCounts());
            }
        }

        // 等价校验（不计时）：逐学生与单份 get 对照，并按草稿形态分桶记录
        int mismatches = 0;
        String firstMismatch = "";
        int nullAgreement = 0;
        Map<String, Map<String, Object>> byBranch = new LinkedHashMap<>();
        for (int i = 0; i < inProgressIds.size(); i++) {
            Long sid = inProgressIds.get(i);
            String json = redis.opsForValue().get("exam:draft:" + examId + ":" + sid);
            ExamDraftService.DraftState batched = (json == null || json.isBlank()) ? null : parseDraftLikeGet(json);
            ExamDraftService.DraftState single = draftService.get(examId, sid);
            String diff = diffState(single, batched);
            if (diff != null) {
                mismatches++;
                if (firstMismatch.isEmpty()) {
                    firstMismatch = "student=" + sid + " " + diff;
                }
            }
            if (single == null && batched == null) {
                nullAgreement++;
            }
            Map<String, Object> bucket = byBranch.computeIfAbsent(kinds.get(i).name(), k -> {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("checked", 0);
                b.put("mismatches", 0);
                b.put("bothNull", 0);
                b.put("singleNonNull", 0);
                return b;
            });
            bucket.put("checked", ((Integer) bucket.get("checked")) + 1);
            if (diff != null) {
                bucket.put("mismatches", ((Integer) bucket.get("mismatches")) + 1);
            }
            if (single == null && batched == null) {
                bucket.put("bothNull", ((Integer) bucket.get("bothNull")) + 1);
            } else if (single != null) {
                bucket.put("singleNonNull", ((Integer) bucket.get("singleNonNull")) + 1);
            }
        }
        assertEquals(0, mismatches, "批取结果与单份 get 不等价（测量结论无效）: " + firstMismatch);

        long[] parses = parseNs.stream().mapToLong(Long::longValue).toArray();
        long[] parsesSorted = parses.clone();
        Arrays.sort(parsesSorted);
        long[] passSorted = passNs.clone();
        Arrays.sort(passSorted);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inProgressStudents", inProgressIds.size());
        m.put("warmupPasses", DRAFT_WARMUP_PASSES);
        m.put("passes", DRAFT_PASSES);
        m.put("keysPerPass", keys.size());
        m.put("redisCommandsPerPass", "1 x MGET（见 redisCommandDeltaPerPass）");
        m.put("nonNullValues", nonNull);
        m.put("parseFailures", parseFail);
        m.put("answeredEntriesTotal", answeredSum);
        m.put("equivalenceCheckedStudents", inProgressIds.size());
        m.put("equivalenceMismatches", mismatches);
        m.put("nullAgreementStudents", nullAgreement);
        m.put("equivalenceByBranch", byBranch);
        m.put("passBestMs", round3(passSorted[0] / 1e6));
        m.put("passMedianMs", round3(percentile(passSorted, 0.50)));
        m.put("passMeanMs", round3(mean(passNs)));
        m.put("passWorstMs", round3(passSorted[passSorted.length - 1] / 1e6));
        m.put("parseP50Ms", round3(percentile(parsesSorted, 0.50)));
        m.put("parseP95Ms", round3(percentile(parsesSorted, 0.95)));
        m.put("parseMeanMs", round3(mean(parses)));
        m.put("redisCommandDeltaPerPass", cmdDelta);
        return m;
    }

    private record BatchPassResult(int nonNull, int parseFail, long answeredEntries, List<Long> parseNanos) {
    }

    /** 一次完整批取：multiGet → 逐值解析 → 按下标关联（回填 null 表示无草稿/损坏）。 */
    private BatchPassResult batchPass(List<String> keys) {
        List<String> values = redis.opsForValue().multiGet(keys);
        List<Long> parseNanos = new ArrayList<>(keys.size());
        int nonNull = 0;
        int parseFail = 0;
        long answered = 0;
        for (int i = 0; i < keys.size(); i++) {
            String value = values == null ? null : values.get(i);
            if (value == null || value.isBlank()) {
                continue;   // 缺键：与单份 get 一致按无草稿，不参与解析
            }
            nonNull++;
            long t = System.nanoTime();
            ExamDraftService.DraftState state = parseDraftLikeGet(value);
            parseNanos.add(System.nanoTime() - t);
            if (state == null) {
                parseFail++;
            } else if (state.answers() != null && state.answers().isObject()) {
                answered += state.answers().size();
            }
        }
        return new BatchPassResult(nonNull, parseFail, answered, parseNanos);
    }

    /** 与 {@code ExamDraftService.get} 逐行同口径的解析（供批取臂复用同一解析语义做等价对照）。 */
    private ExamDraftService.DraftState parseDraftLikeGet(String json) {
        try {
            JsonNode root = om.readTree(json);
            List<Long> marked = new ArrayList<>();
            JsonNode markedNode = root.get("marked");
            if (markedNode != null && markedNode.isArray()) {
                markedNode.forEach(n -> marked.add(n.asLong()));
            }
            LocalDateTime savedTime = root.hasNonNull("savedTime")
                    ? LocalDateTime.parse(root.get("savedTime").asText()) : null;
            return new ExamDraftService.DraftState(root.path("version").asInt(1), root.get("answers"), marked, savedTime);
        } catch (Exception e) {
            return null;
        }
    }

    /** 两份草稿状态是否全等；不等返回差异描述，相等返回 null。 */
    private static String diffState(ExamDraftService.DraftState single, ExamDraftService.DraftState batched) {
        if (single == null || batched == null) {
            return single == batched ? null : ("null 不一致 single=" + (single == null) + " batched=" + (batched == null));
        }
        if (single.version() != batched.version()) {
            return "version " + single.version() + " != " + batched.version();
        }
        if (!java.util.Objects.equals(single.answers(), batched.answers())) {
            return "answers 不等";
        }
        if (!java.util.Objects.equals(single.marked(), batched.marked())) {
            return "marked 不等 " + single.marked() + " != " + batched.marked();
        }
        if (!java.util.Objects.equals(single.savedTime(), batched.savedTime())) {
            return "savedTime 不等 " + single.savedTime() + " != " + batched.savedTime();
        }
        return null;
    }

    /** 只保留命令名 → 调用次数的差值（丢弃 usec 等非计数项），便于跨轮比较。 */
    private static Map<String, Long> cacheOnlyDelta(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> delta = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : after.entrySet()) {
            long d = e.getValue() - before.getOrDefault(e.getKey(), 0L);
            if (d != 0) {
                delta.put(e.getKey(), d);
            }
        }
        return delta;
    }

    /** 读取 Redis INFO commandstats 的 cmdstat_<cmd> calls 计数（server 级全局计数）。 */
    private Map<String, Long> commandCallCounts() {
        var connection = redis.getConnectionFactory().getConnection();
        try {
            Properties props = connection.serverCommands().info("commandstats");
            Map<String, Long> counts = new LinkedHashMap<>();
            if (props == null) {
                return counts;
            }
            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith("cmdstat_")) {
                    continue;
                }
                String value = props.getProperty(key);
                int at = value == null ? -1 : value.indexOf("calls=");
                if (at < 0) {
                    continue;
                }
                int end = value.indexOf(',', at);
                String num = end < 0 ? value.substring(at + 6) : value.substring(at + 6, end);
                try {
                    counts.put(key.substring("cmdstat_".length()), Long.parseLong(num.trim()));
                } catch (NumberFormatException ignored) {
                    // 计数格式异常不参与差值，留给缺失项判定
                }
            }
            return counts;
        } finally {
            connection.close();
        }
    }

    private static double ratioNumerator(Object numerator, Object denominator) {
        double n = ((Number) numerator).doubleValue();
        double d = ((Number) denominator).doubleValue();
        return d == 0 ? 0 : n / d;
    }

    private Map<String, Object> measureDraftMGet(long examId, List<Long> inProgressIds) {
        List<String> keys = new ArrayList<>(inProgressIds.size());
        for (Long sid : inProgressIds) {
            keys.add("exam:draft:" + examId + ":" + sid);
        }
        int nonNull = 0;
        for (int i = 0; i < SQL_WARMUP; i++) {
            List<String> vals = redis.opsForValue().multiGet(keys);
            nonNull = vals == null ? 0 : (int) vals.stream().filter(java.util.Objects::nonNull).count();
        }
        long[] ns = new long[SQL_REPS];
        for (int i = 0; i < SQL_REPS; i++) {
            long t = System.nanoTime();
            List<String> vals = redis.opsForValue().multiGet(keys);
            ns[i] = System.nanoTime() - t;
            nonNull = vals == null ? 0 : (int) vals.stream().filter(java.util.Objects::nonNull).count();
        }
        long[] sorted = ns.clone();
        Arrays.sort(sorted);
        Map<String, Object> m = statBlock(ns, sorted, SQL_WARMUP, SQL_REPS);
        m.put("keys", keys.size());
        m.put("nonNullValues", nonNull);
        m.put("note", "对照方向：本轮不实施；仅证明草稿批量化的可达成本");
        return m;
    }

    private Map<String, Object> measureOnlineMGet(long examId, List<Long> inProgressIds) {
        int online = 0;
        for (int i = 0; i < SQL_WARMUP; i++) {
            online = presenceService.onlineOf(examId, inProgressIds).size();
        }
        long[] ns = new long[SQL_REPS];
        for (int i = 0; i < SQL_REPS; i++) {
            long t = System.nanoTime();
            online = presenceService.onlineOf(examId, inProgressIds).size();
            ns[i] = System.nanoTime() - t;
        }
        long[] sorted = ns.clone();
        Arrays.sort(sorted);
        Map<String, Object> m = statBlock(ns, sorted, SQL_WARMUP, SQL_REPS);
        m.put("onlineCount", online);
        return m;
    }

    private Map<String, Object> measureAbnormalAgg(long examId) {
        int rows = 0;
        for (int i = 0; i < SQL_WARMUP; i++) {
            rows = behaviorLogMapper.selectAbnormalStats(examId, 2).size();
        }
        long[] ns = new long[SQL_REPS];
        for (int i = 0; i < SQL_REPS; i++) {
            long t = System.nanoTime();
            List<AbnormalBehaviorStat> l = behaviorLogMapper.selectAbnormalStats(examId, 2);
            ns[i] = System.nanoTime() - t;
            rows = l.size();
        }
        long[] sorted = ns.clone();
        Arrays.sort(sorted);
        Map<String, Object> m = statBlock(ns, sorted, SQL_WARMUP, SQL_REPS);
        m.put("rowCount", rows);
        return m;
    }

    private static Map<String, Object> statBlock(long[] ns, long[] sorted, int warmup, int reps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("warmup", warmup);
        m.put("reps", reps);
        m.put("bestMs", round3(sorted[0] / 1e6));
        m.put("p50Ms", round3(percentile(sorted, 0.50)));
        m.put("medianMs", round3(percentile(sorted, 0.50)));
        m.put("meanMs", round3(mean(ns)));
        m.put("p95Ms", round3(percentile(sorted, 0.95)));
        m.put("worstMs", round3(sorted[sorted.length - 1] / 1e6));
        return m;
    }

    // ==================== 端到端臂 ====================

    private Map<String, Object> e2eArm(long examId, int concurrency, int count, VariantData data) throws Exception {
        String url = "/api/exams/" + examId + "/monitor/overview";
        int expectedStudents = N_STUDENTS;
        int expectedQuestions = data.qCount();

        try (HeapSampler heap = new HeapSampler()) {
            for (int i = 0; i < E2E_WARMUP; i++) {
                RequestResult warm = doRequest(url);
                assertEquals(200, warm.status, "预热请求失败: " + warm.status);
            }

            long[] latency = new long[count];
            int[] statuses = new int[count];
            int[] students = new int[count];
            int[] questions = new int[count];
            long[] respBytes = new long[count];

            // 端到端每请求的 Redis 命令数：仅并发 1 的臂可取「整段差值 ÷ 请求数」
            Map<String, Long> cmdBefore = concurrency == 1 ? commandCallCounts() : null;
            long wallStart;
            if (concurrency == 1) {
                wallStart = System.nanoTime();
                for (int i = 0; i < count; i++) {
                    RequestResult r = doRequest(url);
                    latency[i] = r.nanos;
                    statuses[i] = r.status;
                    students[i] = r.totalStudents;
                    questions[i] = r.totalQuestions;
                    respBytes[i] = r.bytes;
                }
            } else {
                ExecutorService pool = Executors.newFixedThreadPool(concurrency);
                CountDownLatch ready = new CountDownLatch(concurrency);
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger next = new AtomicInteger(0);
                List<Future<?>> futures = new ArrayList<>(concurrency);
                wallStart = System.nanoTime();
                for (int t = 0; t < concurrency; t++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        try {
                            go.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                        int i;
                        while ((i = next.getAndIncrement()) < count) {
                            RequestResult r = doRequest(url);
                            latency[i] = r.nanos;
                            statuses[i] = r.status;
                            students[i] = r.totalStudents;
                            questions[i] = r.totalQuestions;
                            respBytes[i] = r.bytes;
                        }
                        return null;
                    }));
                }
                ready.await(30, TimeUnit.SECONDS);
                go.countDown();
                for (Future<?> f : futures) {
                    f.get(120, TimeUnit.SECONDS);
                }
                pool.shutdown();
            }
            long wallNanos = System.nanoTime() - wallStart;

            int errors = 0;
            StringBuilder firstError = new StringBuilder();
            long totalBytes = 0;
            long totalNanos = 0;
            for (int i = 0; i < count; i++) {
                totalBytes += respBytes[i];
                totalNanos += latency[i];
                if (statuses[i] != 200 || students[i] != expectedStudents || questions[i] != expectedQuestions) {
                    errors++;
                    if (firstError.length() == 0) {
                        firstError.append("第一处不符 idx=").append(i)
                                .append(" status=").append(statuses[i])
                                .append(" totalStudents=").append(students[i]).append(" 期望=").append(expectedStudents)
                                .append(" totalQuestions=").append(questions[i]).append(" 期望=").append(expectedQuestions);
                    }
                }
            }
            assertEquals(0, errors, "端到端语义不符: " + firstError);

            long[] sorted = latency.clone();
            Arrays.sort(sorted);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("concurrency", concurrency);
            m.put("requests", count);
            m.put("warmupRequests", E2E_WARMUP);
            m.put("errors", errors);
            m.put("p50Ms", round3(percentile(sorted, 0.50)));
            m.put("p95Ms", round3(percentile(sorted, 0.95)));
            m.put("p99Ms", round3(percentile(sorted, 0.99)));
            m.put("meanMs", round3(mean(latency)));
            m.put("worstMs", round3(sorted[sorted.length - 1] / 1e6));
            m.put("wallMillis", round3(wallNanos / 1e6));
            m.put("throughputRps", round3(count / (wallNanos / 1e9)));
            m.put("respBytesMean", round3(totalBytes / (double) count));
            m.put("peakHeapDuringArmMB", heap.peakMB());
            if (cmdBefore != null) {
                Map<String, Long> total = cacheOnlyDelta(cmdBefore, commandCallCounts());
                Map<String, Object> perRequest = new LinkedHashMap<>();
                total.forEach((k, v) -> perRequest.put(k, round3(v / (double) count)));
                m.put("redisCommandDeltaTotal", total);
                m.put("redisCommandsPerRequest", perRequest);
            }
            return m;
        }
    }

    /** 粗粒度堆峰值采样（20ms 轮询 MemoryMXBean），只作量级参考，非精确分配峰值。 */
    private static final class HeapSampler implements AutoCloseable {
        private final AtomicLong peak = new AtomicLong();
        private volatile boolean running = true;
        private final Thread thread;

        HeapSampler() {
            MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
            thread = new Thread(() -> {
                while (running) {
                    peak.accumulateAndGet(bean.getHeapMemoryUsage().getUsed(), Math::max);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "measure-heap-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        double peakMB() {
            return round3(peak.get() / 1024.0 / 1024.0);
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
        }
    }

    private record RequestResult(long nanos, int status, int totalStudents, int totalQuestions, long bytes) {
    }

    private RequestResult doRequest(String url) throws Exception {
        MockHttpServletRequestBuilder builder = get(url).header("Authorization", "Bearer " + teacherToken);
        long t0 = System.nanoTime();
        MvcResult result = mockMvc.perform(builder).andReturn();
        long nanos = System.nanoTime() - t0;
        byte[] body = result.getResponse().getContentAsByteArray();
        int totalStudents = -1;
        int totalQuestions = -1;
        try {
            JsonNode data = om.readTree(body).get("data");
            if (data != null) {
                totalStudents = data.path("totalStudents").asInt(-1);
                totalQuestions = data.path("totalQuestions").asInt(-1);
            }
        } catch (Exception ignored) {
            // 解析失败留给调用侧的语义断言报错
        }
        return new RequestResult(nanos, result.getResponse().getStatus(), totalStudents, totalQuestions, body.length);
    }

    // ==================== 数据构造 ====================

    private record VariantData(String name, int qCount, long examId, String paperJson, String answersJson,
                               List<Long> studentIds, List<Long> inProgressIds, List<DraftKind> draftKinds) {
    }

    /**
     * 进行中学生的草稿数据形态——与本变更必须守住、且必须与单份 get 同判的语义分支一一对应。
     * {@code NORMAL} 之外的四种都不应让批取与单份读取产生分歧。
     */
    private enum DraftKind { NORMAL, CORRUPT, NON_OBJECT_ANSWERS, MISSING_KEY, BLANK_VALUE }

    /** 每 60 个进行中学生取 2 个做异常形态（i%60=0/15/30/45），其余正常。 */
    private static DraftKind draftKindFor(int i) {
        return switch (i % 60) {
            case 0 -> DraftKind.CORRUPT;
            case 15 -> DraftKind.NON_OBJECT_ANSWERS;
            case 30 -> DraftKind.MISSING_KEY;
            case 45 -> DraftKind.BLANK_VALUE;
            default -> DraftKind.NORMAL;
        };
    }

    private VariantData buildData(String name, int qCount, long examId) throws Exception {
        String paperJson = buildPaperJson(qCount);
        String answersJson = buildAnswersJson(qCount);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());

        jdbc.update("DELETE FROM exam_behavior_logs WHERE exam_id = ?", examId);
        jdbc.update("DELETE FROM exam_submissions WHERE exam_id = ?", examId);
        jdbc.update("DELETE FROM exams WHERE id = ?", examId);
        jdbc.update("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                examId, "监考归因测量-" + name, PLACEHOLDER_PAPER_ID, now, now, 60, 1, 1, teacherId, 0);

        List<Long> studentIds = new ArrayList<>(N_STUDENTS);
        List<Long> inProgressIds = new ArrayList<>(IN_PROGRESS_COUNT);
        List<Object[]> activeBatch = new ArrayList<>(IN_PROGRESS_COUNT);
        List<Object[]> doneBatch = new ArrayList<>(N_STUDENTS - IN_PROGRESS_COUNT);
        List<Object[]> logBatch = new ArrayList<>();
        for (int i = 0; i < N_STUDENTS; i++) {
            long sid = STUDENT_ID_BASE + examId % 1000 * 1_000L + i;
            studentIds.add(sid);
            int status = i < IN_PROGRESS_COUNT ? ExamSubmission.STATUS_IN_PROGRESS
                    : (i < IN_PROGRESS_COUNT + SUBMITTED_COUNT ? ExamSubmission.STATUS_SUBMITTED
                    : ExamSubmission.STATUS_GRADED);
            if (status == ExamSubmission.STATUS_IN_PROGRESS) {
                // 进行中：不写 answers/submit_time/submit_type（留 DEFAULT NULL），与真实链路一致
                inProgressIds.add(sid);
                activeBatch.add(new Object[]{examId, sid, now, now, paperJson, status, 0});
            } else {
                doneBatch.add(new Object[]{examId, sid, now, now, now, 1, paperJson, answersJson, status, 0});
            }
            // 异常日志：i%10==0 → 严重度{1,1,3}；其余 i%5==0 → {1,2}；其它无
            int[] severities = (i % 10 == 0) ? new int[]{1, 1, 3}
                    : (i % ABNORMAL_STRIDE == 0) ? new int[]{1, 2} : new int[0];
            for (int k = 0; k < severities.length; k++) {
                logBatch.add(new Object[]{examId, sid, "SWITCH_SCREEN", "{}", severities[k],
                        Timestamp.valueOf(now.toLocalDateTime().minusSeconds(60L - k))});
            }
        }
        jdbc.batchUpdate(INSERT_SUBMISSION_ACTIVE, activeBatch);
        jdbc.batchUpdate(INSERT_SUBMISSION_DONE, doneBatch);
        if (!logBatch.isEmpty()) {
            jdbc.batchUpdate(INSERT_BEHAVIOR_LOG, logBatch);
        }

        // Redis 草稿 + 在线键（只写本工具自建的键）
        String draftJson = buildDraftJson(examId, qCount);
        String savedTime = LocalDateTime.now().toString();
        int onlineCount = 0;
        List<DraftKind> draftKinds = new ArrayList<>(inProgressIds.size());
        for (int i = 0; i < inProgressIds.size(); i++) {
            Long sid = inProgressIds.get(i);
            String draftKey = "exam:draft:" + examId + ":" + sid;
            DraftKind kind = draftKindFor(i);
            draftKinds.add(kind);
            String value = draftValueOf(kind, draftJson, savedTime);
            if (value != null) {
                redis.opsForValue().set(draftKey, value, Duration.ofHours(2));
                createdRedisKeys.add(draftKey);
            }
            if (i % ONLINE_EVERY != ONLINE_OFFSET) {
                String key = "exam:monitor:online:" + examId + ":" + sid;
                redis.opsForValue().set(key, "1", Duration.ofHours(2));
                createdRedisKeys.add(key);
                onlineCount++;
            }
        }
        assertEquals(IN_PROGRESS_COUNT - IN_PROGRESS_COUNT / ONLINE_EVERY, onlineCount,
                "在线键数量与预期不符（离线 1/5）");

        return new VariantData(name, qCount, examId, paperJson, answersJson, studentIds, inProgressIds, draftKinds);
    }

    /** 按形态给出该学生的草稿原始值；{@code null} 表示不写键（缺键形态）。 */
    private String draftValueOf(DraftKind kind, String draftJson, String savedTime) {
        String normal = draftJson.replace("\"savedTime\":\"\"", "\"savedTime\":\"" + savedTime + "\"");
        return switch (kind) {
            case MISSING_KEY -> null;
            case BLANK_VALUE -> "";
            case CORRUPT -> "{\"version\":1,\"answers\":{\"1000\":\"A\"";
            case NON_OBJECT_ANSWERS ->
                    "{\"version\":1,\"answers\":\"oops\",\"marked\":[],\"savedTime\":\"" + savedTime + "\"}";
            case NORMAL -> normal;
        };
    }

    /** 进行中答卷：不写 answers/submit_time/submit_type（留 DEFAULT NULL），避开 H2 的 NULL 类型绑定限制。 */
    private static final String INSERT_SUBMISSION_ACTIVE =
            "INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, paper_json, status,"
                    + " version, created_time, updated_time)"
                    + " VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";

    /** 已交卷/已批改答卷：answers 非空。 */
    private static final String INSERT_SUBMISSION_DONE =
            "INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, submit_time, submit_type,"
                    + " paper_json, answers, status, version, created_time, updated_time)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";

    private static final String INSERT_BEHAVIOR_LOG =
            "INSERT INTO exam_behavior_logs (exam_id, student_id, event_type, event_data, severity, event_time,"
                    + " created_time) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)";

    /** 合法个人快照：questions 数组长度 = 题数（overview 的题目总数来源）。 */
    private String buildPaperJson(int qCount) throws Exception {
        ObjectNode root = om.createObjectNode();
        root.put("paperId", PLACEHOLDER_PAPER_ID);
        ArrayNode questions = root.putArray("questions");
        for (int i = 0; i < qCount; i++) {
            ObjectNode q = questions.addObject();
            q.put("questionId", 1000 + i);
            q.put("type", i % 3 == 0 ? 1 : (i % 3 == 1 ? 2 : 3));
            q.put("content", "第" + (i + 1) + "题：" + "题干内容".repeat(30));
            ArrayNode choices = q.putArray("choices");
            choices.add("选项甲内容" + "选项补充".repeat(8));
            choices.add("选项乙内容" + "选项补充".repeat(8));
            choices.add("选项丙内容" + "选项补充".repeat(8));
            choices.add("选项丁内容" + "选项补充".repeat(8));
            q.put("correctAnswer", "A");
            q.put("score", 5.0);
        }
        return om.writeValueAsString(root);
    }

    /** 已交卷答案：每题一条，每 8 题一条长文本（模拟简答），使 answers 也是 LONGTEXT 量级。 */
    private String buildAnswersJson(int qCount) throws Exception {
        ObjectNode answers = om.createObjectNode();
        String longText = "简答作答内容" + "作答展开".repeat(60);
        for (int i = 0; i < qCount; i++) {
            answers.put(String.valueOf(1000 + i), i % 8 == 0 ? longText : "A");
        }
        return om.writeValueAsString(answers);
    }

    /** 进行中学生的草稿（answers 条目数 = DRAFT_ANSWER_ENTRIES）。 */
    private String buildDraftJson(long examId, int qCount) throws Exception {
        ObjectNode root = om.createObjectNode();
        root.put("version", 1);
        ObjectNode answers = root.putObject("answers");
        for (int i = 0; i < Math.min(DRAFT_ANSWER_ENTRIES, qCount); i++) {
            answers.put(String.valueOf(1000 + i), "A");
        }
        root.putArray("marked");
        root.put("savedTime", "");
        return om.writeValueAsString(root);
    }

    private void cleanupRedisKeys() {
        if (createdRedisKeys.isEmpty()) {
            return;
        }
        try {
            redis.delete(createdRedisKeys);
        } catch (Exception e) {
            System.out.println("MEASURE WARN 清理自建 Redis 键失败: " + e.getMessage());
        }
        createdRedisKeys.clear();
    }

    // ==================== 账号 ====================

    /** 注册并登录唯一真实教师（管理员发邀请码），返回并记录 teacherToken/teacherId。 */
    private void registerAndLoginTeacher() throws Exception {
        String adminToken = login("admin", "admin123");
        MvcResult invite = mockMvc.perform(post("/api/admin/invite-codes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertEquals(200, invite.getResponse().getStatus(),
                "管理员发邀请码失败: " + invite.getResponse().getContentAsString());
        String code = om.readTree(invite.getResponse().getContentAsString()).get("data").get("code").asText();

        String username = "monitor_measure_" + System.nanoTime();
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
        assertEquals(200, regResult.getResponse().getStatus(),
                "注册测量教师失败: " + regResult.getResponse().getContentAsString());

        teacherToken = login(username, "Pass1234");
        teacherId = jwtUtil.parseAccessToken(teacherToken).getId();
        assertNotNull(teacherId, "未能取到测量教师的 user id");
    }

    private String login(String username, String password) throws Exception {
        ObjectNode body = om.createObjectNode();
        body.put("username", username);
        body.put("password", password);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                "登录失败: " + result.getResponse().getContentAsString());
        return om.readTree(result.getResponse().getContentAsString()).get("data").get("accessToken").asText();
    }

    // ==================== JFR ====================

    private static Recording newRecording(String label) {
        Recording recording = new Recording();
        recording.setName("monitor-attribution-" + label);
        recording.enable("jdk.ExecutionSample").with("period", "10 ms");
        recording.enable("jdk.CPULoad").with("period", "500 ms");
        recording.enable("jdk.GCPhasePause").with("threshold", "0 ms");
        return recording;
    }

    /** 解析一次录制：采样总数 + 各目标类帧的命中占比 + 栈顶帧 Top8 + CPU/GC。 */
    private static Map<String, Object> summarizeJfr(Path path) throws IOException {
        int totalSamples = 0;
        int noStack = 0;
        Map<String, Integer> hits = new TreeMap<>();
        Map<String, Integer> topFrames = new LinkedHashMap<>();
        int cpuSamples = 0;
        double cpuUserSum = 0;
        double cpuMachineSum = 0;
        int gcCount = 0;
        double gcPauseMs = 0;

        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String name = event.getEventType().getName();
                if ("jdk.ExecutionSample".equals(name)) {
                    totalSamples++;
                    RecordedStackTrace stack = event.getStackTrace();
                    if (stack == null || stack.getFrames().isEmpty()) {
                        noStack++;
                        continue;
                    }
                    List<RecordedFrame> frames = stack.getFrames();
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (RecordedFrame frame : frames) {
                        if (frame.getMethod() == null || frame.getMethod().getType() == null) {
                            continue;
                        }
                        String type = frame.getMethod().getType().getName();
                        for (Map.Entry<String, String> fc : FOCUS_CLASSES.entrySet()) {
                            if (type.startsWith(fc.getValue()) && seen.add(fc.getKey())) {
                                hits.merge(fc.getKey(), 1, Integer::sum);
                            }
                        }
                    }
                    RecordedFrame top = frames.get(0);
                    String key = (top.getMethod() == null || top.getMethod().getType() == null)
                            ? "<unknown>"
                            : top.getMethod().getType().getName() + "." + top.getMethod().getName();
                    topFrames.merge(key, 1, Integer::sum);
                } else if ("jdk.CPULoad".equals(name)) {
                    Double user = readDouble(event, "jvmUser");
                    Double machine = readDouble(event, "machineTotal");
                    if (user != null) {
                        cpuUserSum += user;
                    }
                    if (machine != null) {
                        cpuMachineSum += machine;
                    }
                    cpuSamples++;
                } else if ("jdk.GCPhasePause".equals(name)) {
                    gcCount++;
                    gcPauseMs += event.getDuration().toNanos() / 1e6;
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalSamples", totalSamples);
        summary.put("samplesWithoutStack", noStack);
        Map<String, Object> share = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : hits.entrySet()) {
            share.put(e.getKey(), round3(totalSamples == 0 ? 0 : 100.0 * e.getValue() / totalSamples));
        }
        summary.put("hitSharePct", share);
        summary.put("cpuUserPctMean", round3(cpuSamples == 0 ? 0 : 100.0 * cpuUserSum / cpuSamples));
        summary.put("cpuMachineTotalPctMean", round3(cpuSamples == 0 ? 0 : 100.0 * cpuMachineSum / cpuSamples));
        summary.put("cpuLoadEvents", cpuSamples);
        summary.put("cpuReadingUsable", cpuSamples > 0);
        summary.put("gcPauseCount", gcCount);
        summary.put("gcPauseMsTotal", round3(gcPauseMs));
        summary.put("topFrames", topFrames(topFrames, 8));
        return summary;
    }

    private static Double readDouble(RecordedEvent event, String field) {
        try {
            Object value = event.getValue(field);
            if (value instanceof Float f) {
                return (double) f;
            }
            if (value instanceof Double d) {
                return d;
            }
            if (value instanceof Number num) {
                return num.doubleValue();
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static List<Map<String, Object>> topFrames(Map<String, Integer> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("frame", e.getKey());
                    m.put("samples", e.getValue());
                    return m;
                })
                .toList();
    }

    // ==================== 小工具 ====================

    private Map<String, Object> loadParams() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nStudents", N_STUDENTS);
        m.put("inProgressCount", IN_PROGRESS_COUNT);
        m.put("submittedCount", SUBMITTED_COUNT);
        m.put("sqlWarmup", SQL_WARMUP);
        m.put("sqlReps", SQL_REPS);
        m.put("draftWarmupPasses", DRAFT_WARMUP_PASSES);
        m.put("draftPasses", DRAFT_PASSES);
        m.put("e2eWarmupRequests", E2E_WARMUP);
        m.put("e2eRequests", E2E_REQUESTS);
        m.put("e2eConcurrencyRep", ints(E2E_CONCURRENCY_REP));
        m.put("e2eConcurrencySmall", ints(E2E_CONCURRENCY_SMALL));
        m.put("jfrEvents", List.of("jdk.ExecutionSample(period=10ms)", "jdk.CPULoad(period=500ms)",
                "jdk.GCPhasePause(threshold=0ms)"));
        m.put("redisDatabase", 15);
        return m;
    }

    private static Map<String, Integer> draftKindMix(List<DraftKind> kinds) {
        Map<String, Integer> mix = new LinkedHashMap<>();
        for (DraftKind kind : kinds) {
            mix.merge(kind.name(), 1, Integer::sum);
        }
        return mix;
    }

    private static List<Integer> ints(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int v : values) {
            list.add(v);
        }
        return list;
    }

    private static double mean(long[] ns) {
        long s = 0;
        for (long v : ns) {
            s += v;
        }
        return s / (double) ns.length / 1e6;
    }

    /** 最近秩分位（输入已升序），返回毫秒。 */
    private static double percentile(long[] sortedNs, double p) {
        int idx = (int) Math.ceil(p * sortedNs.length) - 1;
        idx = Math.max(0, Math.min(sortedNs.length - 1, idx));
        return sortedNs[idx] / 1e6;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
