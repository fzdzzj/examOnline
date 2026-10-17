package com.exam.deployment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时调度池配置词法护栏（harden-scheduler-pool-config，B.2 中危 M2）：
 *
 * <p>裸 {@code @EnableScheduling} 默认单线程调度器——sweep 单轮的 MQ confirm 等待在 broker 故障时
 * 可拖分钟级，把状态机流转与开考提醒的定时触发整体阻塞，且应用本身无异常无告警（最难排查的静默
 * 停摆形态）。本护栏对 yml 做词法级断言：base 声明 {@code spring.task.scheduling.pool.size} 且
 * 缺省值 ≥2；dev/prod 若未来出现同名覆盖，其值同样必须 ≥2（防「dev 2 / prod 1」式分歧——
 * 「三 yml 一致」的机械判据）。
 *
 * <p>局限（如实声明）：词法断言保证「声明存在、缺省 ≥2、覆盖一致」，不能证明运行期线程池真的
 * 以该容量创建（Spring TaskSchedulingProperties 绑定由框架保证）；也不替代对 sweep 自身耗时的
 * 观测（那是交卷链路既有指标与告警的职责）。
 */
class SchedulingPoolConfigGuardTest {

    /** surefire 的 cwd 是项目根（与 AlertAssetsTest / ProdContainerTzGuardTest 同一约定） */
    private static final Path BASE_YML = Paths.get("src", "main", "resources", "application.yml");
    private static final Path DEV_YML = Paths.get("src", "main", "resources", "application-dev.yml");
    private static final Path PROD_YML = Paths.get("src", "main", "resources", "application-prod.yml");

    private static final String POOL_KEY_PATH = "task.scheduling.pool.size";

    /** ${ENV:default} 占位符解析（取 default 段） */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^:}]+:([^}]+)}$");

    @Test
    @DisplayName("base application.yml 声明调度池容量且缺省值 >= 2")
    void baseDeclaresSchedulingPoolAtLeastTwo() throws IOException {
        Object raw = poolSizeOf(BASE_YML);
        assertNotNull(raw, "base application.yml 必须声明 spring.task.scheduling.pool.size"
                + "（删除声明 = 回落 Spring 默认单线程调度器，M2 风险复发）");
        assertTrue(resolveInt(raw) >= 2, "调度池缺省值必须 >= 2（M2 缓解口径），实际=" + raw);
    }

    @Test
    @DisplayName("dev/prod 若覆盖同名键其值必须 >= 2（三 yml 一致性下限）")
    void profileOverridesStayAtLeastTwo() throws IOException {
        for (Path yml : java.util.List.of(DEV_YML, PROD_YML)) {
            Object raw = poolSizeOf(yml);
            if (raw != null) {
                assertTrue(resolveInt(raw) >= 2,
                        yml + " 对 spring.task.scheduling.pool.size 的覆盖必须 >= 2，实际=" + raw);
            }
            // 未覆盖 = 继承 base 声明（合法且为当前形态），不强制要求出现
        }
    }

    // ---------- 辅助 ----------

    @SuppressWarnings("unchecked")
    private static Object poolSizeOf(Path yml) throws IOException {
        assertTrue(Files.isRegularFile(yml), "缺少配置文件: " + yml);
        Map<String, Object> root = new Yaml().load(Files.newBufferedReader(yml, StandardCharsets.UTF_8));
        Object spring = root == null ? null : root.get("spring");
        Object cur = spring;
        for (String seg : POOL_KEY_PATH.split("\\.")) {
            if (!(cur instanceof Map<?, ?>)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(seg);
        }
        return cur;
    }

    /** 值可为整数字面量或 ${ENV:default} 占位符（取 default 段），解析失败即断言失败 */
    private static int resolveInt(Object raw) {
        String s = String.valueOf(raw).trim();
        Matcher m = PLACEHOLDER.matcher(s);
        if (m.matches()) {
            s = m.group(1).trim();
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new AssertionError("调度池容量不是可解析整数: " + raw, e);
        }
    }
}
