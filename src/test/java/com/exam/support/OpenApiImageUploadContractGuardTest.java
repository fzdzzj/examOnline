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
 * 题干图片上传契约口径护栏（add-question-image 复核阻断项 §7 修复）：
 * <p>
 * 阻断项：springdoc 将 {@code @RequestParam MultipartFile} 导出为 {@code application/json}
 * requestBody，生成的 SDK {@code upload()} 强制 JSON Content-Type，浏览器端真实上传请求体为
 * {@code {"file":{}}}，后端恒 400「请选择图片文件」。
 * <p>
 * 修复：控制器改 {@code @RequestPart("file")} + {@code consumes = multipart/form-data}，
 * 使契约如实导出 {@code multipart/form-data} + binary。本护栏静态读入库契约
 * {@code openapi.yaml}，断言上传端点 requestBody 的 content-type 为 multipart/form-data
 * 且 file part schema 为 binary——若有人回退控制器注解并重导出成 JSON 口径，本测试即红
 * （变异演示：把 openapi.yaml 上传端点 content 的 multipart/form-data 换成 application/json）。
 */
class OpenApiImageUploadContractGuardTest {

    private static final String UPLOAD_PATH = "/api/question-images";

    /** 读入库契约并定位上传端点 requestBody 的 content 映射。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> uploadRequestBodyContent() throws Exception {
        String content = Files.readString(Paths.get("openapi.yaml"), StandardCharsets.UTF_8);
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(content);
        Map<String, Object> paths = (Map<String, Object>) root.get("paths");
        assertNotNull(paths, "openapi.yaml 无 paths");
        Map<String, Object> upload = (Map<String, Object>) paths.get(UPLOAD_PATH);
        assertNotNull(upload, "openapi.yaml 缺少 " + UPLOAD_PATH);
        Map<String, Object> post = (Map<String, Object>) upload.get("post");
        assertNotNull(post, UPLOAD_PATH + " 无 post");
        Map<String, Object> requestBody = (Map<String, Object>) post.get("requestBody");
        assertNotNull(requestBody, UPLOAD_PATH + " post 无 requestBody");
        return (Map<String, Object>) requestBody.get("content");
    }

    @Test
    void uploadEndpointDeclaresMultipartFormData() throws Exception {
        Map<String, Object> content = uploadRequestBodyContent();
        assertTrue(content.containsKey("multipart/form-data"),
                "上传端点 requestBody 必须声明 multipart/form-data（契约如实描述口径；"
                        + "若被导出为 application/json，SDK 会生成 JSON 序列化调用 → 浏览器上传恒 400）");
        assertFalse(content.containsKey("application/json"),
                "上传端点 requestBody 不得为 application/json（@RequestPart + consumes 应使 springdoc "
                        + "导出 multipart/form-data）");
    }

    @Test
    void uploadFilePartSchemaIsBinary() throws Exception {
        Map<String, Object> content = uploadRequestBodyContent();
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
