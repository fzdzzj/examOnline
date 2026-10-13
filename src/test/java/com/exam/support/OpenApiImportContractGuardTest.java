package com.exam.support;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题库导入契约口径护栏（add-question-import-export，对齐 add-question-image 修复终态 §7）：
 * import 端点必须 {@code @RequestPart("file")} + {@code consumes = multipart/form-data}，
 * 使 springdoc 契约如实导出 {@code multipart/form-data} + binary——若回退成
 * {@code application/json}（如 @RequestParam MultipartFile），生成 SDK 会发 JSON，
 * 浏览器端真实上传恒 400。本护栏静态读入库契约断言 import 端点
 * requestBody 为 multipart/form-data 且 file part schema 为 binary。
 */
class OpenApiImportContractGuardTest {

    private static final String IMPORT_PATH = "/api/questions/import";

    /** 读入库契约并定位 import 端点 requestBody 的 content 映射。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> importRequestBodyContent() throws Exception {
        String content = Files.readString(Paths.get("openapi.yaml"), StandardCharsets.UTF_8);
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(content);
        Map<String, Object> paths = (Map<String, Object>) root.get("paths");
        assertNotNull(paths, "openapi.yaml 无 paths");
        Map<String, Object> importPath = (Map<String, Object>) paths.get(IMPORT_PATH);
        assertNotNull(importPath, "openapi.yaml 缺少 " + IMPORT_PATH);
        Map<String, Object> post = (Map<String, Object>) importPath.get("post");
        assertNotNull(post, IMPORT_PATH + " 无 post");
        Map<String, Object> requestBody = (Map<String, Object>) post.get("requestBody");
        assertNotNull(requestBody, IMPORT_PATH + " post 无 requestBody");
        return (Map<String, Object>) requestBody.get("content");
    }

    @Test
    void importEndpointDeclaresMultipartFormData() throws Exception {
        Map<String, Object> content = importRequestBodyContent();
        assertTrue(content.containsKey("multipart/form-data"),
                "import 端点 requestBody 必须声明 multipart/form-data（契约如实描述口径；"
                        + "若被导出为 application/json，SDK 会生成 JSON 序列化调用 → 浏览器上传恒 400）");
        assertFalse(content.containsKey("application/json"),
                "import 端点 requestBody 不得为 application/json（@RequestPart + consumes 应使 springdoc 导出 multipart/form-data）");
    }

    @Test
    void importFilePartSchemaIsBinary() throws Exception {
        Map<String, Object> content = importRequestBodyContent();
        Object multipart = content.get("multipart/form-data");
        assertNotNull(multipart, "缺少 multipart/form-data 声明");
        Map<String, Object> multipartBody = (Map<String, Object>) multipart;
        Map<String, Object> schema = (Map<String, Object>) multipartBody.get("schema");
        assertNotNull(schema, "multipart/form-data 无 schema");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties, "multipart/form-data schema 无 properties");
        Map<String, Object> file = (Map<String, Object>) properties.get("file");
        assertNotNull(file, "multipart/form-data schema 缺 file part");
        assertEquals("string", file.get("type"), "file part type 应为 string（二进制流形态）");
        assertEquals("binary", file.get("format"), "file part format 应为 binary");
    }
}