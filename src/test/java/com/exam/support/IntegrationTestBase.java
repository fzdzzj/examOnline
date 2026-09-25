package com.exam.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 题库/组卷集成测试公共基座（MockMvc + H2 + Redis db 15）：
 * 提供教师注册/登录、学生注册、题目/标签/试卷造数与 JSON 请求工具
 * （jsonGet/jsonPost/jsonPut/jsonDelete，避免与 MockMvcRequestBuilders 同名静态方法混淆）。
 * H2 数据跨用例保留，账号以自增序号保证唯一；Redis 状态每个用例前清空。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    /** 须满足 AuthService 的强密码规则（大小写+数字、≥8 位），故不能退化成小写。 */
    protected static final String PASSWORD = "Pass1234";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redis;

    /**
     * 账号/造数取号器：JVM 级单调递增，所有测试实例共享同一计数，不随实例重新播种。
     * 不能改回实例级毫秒种子——紧凑构造的实例会同毫秒撞桶，跨类注册出同名账号。
     * H2 是进程内存库、每轮随 JVM 新建，同轮内唯一即可，故不取模、不带时间种子。
     */
    private static final AtomicLong seq = new AtomicLong();

    @BeforeEach
    void flushRedis() {
        // 清空测试库（db 15），隔离令牌/锁定/限流等 Redis 状态
        var conn = redis.getConnectionFactory().getConnection();
        try {
            conn.serverCommands().flushDb();
        } finally {
            conn.close();
        }
    }

    // ==================== 账号 ====================

    /** 注册并登录一名教师（管理员发邀请码），返回 Access Token。 */
    protected String registerTeacher() throws Exception {
        String username = "tea_" + seq.incrementAndGet();
        String adminAccess = loginToken("admin", "admin123");
        String inviteCode = objectMapper.readTree(
                        mockMvc.perform(post("/api/admin/invite-codes")
                                        .header("Authorization", bearer(adminAccess))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{}"))
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString())
                .get("data").get("code").asText();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("name", "教师-" + username);
        body.put("email", username + "@test.com");
        body.put("roleType", "TEACHER");
        body.put("inviteCode", inviteCode);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
        return loginToken(username, PASSWORD);
    }

    /** 注册并登录一名学生，返回 Access Token。 */
    protected String registerStudent() throws Exception {
        String username = "stu_" + seq.incrementAndGet();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("name", "学生-" + username);
        body.put("email", username + "@test.com");
        body.put("roleType", "STUDENT");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
        return loginToken(username, PASSWORD);
    }

    // ==================== 造数 ====================

    /**
     * 造数用唯一名（标签是全局共享资源且 H2 数据跨用例保留，
     * 名称需带唯一后缀避免 Service 层查重误伤）。
     */
    protected String unique(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    /** 创建题目 JSON 请求体。 */
    protected String questionJson(int type, String content, String answer,
                                  List<String> choices, double score, int difficulty,
                                  List<Long> tagIds) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", type);
        body.put("content", content);
        body.put("correctAnswer", answer);
        body.put("score", score);
        body.put("difficulty", difficulty);
        if (choices != null) {
            ArrayNode arr = body.putArray("choices");
            choices.forEach(arr::add);
        }
        if (tagIds != null) {
            ArrayNode arr = body.putArray("tagIds");
            tagIds.forEach(arr::add);
        }
        return objectMapper.writeValueAsString(body);
    }

    /** 创建题目（默认分 5、难度 1），返回题目 ID。 */
    protected long createQuestion(String token, int type, String content, String answer,
                                  List<String> choices, List<Long> tagIds) throws Exception {
        JsonNode data = perform(jsonPost("/api/questions", token,
                questionJson(type, content, answer, choices, 5.0, 1, tagIds)), 200)
                .get("data");
        return data.get("id").asLong();
    }

    /** 创建标签，返回标签 ID。 */
    protected long createTag(String token, String name, String type) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("type", type);
        JsonNode data = perform(jsonPost("/api/tags", token, objectMapper.writeValueAsString(body)), 200)
                .get("data");
        return data.get("id").asLong();
    }

    /** 创建试卷，返回试卷 ID。 */
    protected long createPaper(String token, String title, double totalScore) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", title);
        body.put("totalScore", totalScore);
        JsonNode data = perform(jsonPost("/api/papers", token, objectMapper.writeValueAsString(body)), 200)
                .get("data");
        return data.get("id").asLong();
    }

    /** 抽题规则 JSON（单条）。 */
    protected String rulesJson(Integer type, Integer difficulty, List<Long> tagIds, int count)
            throws Exception {
        ObjectNode rule = objectMapper.createObjectNode();
        if (type != null) {
            rule.put("type", type);
        }
        if (difficulty != null) {
            rule.put("difficulty", difficulty);
        }
        if (tagIds != null) {
            ArrayNode arr = rule.putArray("tagIds");
            tagIds.forEach(arr::add);
        }
        rule.put("count", count);
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("rules").add(rule);
        return objectMapper.writeValueAsString(body);
    }

    // ==================== 请求工具 ====================

    /** GET（带 Token）。 */
    protected MockHttpServletRequestBuilder jsonGet(String url, String token) {
        return token == null ? get(url) : get(url).header("Authorization", bearer(token));
    }

    /** POST JSON（带 Token）。 */
    protected MockHttpServletRequestBuilder jsonPost(String url, String token, String json) {
        MockHttpServletRequestBuilder builder = post(url).contentType(MediaType.APPLICATION_JSON);
        if (json != null) {
            builder.content(json);
        }
        return token == null ? builder : builder.header("Authorization", bearer(token));
    }

    /** PUT JSON（带 Token）。 */
    protected MockHttpServletRequestBuilder jsonPut(String url, String token, String json) {
        MockHttpServletRequestBuilder builder = put0(url).contentType(MediaType.APPLICATION_JSON);
        if (json != null) {
            builder.content(json);
        }
        return token == null ? builder : builder.header("Authorization", bearer(token));
    }

    /** DELETE（带 Token）。 */
    protected MockHttpServletRequestBuilder jsonDelete(String url, String token) {
        return token == null ? delete0(url) : delete0(url).header("Authorization", bearer(token));
    }

    /** 执行请求并断言 HTTP 状态，返回响应 JSON。 */
    protected JsonNode perform(MockHttpServletRequestBuilder builder, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(builder)
                .andExpect(status().is(expectedStatus))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    /** 登录取 Access Token（子类用它登录预置 admin，或以任意已注册账号取身份）。 */
    protected String loginToken(String username, String password) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", username);
        body.put("password", password);
        JsonNode data = perform(jsonPost("/api/auth/login", null, objectMapper.writeValueAsString(body)), 200)
                .get("data");
        return data.get("accessToken").asText();
    }

    private static MockHttpServletRequestBuilder put0(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url);
    }

    private static MockHttpServletRequestBuilder delete0(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url);
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }
}
