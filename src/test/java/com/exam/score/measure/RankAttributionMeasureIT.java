package com.exam.score.measure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.score.service.RankCalculator;
import com.exam.submission.entity.ExamSubmission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 成绩排名归因隔离测量工具（update-score-ranking-calculation 证据返修）。
 *
 * <p><b>为什么单独一个类名以 IT 结尾</b>：Surefire 默认只收 {@code *Test}、{@code *Tests}、{@code Test*}、
 * {@code *TestCase} 四类命名，本类名以 {@code IT} 结尾因此<b>不会</b>进入 {@code mvn clean test} 全量门禁；只有显式
 * {@code -Dtest=RankAttributionMeasureIT} 才运行。它只做测量、不构成任何业务断言基线。
 *
 * <p><b>一次运行量四件事</b>（口径见 {@link #METRIC_DEFS} 与 {@link #DATA_RULE}）：
 * <ol>
 *   <li>纯函数微基准：{@code new RankCalculator().rank(同数据列表)} 的 best/median/mean/p95；</li>
 *   <li>隔离 SQL 取数：与 {@code ScoreService.myScore} 全班查询同语句的 selectList 耗时；</li>
 *   <li>请求级端到端：MockMvc {@code GET /api/scores/my}（并发 1 与 8）的 p50/p95/p99/mean/吞吐，
 *       并由测试侧 @Primary 计时包装器分账「请求内排名计算耗时」（见 {@link TimedRankCalculator}）；</li>
 *   <li>JFR 逐帧归因：WHOLE（登录+造数+语义自检+全量相位）/FOCUSED（仅 n=3000 热点）/RANK_HOT（纯 rank 紧循环）
 *       三个作用域下 {@code jdk.ExecutionSample} 中栈内含 {@code RankCalculator} 的样本占比 + 栈顶帧直方图。</li>
 * </ol>
 *
 * <p><b>不触碰生产代码</b>：不改 {@code src/main}、RankCalculator、SQL、schema、Mapper、前端、JVM/线程池配置；
 * 计时包装器只在本测试的 {@code @TestConfiguration} 内注册为 {@code @Primary} bean，仅本进程生效。
 *
 * <p><b>数据与环境</b>：隔离 H2 内存库（application-test.yml 的 test profile）+ Redis db15，
 * 单人登录取 token，其余 930000000+ 段位为合成 student_id（无 users 行，myScore 不读 users）。
 * 不写共享 dev、不启 Docker、不在 C 盘创建产物。
 *
 * <p><b>运行</b>（仓库根）：
 * <pre>
 * mvnw.cmd -q test -Dtest=RankAttributionMeasureIT -DfailIfNoTests=false ^
 *     -Dmeasure.rev=&lt;sha7&gt; -Dmeasure.label=old-run1 -Dmeasure.out=target/measure
 * </pre>
 * 机器可读结果写到 {@code ${measure.out}/rank-attribution-${measure.label}.json}，
 * JFR 落到 {@code ${measure.out}/jfr/}（体积大，不入版本库，只为现场复看）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("排名归因隔离测量（仅显式 -Dtest 运行；不进 Surefire 全量门禁）")
class RankAttributionMeasureIT {

    // ==================== 固定口径（便于跨 revision 逐次比对） ====================

    /** 班规模梯度，沿用归档阶段 1/2 的口径。 */
    private static final int[] SIZES = {50, 200, 1000, 3000};
    /** 考试 ID 段位：930000000 + n（避开 API 造数的自增小 ID）。 */
    private static final long EXAM_ID_BASE = 930_000_000L;
    /** 合成学生 ID 段位：仅写 exam_submissions.student_id，不建 users 行。 */
    private static final long STUDENT_ID_BASE = 930_000_000L;
    /** 唯一有 users 行的真实登录学生在榜单中的下标（固定，保证两版同位置）。 */
    private static final int REAL_STUDENT_INDEX = 0;
    /** 分数生成：score(i)=BigDecimal(400 + (i*7919)%300, scale 1)；gcd(7919,300)=1 ⇒ 300 个取值，n=3000 时约 10 人/分。 */
    private static final int SCORE_BASE = 400;
    private static final int SCORE_SPAN = 300;
    private static final long SCORE_MULT = 7919L;

    /** 纯函数微基准：预热次数与测量次数（沿用归档 bestOf30 口径）。 */
    private static final int RANK_WARMUP = 10;
    private static final int RANK_REPS = 30;
    /** 隔离 SQL 取数：预热与测量次数。 */
    private static final int SQL_WARMUP = 5;
    private static final int SQL_REPS = 20;
    /** 请求级：每臂预热请求数与计时请求数（p99 需足够样本）。 */
    private static final int E2E_WARMUP = 10;
    private static final int E2E_REQUESTS = 60;
    private static final int[] E2E_CONCURRENCY = {1, 8};
    /** RANK_HOT 诊断：纯 rank 紧循环时长（毫秒）。 */
    private static final long RANK_HOT_MILLIS = 2000L;
    /** JFR 逐帧归因判定用类名。 */
    private static final String RANK_CLASS = "com.exam.score.service.RankCalculator";

    private static final String DATA_RULE =
            "单人登录取 token（Pass1234）；exams 行直插 id=930000000+n、status=4/published=1、paper_id=930000001(占位无外键)；"
            + "exam_submissions 逐份直插 exam_id=930000000+n、status=3(已批改)、grading_status=1、total_score=objective_score=score(i)、"
            + "subjective_score=0.0；student_id：下标 " + REAL_STUDENT_INDEX + " 为真实登录用户，其余 = 930000000 + n*100000 + i"
            + "（无 users 行，myScore 不读 users）。分数含大量并列与 null 无关（全部非 null）。";

    private static final Map<String, String> METRIC_DEFS = Map.ofEntries(
            Map.entry("microbench.bestMs", "纯函数 rank() 逐次 nanoTime 差分的最小值（bestOf30，抗调度噪声的乐观下界）"),
            Map.entry("microbench.medianMs", "纯函数 rank() 逐次耗时中位数"),
            Map.entry("microbench.p95Ms", "纯函数 rank() 逐次耗时 p95"),
            Map.entry("microbench.ranksum", "该次排名数组求和（跨版本可比的语义指纹）"),
            Map.entry("sqlFetch.bestMs", "与 myScore 全班查询同语句的 mapper.selectList 逐次耗时最小值"),
            Map.entry("sqlFetch.medianMs", "同上，耗时中位数"),
            Map.entry("e2e.p50/p95/p99Ms", "MockMvc GET /api/scores/my 单请求墙钟耗时（含安全过滤链+JSON 序列化+mapper+rank），最近秩取值"),
            Map.entry("e2e.meanMs", "同上算术均值"),
            Map.entry("e2e.throughputRps", "计时请求数 / 该臂墙钟秒数（并发>1 时为有效吞吐）"),
            Map.entry("e2e.rankMeanMs", "计时包装器累计的请求内 rank() 耗时 / 请求数（口径=纯调用耗时，不含 SQL/框架）"),
            Map.entry("e2e.rankSharePct", "请求内 rank() 总耗时 / 全部请求墙钟总耗时（同臂同并发下可直接对账 JFR 份额）"),
            Map.entry("jfr.rankSharePct", "jdk.ExecutionSample 样本中栈内含 com.exam.score.service.RankCalculator 帧的比例"),
            Map.entry("jfr.topFrames", "栈顶（最内层）帧 class.method 直方图 Top8，用于判断采样落点与内联影响"),
            Map.entry("note", "本工具跑隔离 H2 + MockMvc 同进程，不是真实 MySQL/Tomcat 生产性能，不得当作交卷 P99 收益")
    );

    // ==================== 测试侧计时包装器（仅本测试上下文生效） ====================

    /**
     * 注册为 {@code @Primary} 的计时包装器，让请求路径上的 rank 调用可被分账；
     * 只增加两次 nanoTime，算法体仍是生产 {@link RankCalculator#rank}。
     */
    @TestConfiguration
    static class MeasureConfig {
        @Bean
        @Primary
        RankCalculator measuringRankCalculator() {
            return new TimedRankCalculator();
        }
    }

    /** 计时包装器：委托给生产实现并累计耗时/调用次数（纯统计，不改变返回值）。 */
    static class TimedRankCalculator extends RankCalculator {
        static final LongAdder RANK_NANOS = new LongAdder();
        static final LongAdder RANK_CALLS = new LongAdder();

        static void reset() {
            RANK_NANOS.reset();
            RANK_CALLS.reset();
        }

        @Override
        public int[] rank(List<BigDecimal> totals) {
            long t0 = System.nanoTime();
            int[] ranks = super.rank(totals);
            RANK_NANOS.add(System.nanoTime() - t0);
            RANK_CALLS.increment();
            return ranks;
        }
    }

    // ==================== 依赖 ====================

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;

    /** 生产实现的原味实例（绕过计时包装器），用于微基准与语义自检。 */
    private final RankCalculator rawCalculator = new RankCalculator();

    /** 各规模下真实学生的期望名次（测试侧 O(n²) 参照实现算出，端到端逐请求断言）。 */
    private final Map<Integer, Integer> expectedRank = new TreeMap<>();

    private String token;
    private Long realStudentId;

    // ==================== 主流程 ====================

    @Test
    @DisplayName("同口径四相位测量并落盘机器可读结果")
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
        result.put("dataRule", DATA_RULE);
        result.put("sizes", ints(SIZES));
        result.put("metricDefinitions", METRIC_DEFS);
        result.put("load", loadParams());

        // ---- 0) WHOLE 从"整轮最早可编程起点"开始：登录 + 造数 + 语义自检 + 全部规模全部相位。
        //          Spring 上下文启动（约 12s）在测试方法之前，程序化 Recording 无法覆盖，已在口径中声明；
        //          这正是"整轮聚合份额被稀释"的直接来源。
        Path wholeJfr = jfrDir.resolve(label + "-whole.jfr");
        Recording whole = newRecording("whole");
        whole.start();

        long t0 = System.currentTimeMillis();
        token = registerAndLoginRealStudent();
        result.put("setupLoginMillis", System.currentTimeMillis() - t0);
        realStudentId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, loginUsername);
        assertNotNull(realStudentId, "未能取到真实测量学生的 user id");
        long t1 = System.currentTimeMillis();
        prepareData();
        validateSemanticsAcrossSizes();
        result.put("setupDataMillis", System.currentTimeMillis() - t1);

        List<Map<String, Object>> microbench = new ArrayList<>();
        List<Map<String, Object>> sqlFetch = new ArrayList<>();
        List<Map<String, Object>> e2e = new ArrayList<>();
        try {
            for (int n : SIZES) {
                Map<String, Object> m = microbenchOne(n);
                microbench.add(m);
                System.out.println("MEASURE microbench " + m);
            }
            for (int n : SIZES) {
                Map<String, Object> s = sqlFetchOne(n);
                sqlFetch.add(s);
                System.out.println("MEASURE sqlFetch " + s);
            }
            for (int n : SIZES) {
                for (int concurrency : E2E_CONCURRENCY) {
                    Map<String, Object> r = e2eArm(n, concurrency, E2E_REQUESTS);
                    e2e.add(r);
                    System.out.println("MEASURE e2e " + r);
                }
            }
        } finally {
            whole.stop();
            whole.dump(wholeJfr);
            whole.close();
        }
        result.put("microbench", microbench);
        result.put("sqlFetch", sqlFetch);
        result.put("e2e", e2e);

        // ---- 2) FOCUSED：只含 n=3000 的微基准与端到端，检验「份额是否被整轮稀释」 ----
        int focusN = SIZES[SIZES.length - 1];
        Path focusedJfr = jfrDir.resolve(label + "-focused.jfr");
        Recording focused = newRecording("focused");
        focused.start();
        Map<String, Object> focusedMicro;
        List<Map<String, Object>> focusedE2e = new ArrayList<>();
        try {
            focusedMicro = microbenchOne(focusN);
            for (int concurrency : E2E_CONCURRENCY) {
                focusedE2e.add(e2eArm(focusN, concurrency, E2E_REQUESTS));
            }
        } finally {
            focused.stop();
            focused.dump(focusedJfr);
            focused.close();
        }
        result.put("focusedMicrobench", focusedMicro);
        result.put("focusedE2e", focusedE2e);

        // ---- 3) RANK_HOT：纯 rank 紧循环，探「逐帧归因在最好情况下能认到多少份额」 ----
        Path hotJfr = jfrDir.resolve(label + "-rankhot.jfr");
        Recording hot = newRecording("rankHot");
        hot.start();
        long hotCalls;
        try {
            hotCalls = rankHot(focusN, RANK_HOT_MILLIS);
        } finally {
            hot.stop();
            hot.dump(hotJfr);
            hot.close();
        }
        result.put("rankHotCalls", hotCalls);
        result.put("rankHotMillis", RANK_HOT_MILLIS);

        // ---- 4) JFR 解析 ----
        Map<String, Object> jfr = new LinkedHashMap<>();
        jfr.put("whole", summarizeJfr(wholeJfr));
        jfr.put("focused", summarizeJfr(focusedJfr));
        jfr.put("rankHot", summarizeJfr(hotJfr));
        result.put("jfr", jfr);

        result.put("finishedAt", LocalDateTime.now().toString());

        Path jsonPath = outDir.resolve("rank-attribution-" + label + ".json");
        Files.writeString(jsonPath, om.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                StandardCharsets.UTF_8);
        System.out.println("MEASURE wrote " + jsonPath.toAbsolutePath());
        System.out.println("MEASURE jfrDir " + jfrDir.toAbsolutePath());
        System.out.println("MEASURE jfr.whole " + jfr.get("whole"));
        System.out.println("MEASURE jfr.focused " + jfr.get("focused"));
        System.out.println("MEASURE jfr.rankHot " + jfr.get("rankHot"));
    }

    // ==================== 相位实现 ====================

    /** 纯函数微基准（同一份确定性列表；rank 语义指纹一并落盘）。 */
    private Map<String, Object> microbenchOne(int n) {
        List<BigDecimal> list = scores(n);
        for (int i = 0; i < RANK_WARMUP; i++) {
            rawCalculator.rank(list);
        }
        long[] ns = new long[RANK_REPS];
        long ranksum = 0;
        for (int i = 0; i < RANK_REPS; i++) {
            long t = System.nanoTime();
            int[] ranks = rawCalculator.rank(list);
            ns[i] = System.nanoTime() - t;
            ranksum = sum(ranks);
        }
        long[] sorted = ns.clone();
        Arrays.sort(sorted);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", n);
        m.put("warmup", RANK_WARMUP);
        m.put("reps", RANK_REPS);
        m.put("bestMs", round3(sorted[0] / 1e6));
        m.put("medianMs", round3(percentile(sorted, 0.50)));
        m.put("meanMs", round3(mean(ns)));
        m.put("p95Ms", round3(percentile(sorted, 0.95)));
        m.put("worstMs", round3(sorted[sorted.length - 1] / 1e6));
        m.put("ranksum", ranksum);
        return m;
    }

    /** 隔离 SQL 取数：与 {@code ScoreService.myScore} 全班查询同语句。 */
    private Map<String, Object> sqlFetchOne(int n) {
        long examId = EXAM_ID_BASE + n;
        int rows = 0;
        for (int i = 0; i < SQL_WARMUP; i++) {
            rows = gradedAll(examId).size();
        }
        long[] ns = new long[SQL_REPS];
        for (int i = 0; i < SQL_REPS; i++) {
            long t = System.nanoTime();
            List<GradingSubmission> list = gradedAll(examId);
            ns[i] = System.nanoTime() - t;
            rows = list.size();
        }
        long[] sorted = ns.clone();
        Arrays.sort(sorted);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", n);
        m.put("warmup", SQL_WARMUP);
        m.put("reps", SQL_REPS);
        m.put("rowCount", rows);
        m.put("bestMs", round3(sorted[0] / 1e6));
        m.put("medianMs", round3(percentile(sorted, 0.50)));
        m.put("meanMs", round3(mean(ns)));
        m.put("p95Ms", round3(percentile(sorted, 0.95)));
        return m;
    }

    /** 请求级一臂：并发 1 串行，或固定线程池并发 k 同时起跑。 */
    private Map<String, Object> e2eArm(int n, int concurrency, int count) throws Exception {
        long examId = EXAM_ID_BASE + n;
        String url = "/api/scores/my?examId=" + examId;
        int expected = expectedRank.get(n);

        for (int i = 0; i < E2E_WARMUP; i++) {
            RequestResult warm = doRequest(url);
            assertEquals(200, warm.status, "预热请求失败: " + warm.status);
        }

        TimedRankCalculator.reset();
        long[] latency = new long[count];
        int[] statuses = new int[count];
        int[] ranks = new int[count];
        long[] respBytes = new long[count];

        long wallStart;
        if (concurrency == 1) {
            wallStart = System.nanoTime();
            for (int i = 0; i < count; i++) {
                RequestResult r = doRequest(url);
                latency[i] = r.nanos;
                statuses[i] = r.status;
                ranks[i] = r.rank;
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
                        ranks[i] = r.rank;
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

        long rankNanos = TimedRankCalculator.RANK_NANOS.sum();
        long rankCalls = TimedRankCalculator.RANK_CALLS.sum();

        int errors = 0;
        StringBuilder firstError = new StringBuilder();
        long totalBytes = 0;
        long totalNanos = 0;
        for (int i = 0; i < count; i++) {
            totalBytes += respBytes[i];
            totalNanos += latency[i];
            if (statuses[i] != 200 || ranks[i] != expected) {
                errors++;
                if (firstError.length() == 0) {
                    firstError.append("第一处不符 idx=").append(i)
                            .append(" status=").append(statuses[i])
                            .append(" rank=").append(ranks[i]).append(" 期望=").append(expected);
                }
            }
        }

        long[] sorted = latency.clone();
        Arrays.sort(sorted);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", n);
        m.put("concurrency", concurrency);
        m.put("requests", count);
        m.put("warmupRequests", E2E_WARMUP);
        m.put("expectedRank", expected);
        m.put("errors", errors);
        if (errors > 0) {
            m.put("firstError", firstError.toString());
        }
        m.put("p50Ms", round3(percentile(sorted, 0.50)));
        m.put("p95Ms", round3(percentile(sorted, 0.95)));
        m.put("p99Ms", round3(percentile(sorted, 0.99)));
        m.put("meanMs", round3(mean(latency)));
        m.put("worstMs", round3(sorted[sorted.length - 1] / 1e6));
        m.put("wallMillis", round3(wallNanos / 1e6));
        m.put("throughputRps", round3(count / (wallNanos / 1e9)));
        m.put("respBytesMean", round3(totalBytes / (double) count));
        m.put("rankCallsInRequest", rankCalls);
        m.put("rankMeanMs", round3(rankNanos / 1e6 / count));
        m.put("rankTotalMs", round3(rankNanos / 1e6));
        m.put("rankSharePct", round3(totalNanos == 0 ? 0 : 100.0 * rankNanos / totalNanos));
        return m;
    }

    /** 纯 rank 紧循环（同一份不可变列表，避免建列表成本混入）。 */
    private long rankHot(int n, long millis) {
        List<BigDecimal> list = scores(n);
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long calls = 0;
        while (System.nanoTime() < deadline) {
            rawCalculator.rank(list);
            calls++;
        }
        return calls;
    }

    // ==================== 语义自检与数据 ====================

    /**
     * 语义自检（不进计时）：对每个规模，用测试侧 O(n²) 参照实现算出参照名次，
     * 与生产实现比对「完全一致」，并记录真实学生期望名次供端到端逐请求断言。
     */
    private void validateSemanticsAcrossSizes() {
        for (int n : SIZES) {
            List<BigDecimal> list = scores(n);
            int[] expected = referenceRank(list);
            int[] actual = rawCalculator.rank(list);
            assertEquals(expected.length, actual.length, "名次数组长度不一致 n=" + n);
            assertTrue(Arrays.equals(expected, actual),
                    "生产实现与测试侧 O(n²) 参照名次在 n=" + n + " 上不一致");
            expectedRank.put(n, expected[REAL_STUDENT_INDEX]);
        }
        System.out.println("MEASURE semantics OK expectedRank=" + expectedRank);
    }

    /** 测试侧参照实现（旧二重遍历语义）：同分并列、跳号、null 记 0。 */
    private static int[] referenceRank(List<BigDecimal> totals) {
        int n = totals.size();
        int[] ranks = new int[n];
        for (int i = 0; i < n; i++) {
            if (totals.get(i) == null) {
                continue;
            }
            int rank = 1;
            for (int j = 0; j < n; j++) {
                if (j != i && totals.get(j) != null && totals.get(j).compareTo(totals.get(i)) > 0) {
                    rank++;
                }
            }
            ranks[i] = rank;
        }
        return ranks;
    }

    /** 确定性分数列表：score(i)=BigDecimal(400+(i*7919)%300, 1)。 */
    private static List<BigDecimal> scores(int n) {
        List<BigDecimal> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(BigDecimal.valueOf(SCORE_BASE + (i * SCORE_MULT) % SCORE_SPAN, 1));
        }
        return list;
    }

    private static final String INSERT_SUBMISSION =
            "INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, status,"
                    + " objective_score, subjective_score, total_score, grading_status, partial_graded,"
                    + " version, created_time, updated_time)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";

    /** 每个规模一场考试 + n 份已批改答卷（真实学生落在固定下标）。 */
    private void prepareData() {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (int n : SIZES) {
            long examId = EXAM_ID_BASE + n;
            jdbc.update("DELETE FROM exam_submissions WHERE exam_id = ?", examId);
            jdbc.update("DELETE FROM exams WHERE id = ?", examId);
            jdbc.update("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes,"
                            + " status, published, created_by, version, created_time, updated_time)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    examId, "排名归因隔离测量-n" + n, 930000001L, now, now, 60, 4, 1, 930000999L, 0);

            List<Object[]> batch = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                BigDecimal score = BigDecimal.valueOf(SCORE_BASE + (i * SCORE_MULT) % SCORE_SPAN, 1);
                long studentId = (i == REAL_STUDENT_INDEX)
                        ? realStudentId
                        : STUDENT_ID_BASE + (long) n * 100000L + i;
                batch.add(new Object[]{examId, studentId, now, now,
                        ExamSubmission.STATUS_GRADED, score, BigDecimal.ZERO.setScale(1), score, 1, 0});
            }
            jdbc.batchUpdate(INSERT_SUBMISSION, batch);
        }
    }

    // ==================== 请求与账号 ====================

    private String loginUsername;

    /** 注册并登录唯一真实学生（无邀请码），返回 access token。 */
    private String registerAndLoginRealStudent() throws Exception {
        loginUsername = "rank_measure_" + System.nanoTime();
        ObjectNode reg = om.createObjectNode();
        reg.put("username", loginUsername);
        reg.put("password", "Pass1234");
        reg.put("name", "排名测量学生");
        reg.put("email", loginUsername + "@measure.test");
        reg.put("roleType", "STUDENT");
        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(reg.toString())).andReturn();
        assertEquals(200, regResult.getResponse().getStatus(),
                "注册测量学生失败: " + regResult.getResponse().getContentAsString());

        ObjectNode login = om.createObjectNode();
        login.put("username", loginUsername);
        login.put("password", "Pass1234");
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(login.toString())).andReturn();
        assertEquals(200, loginResult.getResponse().getStatus(),
                "登录测量学生失败: " + loginResult.getResponse().getContentAsString());
        JsonNode data = om.readTree(loginResult.getResponse().getContentAsString()).get("data");
        return data.get("accessToken").asText();
    }

    private record RequestResult(long nanos, int status, int rank, long bytes) {
    }

    /** 单次请求：计时只包住 perform + 读响应体，JSON 解析在计时窗口之外。 */
    private RequestResult doRequest(String url) throws Exception {
        MockHttpServletRequestBuilder builder = get(url).header("Authorization", "Bearer " + token);
        long t0 = System.nanoTime();
        MvcResult result = mockMvc.perform(builder).andReturn();
        long nanos = System.nanoTime() - t0;
        byte[] body = result.getResponse().getContentAsByteArray();
        int rank = 0;
        try {
            JsonNode root = om.readTree(body);
            JsonNode data = root.get("data");
            if (data != null && data.get("rank") != null) {
                rank = data.get("rank").asInt();
            }
        } catch (Exception ignored) {
            rank = -1;
        }
        return new RequestResult(nanos, result.getResponse().getStatus(), rank, body.length);
    }

    /** 与 {@code ScoreService.myScore}（:293-297）同语句的全班已汇总答卷取数。 */
    private List<GradingSubmission> gradedAll(long examId) {
        return gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                        .isNotNull(GradingSubmission::getTotalScore));
    }

    // ==================== JFR ====================

    private static Recording newRecording(String label) {
        Recording recording = new Recording();
        recording.setName("rank-attribution-" + label);
        recording.enable("jdk.ExecutionSample").with("period", "10 ms");
        recording.enable("jdk.CPULoad").with("period", "500 ms");
        recording.enable("jdk.GCPhasePause").with("threshold", "0 ms");
        return recording;
    }

    /**
     * 解析一次录制：执行采样总数/含 RankCalculator 帧数/份额 + 栈顶帧 Top8 + CPU/GC。
     * 栈顶帧直方图用于判断采样落点（例如热点是否被内联到调用者而看不到 RankCalculator 帧）。
     */
    private static Map<String, Object> summarizeJfr(Path path) throws IOException {
        int totalSamples = 0;
        int rankSamples = 0;
        int noStack = 0;
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
                    boolean hitRank = false;
                    for (RecordedFrame frame : frames) {
                        if (frame.getMethod() == null || frame.getMethod().getType() == null) {
                            continue;
                        }
                        String type = frame.getMethod().getType().getName();
                        if (RANK_CLASS.equals(type)) {
                            hitRank = true;
                        }
                    }
                    if (hitRank) {
                        rankSamples++;
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
        summary.put("rankSamples", rankSamples);
        summary.put("rankSharePct", round3(totalSamples == 0 ? 0 : 100.0 * rankSamples / totalSamples));
        summary.put("cpuUserPctMean", round3(cpuSamples == 0 ? 0 : 100.0 * cpuUserSum / cpuSamples));
        summary.put("cpuMachineTotalPctMean", round3(cpuSamples == 0 ? 0 : 100.0 * cpuMachineSum / cpuSamples));
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
        m.put("rankWarmup", RANK_WARMUP);
        m.put("rankReps", RANK_REPS);
        m.put("sqlWarmup", SQL_WARMUP);
        m.put("sqlReps", SQL_REPS);
        m.put("e2eWarmupRequests", E2E_WARMUP);
        m.put("e2eRequests", E2E_REQUESTS);
        m.put("e2eConcurrency", ints(E2E_CONCURRENCY));
        m.put("rankHotMillis", RANK_HOT_MILLIS);
        m.put("jfrEvents", List.of("jdk.ExecutionSample(period=10ms)", "jdk.CPULoad(period=500ms)", "jdk.GCPhasePause(threshold=0ms)"));
        return m;
    }

    private static List<Integer> ints(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int v : values) {
            list.add(v);
        }
        return list;
    }

    private static long sum(int[] values) {
        long s = 0;
        for (int v : values) {
            s += v;
        }
        return s;
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