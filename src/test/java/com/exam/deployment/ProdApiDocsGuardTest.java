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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产环境 OpenAPI 暴露面默认收敛词法护栏（harden-low-risk-security-residues）：
 *
 * <p>prod 环境下默认关闭 Swagger UI 与 OpenAPI 契约接口（/v3/api-docs），
 * 避免未授权外部访问泄露接口明细与内部拓扑 schema。支持通过环境变量 API_DOCS_ENABLED 显式覆盖。
 */
class ProdApiDocsGuardTest {

    private static final Path PROD_YML = Paths.get("src", "main", "resources", "application-prod.yml");

    @Test
    @DisplayName("prod 档 swagger-ui 必须显式关闭（enabled: false）")
    void swaggerUiIsDisabledInProd() throws IOException {
        Map<String, Object> root = loadYml(PROD_YML);
        Map<String, Object> springdoc = section(root, "springdoc");
        Map<String, Object> swaggerUi = section(springdoc, "swagger-ui");
        assertEquals(Boolean.FALSE, swaggerUi.get("enabled"),
                "prod 环境 swagger-ui.enabled 必须为 false");
    }

    @Test
    @DisplayName("prod 档 api-docs 必须默认关闭且支持环境变量覆盖（enabled: ${API_DOCS_ENABLED:false}）")
    void apiDocsIsDisabledByDefaultInProd() throws IOException {
        Map<String, Object> root = loadYml(PROD_YML);
        Map<String, Object> springdoc = section(root, "springdoc");
        Map<String, Object> apiDocs = section(springdoc, "api-docs");
        Object enabled = apiDocs.get("enabled");
        assertEquals("${API_DOCS_ENABLED:false}", String.valueOf(enabled),
                "prod 环境 api-docs.enabled 必须配置为 ${API_DOCS_ENABLED:false}，默认关闭且允许按需覆盖");
        assertNotEquals(Boolean.TRUE, enabled, "prod 环境 api-docs.enabled 禁止为裸 true");
    }

    private static Map<String, Object> loadYml(Path yml) throws IOException {
        assertTrue(Files.isRegularFile(yml), "缺少配置文件: " + yml);
        return new Yaml().load(Files.newBufferedReader(yml, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        assertTrue(parent != null && parent.get(key) instanceof Map,
                "配置缺少 " + key + " 段（prod OpenAPI 配置块缺失）");
        return (Map<String, Object>) parent.get(key);
    }
}
