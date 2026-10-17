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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Actuator 暴露面收敛词法护栏（harden-actuator-exposure，安全专项发现 2）：
 *
 * <p>prod 只暴露 health,prometheus 且 show-details=never；鉴权豁免为观测端点白名单而非
 * /actuator/** 整体豁免。配置与豁免清单都是易回退的部署面——删除 prod 覆盖块、或把豁免
 * 改回整体形态，都让「健康细节/全量 metrics 未认证可读」的风险静默复发，本护栏把收敛面
 * 变成提交前就挡下的确定性断言。
 *
 * <p>局限（如实声明）：词法断言保证「prod 档声明存在且值正确、豁免白名单三模式在场且整体
 * 豁免不在场」，不能证明运行期 MVC 映射真的按其生效（Boot 属性绑定由框架保证）；观测链路
 * 的端到端可用性由既有 SubmitObservabilityIntegrationTest（/actuator/metrics 与
 * /actuator/prometheus 双端点）与 prod 演练环境 healthcheck 承载。
 */
class ActuatorExposureGuardTest {

    /** surefire 的 cwd 是项目根（与 AlertAssetsTest / ProdContainerTzGuardTest 同一约定） */
    private static final Path PROD_YML = Paths.get("src", "main", "resources", "application-prod.yml");
    private static final Path WEB_MVC_CONFIG = Paths.get("src", "main", "java", "com", "exam", "auth", "security", "WebMvcConfig.java");

    @Test
    @DisplayName("prod 档 exposure 恰为 health,prometheus 且 show-details 为 never")
    void prodShrinksExposureAndHealthDetails() throws IOException {
        Map<String, Object> root = loadYml(PROD_YML);
        Map<String, Object> management = section(root, "management");
        Map<String, Object> endpoints = section(management, "endpoints");
        Map<String, Object> web = section(endpoints, "web");
        Map<String, Object> exposure = section(web, "exposure");
        assertEquals("health,prometheus", String.valueOf(exposure.get("include")),
                "prod exposure 必须恰为 health,prometheus（info 零价值剔除、metrics 由 prometheus 承载聚合出口）");

        Map<String, Object> endpoint = section(management, "endpoint");
        Map<String, Object> health = section(endpoint, "health");
        assertEquals("never", String.valueOf(health.get("show-details")),
                "prod 健康细节必须收敛为 never（组件名与连接状态不得未认证可读；healthcheck 只看状态码不受影响）");
    }

    @Test
    @DisplayName("WebMvcConfig 豁免为观测端点白名单，不含 /actuator/** 整体豁免")
    void interceptorExclusionIsWhitelistNotBroadActuator() throws IOException {
        String src = Files.readString(WEB_MVC_CONFIG, StandardCharsets.UTF_8);
        assertTrue(src.contains("\"/actuator/health/**\""), "豁免必须含 /actuator/health/**（prod healthcheck /health/db 依赖）");
        assertTrue(src.contains("\"/actuator/prometheus\""), "豁免必须含 /actuator/prometheus（内网抓取依赖）");
        assertTrue(src.contains("\"/actuator/metrics/**\""), "豁免必须含 /actuator/metrics/**（dev/测试观测与集成测试留证；prod 未暴露该端点）");
        assertFalse(src.contains("\"/actuator/**\""), "不得恢复 /actuator/** 整体豁免（其余 actuator 端点须默认要求认证）");
    }

    // ---------- 辅助 ----------

    private static Map<String, Object> loadYml(Path yml) throws IOException {
        assertTrue(Files.isRegularFile(yml), "缺少配置文件: " + yml);
        return new Yaml().load(Files.newBufferedReader(yml, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        assertTrue(parent != null && parent.get(key) instanceof Map,
                "配置缺少 " + key + " 段（prod 收敛块被删除即在此失败）");
        return (Map<String, Object>) parent.get(key);
    }
}
