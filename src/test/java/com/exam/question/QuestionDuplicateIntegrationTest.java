package com.exam.question;

import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题目查重端点集成测试（spec-delta question-bank「题干文本查重预检」）：
 * 高相似度召回与降序排列、excludeId 排除自身、教师数据隔离、
 * 空/短题干 400 校验、threshold/limit 过滤生效。
 */
class QuestionDuplicateIntegrationTest extends IntegrationTestBase {

    private static final String CORE = "下列关于TCP三次握手说法正确的是";
    private static final String WORSE = "下列关于TCP三次握手说法错误的是";

    @Test
    void highSimilarityRecallReturnsDescendingHits() throws Exception {
        String teacher = registerTeacher();
        long coreId = createQuestion(teacher, 1, CORE, "A", List.of("A选项", "B选项"), null);
        createQuestion(teacher, 1, WORSE, "B", List.of("A选项", "B选项"), null);

        JsonNode data = perform(
                jsonGet("/api/questions/check-duplicate?stem=" + CORE, teacher), 200)
                .get("data");
        assertTrue(data.isArray() && data.size() > 0, "应召回相似题目");
        // 降序排列
        double prev = 1.0;
        for (JsonNode item : data) {
            double sim = item.get("similarity").asDouble();
            assertTrue(sim <= prev, "相似度应降序排列");
            prev = sim;
        }
        // 最高的相似度即是完全一致的题干本身（1.0）
        assertEquals(1.0, data.get(0).get("similarity").asDouble(), 1e-9);
        assertEquals(coreId, data.get(0).get("questionId").asLong());
        assertTrue(prev >= 0.6, "最不相似的命中也应 ≥ 阈值 0.6，实际 " + prev);
    }

    @Test
    void excludeIdExcludesSelf() throws Exception {
        String teacher = registerTeacher();
        long coreId = createQuestion(teacher, 1, CORE, "A", List.of("A选项", "B选项"), null);
        createQuestion(teacher, 1, WORSE, "B", List.of("A选项", "B选项"), null);

        JsonNode data = perform(jsonGet(
                "/api/questions/check-duplicate?stem=" + CORE + "&excludeId=" + coreId, teacher), 200)
                .get("data");
        assertTrue(data.isArray() && !data.isEmpty());
        for (JsonNode item : data) {
            assertTrue(item.get("questionId").asLong() != coreId,
                    "excludeId 应排除题目自身");
        }
        // 排除自身后只剩一字之差的那道
        assertTrue(data.size() == 1, "排除自身后应仅剩一道相似题");
    }

    @Test
    void teacherDataIsolation() throws Exception {
        String teacherA = registerTeacher();
        String teacherB = registerTeacher();
        createQuestion(teacherA, 1, CORE, "A", List.of("A选项", "B选项"), null);

        // 教师 B 题库为空 → 查重命中空
        JsonNode data = perform(jsonGet(
                "/api/questions/check-duplicate?stem=" + CORE, teacherB), 200)
                .get("data");
        assertTrue(data.isArray() && data.isEmpty(), "教师 B 不应看到教师 A 的题目");
    }

    @Test
    void blankOrShortStemReturns400() throws Exception {
        String teacher = registerTeacher();
        // 空题干
        perform(jsonGet("/api/questions/check-duplicate?stem=", teacher), 400);
        // 空白（直接传空格，避免 URL 编码歧义）
        perform(jsonGet("/api/questions/check-duplicate?stem=  ", teacher), 400);
        // 单个有效字符
        perform(jsonGet("/api/questions/check-duplicate?stem=A", teacher), 400);
        // 缺失参数
        perform(jsonGet("/api/questions/check-duplicate", teacher), 400);
    }

    @Test
    void thresholdAndLimitFilterApply() throws Exception {
        String teacher = registerTeacher();
        createQuestion(teacher, 1, CORE, "A", List.of("A选项", "B选项"), null);
        createQuestion(teacher, 1, WORSE, "B", List.of("A选项", "B选项"), null);

        // limit=1 仅返回最相似的一条
        JsonNode one = perform(jsonGet(
                "/api/questions/check-duplicate?stem=" + CORE + "&limit=1", teacher), 200)
                .get("data");
        assertEquals(1, one.size(), "limit=1 应只返回一条");
        assertEquals(1.0, one.get(0).get("similarity").asDouble(), 1e-9);

        // threshold=0.99 只保留完全一致的题干
        JsonNode high = perform(jsonGet(
                "/api/questions/check-duplicate?stem=" + CORE + "&threshold=0.99", teacher), 200)
                .get("data");
        assertEquals(1, high.size(), "高阈值应仅保留完全一致的一条");
        assertEquals(1.0, high.get(0).get("similarity").asDouble(), 1e-9);
    }

    @Test
    void studentWithoutPermissionGets403() throws Exception {
        String student = registerStudent();
        perform(jsonGet("/api/questions/check-duplicate?stem=" + CORE, student), 403);
    }
}