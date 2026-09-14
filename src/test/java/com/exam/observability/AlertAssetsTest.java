package com.exam.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 观测栈配置资产静态校验（阶段 11 task4）。
 *
 * <p>为什么要有这个测试：Prometheus/Grafana 对「写错的指标名」不报错，只会让规则永久静默
 * （no data 不等于健康）。本测试把「名字对不对、结构全不全、引用对不对」变成会失败的断言，
 * 不依赖 Docker 即可在 mvn test 里守住配置正确性。
 */
class AlertAssetsTest {

    /** surefire 的 cwd 是项目根，用相对路径定位观测栈资产 */
    private static final Path OBS = Paths.get("docker", "observability");
    private static final Path RULES_YML = OBS.resolve("prometheus/rules/exam-online-alerts.yml");
    private static final Path PROMETHEUS_YML = OBS.resolve("prometheus/prometheus.yml");
    private static final Path DATASOURCES_YML = OBS.resolve("grafana/provisioning/datasources/prometheus.yml");
    private static final Path DASHBOARDS_YML = OBS.resolve("grafana/provisioning/dashboards/dashboards.yml");
    private static final Path DASHBOARD_JSON = OBS.resolve("grafana/dashboards/exam-online-overview.json");
    private static final Path BUSINESS_METRICS_JAVA = Paths.get("src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 非 exam_* 的显式白名单（actuator 标准指标 + 面板必需的 Boot 自动配置 JVM 指标）。
     * 刻意排除 hikaricp_connections_*：本项目用 dynamic-datasource，其 Hikari 指标能否被
     * Boot 自动绑定未经实测，写错会导致规则因 no data 永久静默。 */
    private static final Set<String> NON_EXAM_WHITELIST = Set.of(
            "up",
            "http_server_requests_seconds_count",
            "jvm_memory_used_bytes",
            "jvm_gc_pause_seconds_count",
            "jvm_gc_pause_seconds_sum");

    /** PromQL 关键字/函数/运算符/标签名（非指标名）：expr 里出现、又不以 exam_ 开头、也不在白名单的
     * 标识符必须落在此集合，否则说明引用了未登记的名字，直接失败。 */
    private static final Set<String> PROMQL_KEYWORDS = Set.of(
            "sum", "rate", "increase", "histogram_quantile", "clamp_min",
            "by", "le", "and", "or", "unless", "on", "ignoring", "offset", "bool",
            "label_values", "job", "status", "type", "endpoint", "area");

    /** 期望的 7 条告警规则名（与提案一致，防止规则被误删/误改） */
    private static final Set<String> EXPECTED_ALERTS = Set.of(
            "ExamOnlineDown", "SubmitFailureRatioHigh", "SubmitLatencyP99High",
            "MqSubmitQueueBacklog", "RateLimitDegraded", "Http5xxRatioHigh", "AntiCheatEventSpike");

    // ---------- 断言 1：资产文件存在且语法可解析 ----------

    @Test
    @DisplayName("观测栈资产文件存在且 YAML/JSON 语法合法")
    void assetsExistAndParse() throws IOException {
        for (Path p : List.of(RULES_YML, PROMETHEUS_YML, DATASOURCES_YML, DASHBOARDS_YML, DASHBOARD_JSON)) {
            assertTrue(Files.isRegularFile(p), "缺少观测栈资产文件: " + p);
        }
        new Yaml().load(Files.newBufferedReader(RULES_YML, StandardCharsets.UTF_8));
        new Yaml().load(Files.newBufferedReader(PROMETHEUS_YML, StandardCharsets.UTF_8));
        new Yaml().load(Files.newBufferedReader(DATASOURCES_YML, StandardCharsets.UTF_8));
        new Yaml().load(Files.newBufferedReader(DASHBOARDS_YML, StandardCharsets.UTF_8));
        JSON.readTree(Files.newBufferedReader(DASHBOARD_JSON, StandardCharsets.UTF_8));
    }

    // ---------- 断言 2：每条告警规则结构完整 ----------

    @Test
    @DisplayName("7 条告警规则结构完整（alert/expr/for/severity/summary/description）")
    void alertRulesAreComplete() throws IOException {
        List<Map<String, Object>> rules = alertRules();
        Set<String> names = new HashSet<>();
        for (Map<String, Object> rule : rules) {
            String alert = String.valueOf(rule.get("alert"));
            names.add(alert);
            String expr = String.valueOf(rule.get("expr"));
            assertTrue(expr.trim().length() > 0, alert + " 缺少 expr");
            assertNotNull(rule.get("for"), alert + " 缺少 for");
            @SuppressWarnings("unchecked")
            Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
            assertNotNull(labels, alert + " 缺少 labels");
            assertNotNull(labels.get("severity"), alert + " 缺少 labels.severity");
            @SuppressWarnings("unchecked")
            Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
            assertNotNull(annotations, alert + " 缺少 annotations");
            assertTrue(String.valueOf(annotations.get("summary")).trim().length() > 0, alert + " 缺少 annotations.summary");
            assertTrue(String.valueOf(annotations.get("description")).trim().length() > 0, alert + " 缺少 annotations.description");
        }
        assertEquals(EXPECTED_ALERTS, names, "告警规则集合与提案不一致");
    }

    @Test
    @DisplayName("关键告警语义被锁定（>= 0 and / histogram_quantile(0.99 / clamp_min）")
    void keyAlertSemanticsLocked() throws IOException {
        Map<String, String> exprs = new HashMap<>();
        for (Map<String, Object> rule : alertRules()) {
            exprs.put(String.valueOf(rule.get("alert")), String.valueOf(rule.get("expr")));
        }
        assertTrue(exprs.get("MqSubmitQueueBacklog").contains(">= 0 and"),
                "MqSubmitQueueBacklog 必须带 `>= 0 and` 前置条件排除 -1 哨兵值");
        assertTrue(exprs.get("SubmitLatencyP99High").contains("histogram_quantile(0.99"),
                "SubmitLatencyP99High 必须用 histogram_quantile(0.99, ...)");
        assertTrue(exprs.get("SubmitFailureRatioHigh").contains("clamp_min"),
                "SubmitFailureRatioHigh 必须用 clamp_min 防除零");
    }

    // ---------- 断言 3：规则与面板中的指标名全部命中已知集合 ----------

    @Test
    @DisplayName("规则与面板引用的每个指标名都命中已知集合（防拼写漂移）")
    void metricsInRulesAndPanelsMatchKnownSet() throws IOException {
        Set<String> known = knownExamMetrics();
        // 代码常量必须覆盖 6 个指标的导出名，守住 known 集合本身的完整性
        assertTrue(known.contains("exam_submit_duration_seconds_bucket"));
        assertTrue(known.contains("exam_submit_duration_seconds_count"));
        assertTrue(known.contains("exam_submit_duration_seconds_sum"));
        assertTrue(known.contains("exam_submit_success_total"));
        assertTrue(known.contains("exam_submit_failure_total"));
        assertTrue(known.contains("exam_mq_submit_queue_depth"));
        assertTrue(known.contains("exam_anticheat_events_total"));
        assertTrue(known.contains("exam_ratelimit_degraded_total"));

        List<String> exprs = allExpressions();
        assertFalse(exprs.isEmpty(), "未提取到任何表达式，说明资产文件可能为空");
        for (String expr : exprs) {
            for (String id : identifiers(expr)) {
                if (id.startsWith("exam_")) {
                    assertTrue(known.contains(id),
                            "未知 exam_* 指标名 [" + id + "] 出现在 expr: " + expr);
                } else if (!PROMQL_KEYWORDS.contains(id)) {
                    assertTrue(NON_EXAM_WHITELIST.contains(id),
                            "白名单外指标名 [" + id + "] 出现在 expr: " + expr);
                }
            }
        }
    }

    // ---------- 断言 4：数据源 uid 与面板引用一致 ----------

    @Test
    @DisplayName("Grafana 数据源 uid 与面板 JSON 引用完全一致")
    void datasourceUidMatchesPanelReferences() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> dsRoot = (Map<String, Object>) new Yaml().load(Files.newBufferedReader(DATASOURCES_YML, StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> datasources = (List<Map<String, Object>>) dsRoot.get("datasources");
        assertNotNull(datasources, "数据源 provisioning 缺少 datasources 列表");
        String uid = String.valueOf(datasources.get(0).get("uid"));
        assertTrue(uid.trim().length() > 0, "数据源 uid 为空");

        JsonNode dashboard = JSON.readTree(Files.newBufferedReader(DASHBOARD_JSON, StandardCharsets.UTF_8));
        for (JsonNode panel : dashboard.path("panels")) {
            String title = panel.path("title").asText("(未命名)");
            for (JsonNode target : panel.path("targets")) {
                JsonNode ds = target.path("datasource");
                if (ds.isObject() && ds.has("uid")) {
                    assertEquals(uid, ds.get("uid").asText(),
                            "panel [" + title + "] 引用的 datasource uid 与 provisioning 不一致");
                }
            }
        }
    }

    // ---------- 断言 5：每个 panel 都有非空 targets 且 expr 非空 ----------

    @Test
    @DisplayName("每个 panel 都有非空 targets 且 expr 非空")
    void panelsHaveNonEmptyExprs() throws IOException {
        JsonNode dashboard = JSON.readTree(Files.newBufferedReader(DASHBOARD_JSON, StandardCharsets.UTF_8));
        JsonNode panels = dashboard.path("panels");
        assertTrue(panels.isArray() && panels.size() >= 8,
                "面板分组不足，应覆盖交卷/积压/限流/5xx/JVM/防作弊等");
        for (JsonNode panel : panels) {
            String title = panel.path("title").asText("(未命名)");
            JsonNode targets = panel.path("targets");
            assertTrue(targets.isArray() && targets.size() > 0, "panel [" + title + "] 缺少 targets");
            boolean anyExpr = false;
            for (JsonNode target : targets) {
                anyExpr |= target.path("expr").asText("").trim().length() > 0;
            }
            assertTrue(anyExpr, "panel [" + title + "] 所有 target 的 expr 均为空");
        }
    }

    @Test
    @DisplayName("面板带 job 模板变量")
    void dashboardHasJobTemplateVariable() throws IOException {
        JsonNode dashboard = JSON.readTree(Files.newBufferedReader(DASHBOARD_JSON, StandardCharsets.UTF_8));
        boolean hasJob = StreamSupport.stream(dashboard.path("templating").path("list").spliterator(), false)
                .anyMatch(v -> "job".equals(v.path("name").asText()));
        assertTrue(hasJob, "面板缺少 job 模板变量");
    }

    // ---------- 辅助 ----------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> alertRules() throws IOException {
        Map<String, Object> root = (Map<String, Object>) new Yaml().load(Files.newBufferedReader(RULES_YML, StandardCharsets.UTF_8));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
        assertNotNull(groups, "告警规则文件缺少 groups");
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            rules.addAll((List<Map<String, Object>>) group.get("rules"));
        }
        return rules;
    }

    /** 汇总所有表达式：告警规则 expr + 面板 targets expr + 面板 job 模板变量查询。 */
    private static List<String> allExpressions() throws IOException {
        List<String> exprs = new ArrayList<>();
        for (Map<String, Object> rule : alertRules()) {
            exprs.add(String.valueOf(rule.get("expr")));
        }
        JsonNode dashboard = JSON.readTree(Files.newBufferedReader(DASHBOARD_JSON, StandardCharsets.UTF_8));
        for (JsonNode panel : dashboard.path("panels")) {
            for (JsonNode target : panel.path("targets")) {
                exprs.add(target.path("expr").asText(""));
            }
        }
        for (JsonNode v : dashboard.path("templating").path("list")) {
            JsonNode query = v.path("query");
            if (query.has("query")) {
                exprs.add(query.get("query").asText(""));
            }
        }
        return exprs;
    }

    /** 从 BusinessMetrics 源码提取「builder 类型 + 常量名 → 指标原始名」，
     * 按 Micrometer 确定性命名规则换算成 Prometheus 指标名集合。 */
    private static Set<String> knownExamMetrics() throws IOException {
        String src = Files.readString(BUSINESS_METRICS_JAVA, StandardCharsets.UTF_8);
        Pattern constPattern = Pattern.compile("String\\s+(\\w+)\\s*=\\s*\"([^\"]+)\"");
        Pattern builderPattern = Pattern.compile("(Timer|Counter|Gauge)\\.builder\\((\\w+)");
        Map<String, String> constToValue = new HashMap<>();
        for (Matcher m = constPattern.matcher(src); m.find(); ) {
            constToValue.put(m.group(1), m.group(2));
        }
        Set<String> known = new HashSet<>();
        for (Matcher m = builderPattern.matcher(src); m.find(); ) {
            String value = constToValue.get(m.group(2));
            if (value == null || !value.startsWith("exam.")) {
                continue;
            }
            String base = value.replace('.', '_');
            switch (m.group(1)) {
                // Timer：点转下划线加 _seconds，导出 _bucket/_count/_sum 三件套
                case "Timer" -> {
                    known.add(base + "_seconds_bucket");
                    known.add(base + "_seconds_count");
                    known.add(base + "_seconds_sum");
                }
                // Counter：加 _total 后缀
                case "Counter" -> known.add(base + "_total");
                // Gauge：原样导出
                case "Gauge" -> known.add(base);
                default -> { }
            }
        }
        return known;
    }

    /** 提取表达式中的标识符：先剔除字符串字面量、$ 模板变量与 [5m] 这类区间向量时长，避免误识别。 */
    private static Set<String> identifiers(String expr) {
        String cleaned = expr.replaceAll("\"[^\"]*\"", "")
                .replaceAll("\\$[A-Za-z0-9_]*", "")
                .replaceAll("\\[\\d+[A-Za-z]+\\]", "");
        Pattern identPattern = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
        Set<String> ids = new HashSet<>();
        for (Matcher m = identPattern.matcher(cleaned); m.find(); ) {
            ids.add(m.group());
        }
        return ids;
    }
}
