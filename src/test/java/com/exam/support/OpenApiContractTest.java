package com.exam.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 契约冒烟测试（add-backend-openapi 阶段18）。
 * - 验证 /v3/api-docs 可达，返回 200
 * - paths 非空且数量 ≥ 下限（防新增端点后契约静默缺失，必须重新导出）
 * - components.securitySchemes 含 bearer scheme
 * - 免鉴权端点在契约中显式 security: [] （覆盖全局）
 * - 受保护端点继承全局 security（per-op 为 null）
 * - 导出契约改为显式动作：mvn test -DexportContract=true 才触发写 openapi.yaml
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Order(1)
    void openApiContractSmoke() throws Exception {
        // 纯断言，不写文件；验证基本结构 + 免鉴权标注
        MvcResult jsonResult = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        String json = jsonResult.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);
        JsonNode paths = root.path("paths");
        assertTrue(paths.isObject() && paths.size() > 0, "paths 为空");
        int pathCount = paths.size();
        assertTrue(pathCount >= 55, "paths 数量 " + pathCount + " < 55，新增端点后必须重新导出 openapi.yaml");

        JsonNode securitySchemes = root.path("components").path("securitySchemes");
        assertTrue(securitySchemes.has("Authorization") || securitySchemes.toString().contains("bearer"),
                "securitySchemes 应包含 Authorization bearer scheme");

        // 免鉴权标注断言（Scenario 鉴权语义标注护栏）
        JsonNode loginPath = paths.path("/api/auth/login");
        JsonNode loginPost = loginPath.path("post");
        assertTrue(loginPost.has("security"), "/api/auth/login 应有 security 字段（显式 [] 表示免鉴权）");
        assertEquals("[]", loginPost.path("security").toString(), "/api/auth/login 的 security 应为显式空数组 []");

        JsonNode examsPath = paths.path("/api/exams");
        JsonNode examsGet = examsPath.path("get");
        // 受保护端点 per-op security 为 null 表示继承全局
        assertTrue(examsGet.path("security").isMissingNode() || examsGet.path("security").isNull() || examsGet.path("security").toString().equals("null"),
                "/api/exams 的 security 应为 null（继承全局 securityRequirement）");
    }

    @Test
    @Order(2)
    void staticReadExportedContractFile() throws Exception {
        // 纯静态读 openapi.yaml，防文件被误清空或未导出
        // 因 openapi.yaml 随 commit 入仓库，全新克隆已存在，无顺序依赖
        assertTrue(Files.exists(Paths.get("openapi.yaml")), "openapi.yaml 不存在");
        assertTrue(Files.size(Paths.get("openapi.yaml")) > 0, "openapi.yaml 为空文件");
        String content = Files.readString(Paths.get("openapi.yaml"), StandardCharsets.UTF_8);
        assertTrue(content.contains("openapi:") || content.contains("openapi: 3."),
                "openapi.yaml 应包含 openapi: 版本行");
    }

    @Test
    @Order(3)
    @EnabledIfSystemProperty(named = "exportContract", matches = "true")
    void exportOpenApiContract() throws Exception {
        // 显式导出动作：仅当 -DexportContract=true 时运行
        // 避免常规 mvn test 污染工作区（CI 每次都会脏）
        // 运行示例：mvn test -DexportContract=true -Dtest=OpenApiContractTest#exportOpenApiContract
        MvcResult yamlResult = mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andReturn();
        String yamlContent = yamlResult.getResponse().getContentAsString();
        Files.write(Paths.get("openapi.yaml"), yamlContent.getBytes(StandardCharsets.UTF_8));
    }
}


