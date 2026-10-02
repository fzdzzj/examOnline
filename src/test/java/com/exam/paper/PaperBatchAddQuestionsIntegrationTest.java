package com.exam.paper;

import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量加题入卷集成测试（add-paper-batch-add-questions）：
 * 整批单事务全有全无——任一题失败整体回滚，不得出现半批入卷；
 * 请求内重复 questionId 前置校验 400；混合分值与题号接续；锁定试卷拒绝。
 */
class PaperBatchAddQuestionsIntegrationTest extends IntegrationTestBase {

    @Test
    void batchAddSuccessWithMixedScoresAndSequentialNumbers() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "批量单选一", "A", List.of("甲", "乙"), null);
        long q2 = createQuestion(teacher, 3, "批量判断二", "T", null, null);
        long q3 = createQuestion(teacher, 2, "批量多选三", "A,B", List.of("甲", "乙", "丙"), null);
        long paperId = createPaper(teacher, "批量入卷卷", 25.0);
        // 既有题目占第 1 号（默认分 5），批量应从第 2 号接续
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                "{\"questionId\":" + q1 + "}"), 200);

        // q2 显式覆盖 10 分，q3 缺省（用题目默认分 5）
        JsonNode detail = perform(jsonPost("/api/papers/" + paperId + "/questions/batch", teacher,
                "{\"items\":[{\"questionId\":" + q2 + ",\"score\":10},{\"questionId\":" + q3 + "}]}"), 200)
                .get("data");
        assertEquals(3, detail.get("questionCount").asInt());
        JsonNode questions = detail.get("questions");
        assertEquals(q2, questions.get(1).get("questionId").asLong());
        assertEquals(2, questions.get(1).get("number").asInt());
        assertEquals(10.0, questions.get(1).get("score").asDouble());
        assertEquals(q3, questions.get(2).get("questionId").asLong());
        assertEquals(3, questions.get(2).get("number").asInt());
        assertEquals(5.0, questions.get(2).get("score").asDouble());
    }

    @Test
    void batchAddRollsBackWhenQuestionAlreadyInPaper() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "回滚单选一", "A", List.of("甲", "乙"), null);
        long q2 = createQuestion(teacher, 3, "回滚判断二", "T", null, null);
        long paperId = createPaper(teacher, "回滚卷-已在卷", 10.0);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                "{\"questionId\":" + q1 + "}"), 200);

        // 批量 [q2, q1]：q1 已在卷中 -> 与单题同文案，整批回滚（q2 零新增行）
        JsonNode rejected = perform(jsonPost("/api/papers/" + paperId + "/questions/batch", teacher,
                "{\"items\":[{\"questionId\":" + q2 + "},{\"questionId\":" + q1 + "}]}"), 400);
        assertTrue(rejected.get("message").asText().contains("该题目已在试卷中"));
        JsonNode detail = perform(jsonGet("/api/papers/" + paperId, teacher), 200).get("data");
        assertEquals(1, detail.get("questionCount").asInt());
        assertFalse(hasQuestion(detail, q2), "整批回滚后 q2 不得残留在卷中");
    }

    @Test
    void batchAddRejectsDuplicateIdsWithinRequest() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "重复单选", "A", List.of("甲", "乙"), null);
        long paperId = createPaper(teacher, "重复请求卷", 10.0);

        // 请求内重复 questionId -> 400 前置显式校验，不靠逐题查重间接暴露
        JsonNode rejected = perform(jsonPost("/api/papers/" + paperId + "/questions/batch", teacher,
                "{\"items\":[{\"questionId\":" + q1 + "},{\"questionId\":" + q1 + "}]}"), 400);
        assertTrue(rejected.get("message").asText().contains("请求内存在重复题目"));
        JsonNode detail = perform(jsonGet("/api/papers/" + paperId, teacher), 200).get("data");
        assertEquals(0, detail.get("questionCount").asInt());
    }

    @Test
    void batchAddRollsBackWhenQuestionMissingOrDeleted() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "缺题单选", "A", List.of("甲", "乙"), null);
        long q2 = createQuestion(teacher, 3, "软删判断", "T", null, null);
        long paperId = createPaper(teacher, "回滚卷-缺题", 10.0);

        // 不存在的题目 ID -> 404，同批 q1 整体回滚
        JsonNode missing = perform(jsonPost("/api/papers/" + paperId + "/questions/batch", teacher,
                "{\"items\":[{\"questionId\":" + q1 + "},{\"questionId\":999999}]}"), 404);
        assertTrue(missing.get("message").asText().contains("题目不存在或已删除"));
        JsonNode detail = perform(jsonGet("/api/papers/" + paperId, teacher), 200).get("data");
        assertEquals(0, detail.get("questionCount").asInt());

        // 已软删题目 -> 404 整体回滚
        perform(jsonDelete("/api/questions/" + q2, teacher), 200);
        perform(jsonPost("/api/papers/" + paperId + "/questions/batch", teacher,
                "{\"items\":[{\"questionId\":" + q1 + "},{\"questionId\":" + q2 + "}]}"), 404);
        detail = perform(jsonGet("/api/papers/" + paperId, teacher), 200).get("data");
        assertEquals(0, detail.get("questionCount").asInt());
        assertFalse(hasQuestion(detail, q1), "整批回滚后 q1 不得残留在卷中");
    }

    @Test
    void batchAddRejectedOnLockedPaper() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "锁定单选", "A", List.of("甲", "乙"), null);
        long q2 = createQuestion(teacher, 3, "锁定判断", "T", null, null);
        long paperId = createPaper(teacher, "锁定卷", 5.0);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                "{\"questionId\":" + q1 + "}"), 200);
        // 生成快照即锁定（总分校验须先对齐：1 题 × 默认分 5 = 总分 5）
        perform(jsonPost("/api/papers/" + paperId + "/snapshot", teacher, null), 200);

        JsonNode rejected = perform(jsonPost("/api/papers/" + paperId + "/questions/batch", teacher,
                "{\"items\":[{\"questionId\":" + q2 + "}]}"), 400);
        assertTrue(rejected.get("message").asText().contains("已锁定"));
        JsonNode detail = perform(jsonGet("/api/papers/" + paperId, teacher), 200).get("data");
        assertEquals(1, detail.get("questionCount").asInt());
        assertFalse(hasQuestion(detail, q2));
    }

    /** 试卷详情中是否包含某题目。 */
    private static boolean hasQuestion(JsonNode detail, long questionId) {
        for (JsonNode question : detail.get("questions")) {
            if (question.get("questionId").asLong() == questionId) {
                return true;
            }
        }
        return false;
    }
}
