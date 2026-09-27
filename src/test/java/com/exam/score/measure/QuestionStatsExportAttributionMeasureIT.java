package com.exam.score.measure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.support.GradingPaperReader;
import com.exam.question.entity.QuestionType;
import com.exam.score.service.ScoreExportService;
import com.exam.score.support.ExcelSheetWriter;
import com.exam.score.support.QuestionScoreResolver;
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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 题目统计导出（{@code ScoreExportService.exportQuestionStats}）内存/临时盘/耗时隔离归因测量工具
 * （update-question-stats-export-memory 阶段 1）。
 *
 * <p><b>为什么类名以 IT 结尾</b>：Surefire 默认只收 {@code *Test}/{@code *Tests}/{@code Test*}/{@code *TestCase}，
 * 本类不进 {@code mvnw.cmd clean test} 全量门禁，只有显式 {@code -Dtest=QuestionStatsExportAttributionMeasureIT} 才运行。
 * 它只做测量与 oracle 校验，不构成业务断言基线。
 *
 * <p><b>测什么</b>：对同一份可复现数据（学生数×题量梯度 + 坏答卷边角），多轮分别记录
 * <ol>
 *   <li>整体导出（生产 {@code exportQuestionStats} 原味调用）的耗时/分配/堆峰值/GC 后堆/临时盘峰值/输出字节/SQL 次数；</li>
 *   <li>分相位（测试侧同步骤副本，输出经 XLSX 单元格逐格比对与生产实现交叉校验）：
 *       快照装载 → 答卷分页+逐题解析 → pairs 积累 → pairs 保留量 → 区分度排序 → SXSSF 组装 →
 *       工作簿序列化（临时文件） → 最终 {@code toByteArray}。</li>
 * </ol>
 *
 * <p><b>口径与边界</b>（都必须随结果一起回报，不得省略）：
 * <ul>
 *   <li>分配量用 {@code com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes} 差分的<b>精确</b>字节数（单线程测量）；
 *       堆峰值用采样线程读 {@code MemoryMXBean} 的 used 值取最大，是<b>上界</b>（含测量夹具自身）；</li>
 *   <li>pairs 保留量 = 「建成 pairs 并 GC 后的 used 堆」−「释放 pairs 并 GC 后的 used 堆」，是 GC 后差分，
 *       不是独立臂分位数相减；逐轮原样上报，不取最好值；</li>
 *   <li>临时盘只统计 {@code java.io.tmpdir/poifiles}（POI 默认刷盘目录）；</li>
 *   <li>本工具跑隔离 H2 内存库 + 进程内直调 Service，<b>不是</b>真实 MySQL/Tomcat 生产性能，
 *       不外推生产 P99；不写共享 dev、不启 Docker、不改 {@code src/main}/schema/Mapper/前端/OpenAPI/JVM 参数。</li>
 * </ul>
 *
 * <p><b>运行</b>（仓库根）：
 * <pre>
 * mvnw.cmd -q test -Dtest=QuestionStatsExportAttributionMeasureIT -DfailIfNoTests=false ^
 *     -Dmeasure.rev=&lt;sha7&gt; -Dmeasure.label=old-run1 -Dmeasure.out=target/measure
 * </pre>
 * 机器可读结果写到 {@code ${measure.out}/question-stats-attribution-${measure.label}.json}。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("题目统计导出内存归因测量（仅显式 -Dtest 运行；不进 Surefire 全量门禁）")
class QuestionStatsExportAttributionMeasureIT {

    // ==================== 固定口径 ====================

    /** 生产口径常量（与 ScoreExportService / ExcelSheetWriter 一致，仅测量副本使用）。 */
    private static final int PAGE_SIZE = 500;
    private static final int SXSSF_WINDOW = 100;
    private static final BigDecimal GROUP_RATIO = new BigDecimal("0.27");

    /** 学生数×题量梯度 + 坏答卷/少样本边角；题量覆盖 >SXSSF 窗口 100 与 ≤窗口 两种情况。 */
    private record Combo(String label, int students, int questions, int badAnswers, String note) {
    }

    private static final List<Combo> COMBOS = List.of(
            new Combo("s1000-q20", 1000, 20, 0, "基线：学生数中等、题量小于 SXSSF 窗口"),
            new Combo("s1000-q150", 1000, 150, 0, "题量大于 SXSSF 窗口：触发临时刷盘"),
            new Combo("s3000-q60", 3000, 60, 0, "学生数放大：pairs = N×Q"),
            new Combo("s100-q20-bad2", 100, 20, 2, "含 2 份损坏答卷：走逐份降级路径"),
            new Combo("s3-q20-bad1", 3, 20, 1, "少样本：n=2 → 区分度「样本不足」")
    );

    private static final long EXAM_ID_BASE = 941_000_000L;
    private static final long QUESTION_ID_BASE = 941_500_000L;
    private static final long PAPER_ID_BASE = 941_900_000L;

    /** 客观题占比 80%（其余为简答，且不建主观批改行 → 未批按 0 分计入）。 */
    private static final double OBJECTIVE_RATIO = 0.8;
    private static final double OBJECTIVE_SCORE = 2.0;
    private static final double SHORT_ANSWER_SCORE = 5.0;

    /** 测量轮数（逐轮原样上报，不挑最好一轮）。 */
    private static final int ROUNDS = 3;

    private static final String DATA_RULE =
            "每 combo 一场考试：exams.status=4/published=1；exam_snapshots.paper_json 含 questions"
            + "（前 80% 为 SINGLE，choices=[A,B,C,D]，correctAnswer=A，score=2；其余为 SHORT_ANSWER，"
            + "choices=null，correctAnswer=参考答案，score=5）；exam_submissions 逐份直插 status=3、grading_status=1，"
            + "answers JSON：客观题 answer=((i+qi)%3==0)?B:A ⇒ 得 2 或 0，简答题无 subjective_grades 行 ⇒ 未批计 0；"
            + "total_score=objective_score=2×(该生答对的客观题数)，subjective_score=0；"
            + "坏答卷为最后 badAnswers 份，answers=?{ 非法 JSON 或 NULL ⇒ 解析抛错被跳过；"
            + "不建 users 行（exportQuestionStats 不读 users），登录上下文用合成 ADMIN LoginUser（只过 owner 校验）。";

    private static final Map<String, String> METRIC_DEFS = Map.ofEntries(
            Map.entry("production.millis", "生产 exportQuestionStats 单次调用墙钟耗时"),
            Map.entry("production.allocatedMB", "该调用期间当前线程分配字节差分（精确分配，非采样）"),
            Map.entry("production.heapPeakMB", "整段调用期间采样线程观测的 used 堆最大值（上界，含夹具）"),
            Map.entry("production.heapUsedAfterGcBeforeMB/AfterMB", "调用前后多次 System.gc() 后 used 堆的最小值"),
            Map.entry("production.tempPeakBytes/tempGrowthPeakBytes/tempFilesPeak",
                    "java.io.tmpdir/poifiles 下文件字节/相对相位起点增量/个数的峰值"),
            Map.entry("production.outputBytes", "返回的 XLSX 字节数"),
            Map.entry("production.sqlQueries", "MyBatis Executor.query 调用次数（Interceptor 计数）"),
            Map.entry("replica.phases[].millis/allocatedMB", "分相位耗时与精确分配（副本步骤与生产逐句对齐，输出单元格逐格比对）"),
            Map.entry("replica.pairsRetainedMB", "建成 pairs 并 GC 后堆 − 释放 pairs 并 GC 后堆（GC 后差分，逐轮上报）"),
            Map.entry("replica.sql.pagingResolve/pagingResolveFallback", "分页+解析阶段 SQL 次数；损坏答卷触发逐份降级的额外次数"),
            Map.entry("cellsEqualProduction", "副本 XLSX 与生产 XLSX 用 DataFormatter 逐格比对结果"),
            Map.entry("oracle.*", "测试侧独立重算的题序/平均分/得分率/答对率/作答人数/区分度与生产单元格对照"),
            Map.entry("note", "隔离 H2 + 进程内直调 Service，非真实 MySQL/Tomcat 性能；不外推生产 P99")
    );

    // ==================== 测试侧 SQL 计数（仅本测试上下文） ====================

    /**
     * 计数所有 MyBatis 查询（MyBatis-Plus 会收集容器内 {@link Interceptor} bean 作为插件）。
     * 只统计、不改写 SQL 与结果。
     */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            CacheKey.class, BoundSql.class})
    })
    static class CountingInterceptor implements Interceptor {
        private static final LongAdder COUNT = new LongAdder();

        static void reset() {
            COUNT.reset();
        }

        static long count() {
            return COUNT.sum();
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            COUNT.increment();
            return invocation.proceed();
        }
    }

    @TestConfiguration
    static class MeasureSqlConfig {
        @Bean
        CountingInterceptor measureCountingInterceptor() {
            return new CountingInterceptor();
        }
    }

    // ==================== 依赖 ====================

    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ScoreExportService scoreExportService;
    @Autowired
    private GradingPaperReader paperReader;
    @Autowired
    private QuestionScoreResolver scoreResolver;
    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;

    // ==================== 主流程 ====================

    @Test
    @DisplayName("同数据多轮归因：整体 + 分相位 + pairs 保留量 + oracle 校验，落盘机器可读结果")
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
        result.put("heapCapNote", "堆上限由运行 JVM 决定，多轮必须同设置；本工具不注入 -Xmx");
        result.put("dataRule", DATA_RULE);
        result.put("metricDefinitions", METRIC_DEFS);
        result.put("loadParams", loadParams());

        // 合成 ADMIN 登录上下文：exportQuestionStats 只做 owner 校验（ADMIN 层级放行），不触接口层。
        LoginUser admin = new LoginUser();
        admin.setId(-1L);
        admin.setUsername("measure-admin");
        admin.setRoleLevel(3);
        SecurityUtil.set(admin);
        try {
            long t0 = System.currentTimeMillis();
            prepareData();
            result.put("prepareDataMillis", System.currentTimeMillis() - t0);

            // 预热（不计入测量窗口）：JIT 编译两条路径。
            for (int i = 0; i < 2; i++) {
                scoreExportService.exportQuestionStats(examIdOf(COMBOS.get(0)));
                runPhased(COMBOS.get(0), examIdOf(COMBOS.get(0)));
            }

            // oracle：测试侧独立重算，与生产 XLSX 逐格对照（每 combo 一次）。
            List<Map<String, Object>> oracle = new ArrayList<>();
            for (Combo combo : COMBOS) {
                Map<String, Object> o = validateOracle(combo);
                oracle.add(o);
                System.out.println("MEASURE oracle " + o);
            }
            result.put("oracle", oracle);

            List<Map<String, Object>> rounds = new ArrayList<>();
            for (int round = 1; round <= ROUNDS; round++) {
                Map<String, Object> roundResult = new LinkedHashMap<>();
                roundResult.put("round", round);
                List<Map<String, Object>> perCombo = new ArrayList<>();
                for (Combo combo : COMBOS) {
                    Map<String, Object> m = measureOne(combo, round);
                    perCombo.add(m);
                    System.out.println("MEASURE " + m);
                }
                roundResult.put("combos", perCombo);
                rounds.add(roundResult);
            }
            result.put("rounds", rounds);
        } finally {
            SecurityUtil.clear();
        }

        result.put("finishedAt", LocalDateTime.now().toString());
        Path jsonPath = outDir.resolve("question-stats-attribution-" + label + ".json");
        Files.writeString(jsonPath, om.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                StandardCharsets.UTF_8);
        System.out.println("MEASURE wrote " + jsonPath.toAbsolutePath());
    }

    /** 一个 combo 一轮：生产整体 + 分相位副本，并做单元格交叉校验。 */
    private Map<String, Object> measureOne(Combo combo, int round) throws Exception {
        long examId = examIdOf(combo);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("combo", combo.label());
        r.put("students", combo.students());
        r.put("questions", combo.questions());
        r.put("badAnswers", combo.badAnswers());
        r.put("round", round);

        // ---- 生产整体调用 ----
        CountingInterceptor.reset();
        long heapBefore = heapAfterGc();
        Sampler sampler = new Sampler();
        sampler.start();
        long a0 = allocated();
        long t0 = System.nanoTime();
        byte[] prod = scoreExportService.exportQuestionStats(examId);
        long prodNanos = System.nanoTime() - t0;
        long prodAlloc = allocated() - a0;
        sampler.close();
        long prodSql = CountingInterceptor.count();
        long heapAfter = heapAfterGc();

        Map<String, Object> prodM = new LinkedHashMap<>();
        prodM.put("millis", round3(prodNanos / 1e6));
        prodM.put("allocatedMB", round3(prodAlloc / 1048576.0));
        prodM.put("heapPeakMB", round3(sampler.heapPeak / 1048576.0));
        prodM.put("heapUsedAfterGcBeforeMB", round3(heapBefore / 1048576.0));
        prodM.put("heapUsedAfterGcAfterMB", round3(heapAfter / 1048576.0));
        prodM.put("heapUsedDeltaAfterGcMB", round3((heapAfter - heapBefore) / 1048576.0));
        prodM.put("tempPeakBytes", sampler.tempPeak);
        prodM.put("tempGrowthPeakBytes", sampler.tempGrowthPeak);
        prodM.put("tempFilesPeak", sampler.tempFilesPeak);
        prodM.put("outputBytes", prod.length);
        prodM.put("sqlQueries", prodSql);
        r.put("production", prodM);

        // ---- 分相位副本 ----
        PhasedRun ph = runPhased(combo, examId);
        r.put("replica", ph.summary());

        // ---- 交叉校验：生产与副本 XLSX 单元格逐格一致（副本可信的前提） ----
        List<List<String>> prodCells = readCells(prod);
        List<List<String>> replicaCells = readCells(ph.bytes());
        boolean equal = prodCells.equals(replicaCells);
        r.put("cellsEqualProduction", equal);
        if (!equal) {
            r.put("cellsDiff", firstDiff(prodCells, replicaCells));
        }
        // 更强等价判据：逐 zip 条目内容一致（忽略 docProps/core.xml 的文档元数据），
        // 因此「输出字节数偶尔 ±1」只是压缩/元数据差异，不影响内容等价。
        r.put("zipContentEqual", zipContentEqual(prod, ph.bytes()));
        r.put("outputBytesEqual", prod.length == ph.bytes().length);
        // 夹具自身断言：SQL 计数必须生效，否则本次证据无效。
        if (prodSql <= 0) {
            throw new IllegalStateException("SQL 计数未生效（Interceptor 未注册），本轮证据无效");
        }
        return r;
    }

    // ==================== 分相位副本（与生产逐句对齐） ====================

    private record PhasedRun(byte[] bytes, List<Map<String, Object>> phases, Map<String, Object> extra) {
        Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phases", phases);
            m.putAll(extra);
            return m;
        }
    }

    /**
     * 与 {@code ScoreExportService.exportQuestionStats} 同步骤的副本，仅在相位边界插入计时/分配。
     * 唯一顺序调整：把逐题 {@code discrimination} 提到写表之前统一算（生产在写行时逐题算），
     * 计算量与前值完全相同、输出不变，只为把「排序」与「SXSSF 写」分成两个相位。
     */
    private PhasedRun runPhased(Combo combo, long examId) throws Exception {
        List<Map<String, Object>> phases = new ArrayList<>();
        Map<String, Object> extra = new LinkedHashMap<>();
        CountingInterceptor.reset();
        long startHeapBefore = heapAfterGc();
        Sampler sampler = new Sampler();
        sampler.start();
        long wallStart = System.nanoTime();

        // 相位 1：快照装载
        long a0 = allocated();
        long t0 = System.nanoTime();
        long sqlBefore = CountingInterceptor.count();
        GradingPaper paper = paperReader.readByExamId(examId);
        List<GradingQuestion> questions = paper.questions();
        phases.add(phase("paperLoad", System.nanoTime() - t0, allocated() - a0));
        extra.put("sql.paperLoad", CountingInterceptor.count() - sqlBefore);

        Map<Long, double[]> sums = new LinkedHashMap<>();
        Map<Long, int[]> counts = new LinkedHashMap<>();
        Map<Long, List<double[]>> pairs = new LinkedHashMap<>();

        // 相位 2：答卷分页 + 逐题解析；相位 3：pairs/sums/counts 积累
        long resolveNanos = 0;
        long resolveAlloc = 0;
        long aggregateNanos = 0;
        long aggregateAlloc = 0;
        long pagingSqlTotal = 0;
        long fallbackExtraSql = 0;
        long lastId = 0;
        while (true) {
            sqlBefore = CountingInterceptor.count();
            List<GradingSubmission> page = gradingSubmissionMapper.selectList(
                    Wrappers.<GradingSubmission>lambdaQuery()
                            .eq(GradingSubmission::getExamId, examId)
                            .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                            .gt(GradingSubmission::getId, lastId)
                            .orderByAsc(GradingSubmission::getId)
                            .last("LIMIT " + PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }
            long rSqlBefore = CountingInterceptor.count();
            long ra0 = allocated();
            long rt0 = System.nanoTime();
            Map<Long, Map<Long, QuestionScoreResolver.ResolvedQuestionScore>> resolved =
                    resolveQuietly(examId, page, paper);
            resolveNanos += System.nanoTime() - rt0;
            resolveAlloc += allocated() - ra0;
            // 降级路径额外 SQL：整批失败后被逐份重解析的份数（每份 1 次主观批改查询）
            long extraSql = CountingInterceptor.count() - rSqlBefore - 1;
            if (extraSql > 0) {
                fallbackExtraSql += extraSql;
            }

            long aa0 = allocated();
            long at0 = System.nanoTime();
            for (GradingSubmission submission : page) {
                Map<Long, QuestionScoreResolver.ResolvedQuestionScore> perQuestion = resolved.get(submission.getId());
                if (perQuestion == null) {
                    continue;
                }
                double total = submission.getTotalScore() == null
                        ? 0 : submission.getTotalScore().doubleValue();
                for (GradingQuestion question : questions) {
                    QuestionScoreResolver.ResolvedQuestionScore score = perQuestion.get(question.questionId());
                    if (score == null) {
                        continue;
                    }
                    sums.computeIfAbsent(question.questionId(), k -> new double[1])[0]
                            += score.score().doubleValue();
                    counts.computeIfAbsent(question.questionId(), k -> new int[2])[0]++;
                    if (score.fullScore()) {
                        counts.get(question.questionId())[1]++;
                    }
                    pairs.computeIfAbsent(question.questionId(), k -> new ArrayList<>())
                            .add(new double[]{total, score.score().doubleValue()});
                }
            }
            aggregateNanos += System.nanoTime() - at0;
            aggregateAlloc += allocated() - aa0;
            pagingSqlTotal += CountingInterceptor.count() - sqlBefore;

            lastId = page.get(page.size() - 1).getId();
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }
        phases.add(phase("pagingResolve", resolveNanos, resolveAlloc));
        phases.add(phase("aggregatePairs", aggregateNanos, aggregateAlloc));
        extra.put("sql.pagingResolve", pagingSqlTotal);
        extra.put("sql.pagingResolveFallbackExtra", fallbackExtraSql);

        // pairs 保留量：GC 后差分（含 pairs 与不含 pairs 的 used 堆）
        long heapWithPairs = heapAfterGc();

        // 相位 4：区分度排序（逐题复制 + 降序排序）
        a0 = allocated();
        t0 = System.nanoTime();
        Map<Long, String> disc = new LinkedHashMap<>();
        for (GradingQuestion question : questions) {
            int[] c = counts.getOrDefault(question.questionId(), new int[2]);
            disc.put(question.questionId(), c[0] == 0 ? "-"
                    : discrimination(pairs.getOrDefault(question.questionId(), List.of()),
                            question.score().doubleValue()));
        }
        phases.add(phase("discriminationSort", System.nanoTime() - t0, allocated() - a0));

        pairs = null;
        long heapWithoutPairs = heapAfterGc();
        extra.put("pairsRetainedMB", round3((heapWithPairs - heapWithoutPairs) / 1048576.0));
        extra.put("heapWithPairsAfterGcMB", round3(heapWithPairs / 1048576.0));
        extra.put("heapWithoutPairsAfterGcMB", round3(heapWithoutPairs / 1048576.0));

        // 相位 5：SXSSF 组装（建表/表头/行/列宽），相位 6：工作簿序列化，相位 7：最终 toByteArray
        a0 = allocated();
        t0 = System.nanoTime();
        SXSSFWorkbook workbook = new SXSSFWorkbook(SXSSF_WINDOW);
        SXSSFSheet sheet = ExcelSheetWriter.createSheet(workbook, "题目统计");
        ExcelSheetWriter.writeHeader(sheet, "题号", "题型", "题干", "满分", "平均分", "得分率",
                "答对率(满分率)", "区分度", "作答人数");
        int rowIndex = 1;
        for (GradingQuestion question : questions) {
            Row row = sheet.createRow(rowIndex++);
            int[] questionCounts = counts.getOrDefault(question.questionId(), new int[2]);
            int n = questionCounts[0];
            int full = questionCounts[1];
            double sum = sums.getOrDefault(question.questionId(), new double[1])[0];
            row.createCell(0).setCellValue(question.number());
            row.createCell(1).setCellValue(labelOf(question.type()));
            row.createCell(2).setCellValue(question.content());
            row.createCell(3).setCellValue(decimal(question.score()));
            row.createCell(4).setCellValue(n == 0 ? "-" : round2(sum / n));
            row.createCell(5).setCellValue(n == 0 || question.score().doubleValue() == 0
                    ? "-" : percent(sum / n / question.score().doubleValue()));
            row.createCell(6).setCellValue(n == 0 ? "-" : percent((double) full / n));
            row.createCell(7).setCellValue(disc.get(question.questionId()));
            row.createCell(8).setCellValue(n);
        }
        ExcelSheetWriter.autoSize(sheet, 9);
        phases.add(phase("xlsxCompose", System.nanoTime() - t0, allocated() - a0));

        a0 = allocated();
        t0 = System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        phases.add(phase("xlsxSerialize", System.nanoTime() - t0, allocated() - a0));

        a0 = allocated();
        t0 = System.nanoTime();
        byte[] bytes = out.toByteArray();
        phases.add(phase("toByteArray", System.nanoTime() - t0, allocated() - a0));

        workbook.dispose();
        try {
            workbook.close();
        } catch (IOException ignored) {
            // 与 ExcelSheetWriter 一致：关闭异常只记日志
        }

        long wallNanos = System.nanoTime() - wallStart;
        sampler.close();
        long startHeapAfter = heapAfterGc();

        extra.put("wallMillis", round3(wallNanos / 1e6));
        extra.put("sqlTotal", CountingInterceptor.count());
        extra.put("outputBytes", bytes.length);
        extra.put("tempPeakBytes", sampler.tempPeak);
        extra.put("tempGrowthPeakBytes", sampler.tempGrowthPeak);
        extra.put("tempFilesPeak", sampler.tempFilesPeak);
        extra.put("heapPeakMB", round3(sampler.heapPeak / 1048576.0));
        extra.put("heapUsedAfterGcBeforeMB", round3(startHeapBefore / 1048576.0));
        extra.put("heapUsedAfterGcAfterMB", round3(startHeapAfter / 1048576.0));
        extra.put("rowCount", questions.size());
        extra.put("sxssfWindow", SXSSF_WINDOW);
        return new PhasedRun(bytes, phases, extra);
    }

    /** 与生产 {@code resolveQuietly} 同语义：整批失败时逐份降级、坏卷跳过。 */
    private Map<Long, Map<Long, QuestionScoreResolver.ResolvedQuestionScore>> resolveQuietly(
            Long examId, List<GradingSubmission> page, GradingPaper paper) {
        try {
            return scoreResolver.resolveBatch(page, paper);
        } catch (Exception e) {
            // 与生产一致：warn 后降级
        }
        Map<Long, Map<Long, QuestionScoreResolver.ResolvedQuestionScore>> result = new LinkedHashMap<>();
        for (GradingSubmission submission : page) {
            try {
                result.put(submission.getId(), scoreResolver.resolve(submission, paper));
            } catch (Exception e) {
                // 坏卷跳过
            }
        }
        return result;
    }

    /** 与生产 {@code discrimination} 逐句一致（前后 27% 高低分组法）。 */
    private static String discrimination(List<double[]> pairs, double max) {
        int n = pairs.size();
        int groupSize = (int) Math.floor(n * GROUP_RATIO.doubleValue());
        if (n < 4 || groupSize < 1) {
            return "样本不足";
        }
        List<double[]> sorted = new ArrayList<>(pairs);
        sorted.sort((a, b) -> Double.compare(b[0], a[0]));
        double highSum = 0;
        double lowSum = 0;
        for (int i = 0; i < groupSize; i++) {
            highSum += sorted.get(i)[1];
            lowSum += sorted.get(n - 1 - i)[1];
        }
        return round2((highSum / groupSize - lowSum / groupSize) / max);
    }

    // ==================== oracle：测试侧独立重算 ====================

    /**
     * 用已知生成规则独立重算每题统计，与生产 XLSX 逐格对照。
     * 覆盖：题序（快照 questions 顺序）、平均分、得分率、答对率、作答人数、
     * 区分度「总分降序 + 并列稳定次序 + 前后各 floor(n×0.27)」、少样本「样本不足」、坏卷跳过。
     */
    private Map<String, Object> validateOracle(Combo combo) throws Exception {
        long examId = examIdOf(combo);
        byte[] prod = scoreExportService.exportQuestionStats(examId);
        List<List<String>> cells = readCells(prod);

        int good = combo.students() - combo.badAnswers();
        int objectiveCount = objectiveCount(combo.questions());
        List<String> mismatches = new ArrayList<>();

        for (int qi = 0; qi < combo.questions(); qi++) {
            long questionId = questionIdOf(combo, qi);
            boolean objective = qi < objectiveCount;
            double max = objective ? OBJECTIVE_SCORE : SHORT_ANSWER_SCORE;

            double sum = 0;
            int full = 0;
            List<double[]> pairs = new ArrayList<>(good);
            for (int i = 0; i < good; i++) {
                boolean correct = objective && ((i + qi) % 3 != 0);
                double score = correct ? OBJECTIVE_SCORE : 0;
                sum += score;
                if (correct) {
                    full++;
                }
                pairs.add(new double[]{totalOf(i, objectiveCount, combo.questions()), score});
            }
            int n = good;
            String expNumber = String.valueOf(qi + 1);
            String expType = objective ? "单选" : "简答";
            String expContent = contentOf(qi);
            String expMax = decimal(BigDecimal.valueOf(max));
            String expAvg = n == 0 ? "-" : round2(sum / n);
            String expRate = n == 0 || max == 0 ? "-" : percent(sum / n / max);
            String expCorrectRate = n == 0 ? "-" : percent((double) full / n);
            String expDisc = n == 0 ? "-" : discrimination(pairs, max);
            String expN = String.valueOf(n);

            List<String> expected = List.of(expNumber, expType, expContent, expMax, expAvg,
                    expRate, expCorrectRate, expDisc, expN);
            // 行下标：表头占第 0 行
            if (qi + 1 >= cells.size()) {
                mismatches.add("题干缺失行 qi=" + qi);
                continue;
            }
            List<String> actual = cells.get(qi + 1);
            for (int c = 0; c < expected.size(); c++) {
                String av = c < actual.size() ? actual.get(c) : "<缺列>";
                if (!expected.get(c).equals(av)) {
                    mismatches.add("qi=" + qi + " 列" + c + " 期望=" + expected.get(c) + " 实际=" + av);
                }
            }
        }

        Map<String, Object> o = new LinkedHashMap<>();
        o.put("combo", combo.label());
        o.put("goodSubmissions", good);
        o.put("badAnswers", combo.badAnswers());
        o.put("objectiveQuestions", objectiveCount);
        o.put("rowCount", cells.size() - 1);
        o.put("headerRow", cells.isEmpty() ? List.of() : cells.get(0));
        o.put("oracleMatch", mismatches.isEmpty());
        if (!mismatches.isEmpty()) {
            o.put("mismatches", mismatches);
        }
        return o;
    }

    // ==================== 数据准备 ====================

    private void prepareData() throws Exception {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (int ci = 0; ci < COMBOS.size(); ci++) {
            Combo combo = COMBOS.get(ci);
            long examId = examIdOf(combo);
            jdbc.update("DELETE FROM exam_submissions WHERE exam_id = ?", examId);
            jdbc.update("DELETE FROM exam_snapshots WHERE exam_id = ?", examId);
            jdbc.update("DELETE FROM exams WHERE id = ?", examId);

            jdbc.update("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes,"
                            + " status, published, created_by, version, created_time, updated_time)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    examId, "题目统计归因-" + combo.label(), PAPER_ID_BASE + ci, now, now, 60, 4, 1, 941999999L, 0);
            jdbc.update("INSERT INTO exam_snapshots (exam_id, exam_json, paper_json, version, created_by, created_time)"
                            + " VALUES (?,?,?,1,?,CURRENT_TIMESTAMP)",
                    examId, "{}", paperJson(combo), 941999999L);

            int objectiveCount = objectiveCount(combo.questions());
            int good = combo.students() - combo.badAnswers();
            List<Object[]> batch = new ArrayList<>(combo.students());
            for (int i = 0; i < combo.students(); i++) {
                boolean bad = i >= good;
                String answers;
                if (bad) {
                    // 一半坏卷答案非法结构、一半 NULL（均触发解析异常 → 跳过）
                    answers = (i % 2 == 0) ? "{\"broken\"" : null;
                } else {
                    answers = answersJson(combo, i);
                }
                double objectiveScore = 0;
                for (int qi = 0; qi < objectiveCount; qi++) {
                    if ((i + qi) % 3 != 0) {
                        objectiveScore += OBJECTIVE_SCORE;
                    }
                }
                BigDecimal total = BigDecimal.valueOf(objectiveScore).setScale(1, RoundingMode.HALF_UP);
                batch.add(new Object[]{examId, 941_800_000L + (long) ci * 1_000_000L + i, now, now, now, answers,
                        ExamSubmission.STATUS_GRADED, total, BigDecimal.ZERO.setScale(1), total, 1, 0});
            }
            jdbc.batchUpdate("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                            + " submit_time, answers, status, objective_score, subjective_score, total_score,"
                            + " grading_status, partial_graded, version, created_time, updated_time)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    batch);
        }
    }

    private String paperJson(Combo combo) throws Exception {
        ObjectNode root = om.createObjectNode();
        root.put("paperId", PAPER_ID_BASE);
        root.put("title", "题目统计归因-" + combo.label());
        root.put("totalScore", combo.questions() * OBJECTIVE_SCORE);
        ArrayNode questions = root.putArray("questions");
        int objectiveCount = objectiveCount(combo.questions());
        for (int qi = 0; qi < combo.questions(); qi++) {
            boolean objective = qi < objectiveCount;
            ObjectNode q = questions.addObject();
            q.put("number", qi + 1);
            q.put("questionId", questionIdOf(combo, qi));
            q.put("type", objective ? QuestionType.SINGLE.getCode() : QuestionType.SHORT_ANSWER.getCode());
            q.put("content", contentOf(qi));
            if (objective) {
                ArrayNode choices = q.putArray("choices");
                choices.add("A");
                choices.add("B");
                choices.add("C");
                choices.add("D");
            }
            q.put("correctAnswer", objective ? "A" : "参考答案");
            q.put("score", objective ? OBJECTIVE_SCORE : SHORT_ANSWER_SCORE);
        }
        return om.writeValueAsString(root);
    }

    private String answersJson(Combo combo, int i) throws Exception {
        ObjectNode answers = om.createObjectNode();
        int objectiveCount = objectiveCount(combo.questions());
        for (int qi = 0; qi < objectiveCount; qi++) {
            answers.put(String.valueOf(questionIdOf(combo, qi)), ((i + qi) % 3 == 0) ? "B" : "A");
        }
        for (int qi = objectiveCount; qi < combo.questions(); qi++) {
            answers.put(String.valueOf(questionIdOf(combo, qi)), "学生答案");
        }
        return om.writeValueAsString(answers);
    }

    /** 学生 i 的总分（只统计可解析答卷口径，用于 oracle）。 */
    private static double totalOf(int i, int objectiveCount, int questions) {
        double total = 0;
        for (int qi = 0; qi < objectiveCount; qi++) {
            if ((i + qi) % 3 != 0) {
                total += OBJECTIVE_SCORE;
            }
        }
        return total;
    }

    private static int objectiveCount(int questions) {
        return (int) Math.round(questions * OBJECTIVE_RATIO);
    }

    private static String contentOf(int qi) {
        return "第" + (qi + 1) + "题题干";
    }

    private static long examIdOf(Combo combo) {
        return EXAM_ID_BASE + COMBOS.indexOf(combo);
    }

    private static long questionIdOf(Combo combo, int qi) {
        return QUESTION_ID_BASE + (long) COMBOS.indexOf(combo) * 1000L + qi;
    }

    // ==================== XLSX 读取与比对 ====================

    private static List<List<String>> readCells(byte[] xlsx) throws IOException {
        DataFormatter formatter = new DataFormatter();
        List<List<String>> rows = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                int last = row.getLastCellNum();
                for (int c = 0; c < last; c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private static String firstDiff(List<List<String>> a, List<List<String>> b) {
        int rows = Math.max(a.size(), b.size());
        for (int r = 0; r < rows; r++) {
            List<String> ra = r < a.size() ? a.get(r) : List.of();
            List<String> rb = r < b.size() ? b.get(r) : List.of();
            if (!ra.equals(rb)) {
                return "row=" + r + " prod=" + ra + " replica=" + rb;
            }
        }
        return "<none>";
    }

    /**
     * 逐 zip 条目内容比对（忽略 {@code docProps/core.xml} 文档元数据：创建时间戳等每次不同）。
     * 输出字节数偶发 ±1 属压缩/元数据差异，本判据证明内容等价。
     */
    private static boolean zipContentEqual(byte[] a, byte[] b) {
        Map<String, byte[]> ea = zipEntries(a);
        Map<String, byte[]> eb = zipEntries(b);
        if (!ea.keySet().equals(eb.keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> e : ea.entrySet()) {
            if (!Arrays.equals(e.getValue(), eb.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, byte[]> zipEntries(byte[] xlsx) {
        Map<String, byte[]> entries = new TreeMap<>();
        // 用 ZipFile（走中央目录）而非 ZipInputStream：SXSSF 流式写出的条目用 data descriptor，
        // ZipInputStream 会因本地头 size=0 抛 "invalid entry size"。
        // 临时文件建在 java.io.tmpdir 根（非 poifiles），不影响临时盘测量，且 finally 删除。
        Path tmp = null;
        try {
            tmp = Files.createTempFile("xlsx-cmp-", ".xlsx");
            Files.write(tmp, xlsx);
            try (ZipFile zf = new ZipFile(tmp.toFile())) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry entry = en.nextElement();
                    if ("docProps/core.xml".equals(entry.getName())) {
                        continue;
                    }
                    try (InputStream is = zf.getInputStream(entry)) {
                        entries.put(entry.getName(), is.readAllBytes());
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("zip 条目读取失败", e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时文件删除失败不影响判定
                }
            }
        }
        return entries;
    }

    // ==================== 格式化（与生产同口径） ====================

    private static String decimal(BigDecimal value) {
        return value == null ? "-" : value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(double value) {
        return BigDecimal.valueOf(value * 100).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String labelOf(QuestionType type) {
        return switch (type) {
            case SINGLE -> "单选";
            case MULTIPLE -> "多选";
            case JUDGE -> "判断";
            case SHORT_ANSWER -> "简答";
        };
    }

    // ==================== 采样与度量工具 ====================

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

    /** 当前线程累计分配字节；平台不支持时返回 -1 并在结果中如实体现。 */
    private static long allocated() {
        return ALLOC_BEAN == null ? -1 : ALLOC_BEAN.getCurrentThreadAllocatedBytes();
    }

    private static long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /** 多次 GC 后 used 堆的最小值（保留量差分的基准；取最小以压制偶发未回收噪声）。 */
    private static long heapAfterGc() {
        long min = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            System.gc();
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            min = Math.min(min, heapUsed());
        }
        return min;
    }

    private static Map<String, Object> phase(String name, long nanos, long allocBytes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", name);
        m.put("millis", round3(nanos / 1e6));
        m.put("allocatedMB", allocBytes < 0 ? -1 : round3(allocBytes / 1048576.0));
        return m;
    }

    /**
     * 采样线程：used 堆最大值 + {@code java.io.tmpdir/poifiles} 字节/文件数峰值。
     *
     * <p>临时盘同时记录绝对值与「相对本相位起点基线的增量」：前一轮 combo 的临时文件若尚未回收，
     * 绝对值会把上一轮的残留算进本轮，增量才是本轮真实刷盘量。
     */
    private static final class Sampler implements AutoCloseable {
        private final Thread thread;
        private volatile boolean running = true;
        private volatile long heapPeak;
        private volatile long tempPeak;
        private volatile long tempGrowthPeak;
        private volatile int tempFilesPeak;
        private long tempBaseline;
        private final Path poiDir = Paths.get(System.getProperty("java.io.tmpdir"), "poifiles");

        Sampler() {
            thread = new Thread(this::loop, "question-stats-measure-sampler");
            thread.setDaemon(true);
        }

        void start() {
            tempBaseline = dirStats(poiDir)[0];
            thread.start();
        }

        private void loop() {
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            int i = 0;
            while (running) {
                long used = mem.getHeapMemoryUsage().getUsed();
                if (used > heapPeak) {
                    heapPeak = used;
                }
                if ((i++ % 10) == 0) {
                    long[] stats = dirStats(poiDir);
                    if (stats[0] > tempPeak) {
                        tempPeak = stats[0];
                        tempFilesPeak = (int) stats[1];
                    }
                    long growth = stats[0] - tempBaseline;
                    if (growth > tempGrowthPeak) {
                        tempGrowthPeak = growth;
                    }
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        public void close() {
            running = false;
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static long[] dirStats(Path dir) {
        if (!Files.isDirectory(dir)) {
            return new long[]{0, 0};
        }
        long bytes = 0;
        long files = 0;
        try (var stream = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(p)) {
                    try {
                        bytes += Files.size(p);
                        files++;
                    } catch (IOException ignored) {
                        // 文件可能正被 dispose 删除，忽略竞态
                    }
                }
            }
        } catch (IOException ignored) {
            // 目录可能刚被删除，忽略
        }
        return new long[]{bytes, files};
    }

    private Map<String, Object> loadParams() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pageSize", PAGE_SIZE);
        m.put("sxssfWindow", SXSSF_WINDOW);
        m.put("rounds", ROUNDS);
        m.put("objectiveRatio", OBJECTIVE_RATIO);
        m.put("objectiveScore", OBJECTIVE_SCORE);
        m.put("shortAnswerScore", SHORT_ANSWER_SCORE);
        m.put("combos", COMBOS.stream().map(c -> Map.of(
                "label", c.label(), "students", c.students(), "questions", c.questions(),
                "badAnswers", c.badAnswers(), "note", c.note())).toList());
        m.put("allocMeasurement", "com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes 差分");
        m.put("heapPeakMeasurement", "1ms 采样线程读 MemoryMXBean used 取最大（上界，含夹具）");
        m.put("pairsRetainedMeasurement", "GC 后 used 堆差分（含/不含 pairs），逐轮上报");
        m.put("sqlMeasurement", "MyBatis Executor.query Interceptor 计数");
        m.put("tempMeasurement", "java.io.tmpdir/poifiles 递归字节/文件数峰值");
        m.put("coldStartExcluded", "Spring 上下文启动与数据准备在测量窗口之外；预热 2 轮不计入");
        return m;
    }

    // ==================== 数值小工具 ====================

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** 保留：排查用。 */
    @SuppressWarnings("unused")
    private static List<Integer> ints(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int v : values) {
            list.add(v);
        }
        return list;
    }

    @SuppressWarnings("unused")
    private static double mean(long[] ns) {
        long s = 0;
        for (long v : ns) {
            s += v;
        }
        return s / (double) ns.length;
    }

    @SuppressWarnings("unused")
    private static String arraysToString(double[] values) {
        return Arrays.toString(values);
    }
}