package com.exam.paper;

import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组卷集成测试（spec「手动组卷/标签随机抽题/抽题锁定与试卷快照」）：
 * 手动加题与分值覆盖（试卷内分值与题目默认分互不影响）、题号排序、
 * 总分校验、按规则随机抽题（含容量不足提示）、快照生成后锁定与不可变。
 */
class PaperIntegrationTest extends IntegrationTestBase {

    @Test
    void manualAssemblyScoreOverrideAndOrder() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "单选题", "A", List.of("A选项", "B选项"), null);
        long q2 = createQuestion(teacher, 3, "判断题", "T", null, null);
        long paperId = createPaper(teacher, "期中卷", 15.0);

        // 加题：q1 用默认分 5，q2 覆盖为 10（spec「组卷与分值覆盖」场景）
        JsonNode item1 = perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                "{\"questionId\":" + q1 + "}"), 200).get("data");
        assertEquals(1, item1.get("number").asInt());
        assertEquals(5.0, item1.get("score").asDouble());
        JsonNode item2 = perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                "{\"questionId\":" + q2 + ",\"score\":10}"), 200).get("data");
        assertEquals(2, item2.get("number").asInt());
        assertEquals(10.0, item2.get("score").asDouble());

        // 题目默认分仍为 5，未被试卷内覆盖值污染（互不影响）
        JsonNode question = perform(jsonGet("/api/questions/" + q2, teacher), 200).get("data");
        assertEquals(5.0, question.get("score").asDouble());

        // 调序：q2 排到第 1
        JsonNode afterOrder = perform(jsonPut("/api/papers/" + paperId + "/questions/order", teacher,
                "{\"questionIds\":[" + q2 + "," + q1 + "]}"), 200).get("data");
        assertEquals(q2, afterOrder.get("questions").get(0).get("questionId").asLong());
        assertEquals(1, afterOrder.get("questions").get(0).get("number").asInt());

        // 试卷内调分：q1 改为 2.5
        perform(jsonPut("/api/papers/" + paperId + "/questions/" + q1 + "/score", teacher,
                "{\"score\":2.5}"), 200);

        // 总分校验（spec「总分校验」场景）：总分 100 != 2.5+10=12.5 -> 400
        JsonNode mismatch = perform(jsonPut("/api/papers/" + paperId, teacher,
                "{\"totalScore\":100}"), 400);
        assertTrue(mismatch.get("message").asText().contains("不一致"));
        // 调整为一致后保存成功
        perform(jsonPut("/api/papers/" + paperId, teacher, "{\"totalScore\":12.5}"), 200);

        // 移出 q1：剩余题目重排为 1..n
        perform(jsonDelete("/api/papers/" + paperId + "/questions/" + q1, teacher), 200);
        JsonNode afterRemove = perform(jsonGet("/api/papers/" + paperId, teacher), 200).get("data");
        assertEquals(1, afterRemove.get("questionCount").asInt());
        assertEquals(1, afterRemove.get("questions").get(0).get("number").asInt());
        // 重复移出 -> 404
        perform(jsonDelete("/api/papers/" + paperId + "/questions/" + q1, teacher), 404);
    }

    @Test
    void randomDrawByRulesWithCapacityCheck() throws Exception {
        String teacher = registerTeacher();
        long mathTag = createTag(teacher, unique("数学"), "SUBJECT");
        for (int i = 1; i <= 5; i++) {
            createQuestion(teacher, 1, "数学单选" + i, "A", List.of("A选项", "B选项"), List.of(mathTag));
        }
        createQuestion(teacher, 3, "数学判断甲", "T", null, null);
        createQuestion(teacher, 3, "数学判断乙", "F", null, null);

        // 按标签抽题（spec「按规则抽题成功」场景）：返回 3 道不重复题目
        JsonNode preview = perform(jsonPost("/api/papers/random-draw/preview", teacher,
                rulesJson(null, null, List.of(mathTag), 3)), 200).get("data");
        assertEquals(3, preview.get("total").asInt());
        JsonNode drawn = preview.get("rules").get(0).get("questions");
        assertEquals(3, drawn.size());
        assertTrue(drawn.get(0).get("id").asLong() != drawn.get(1).get("id").asLong()
                && drawn.get(1).get("id").asLong() != drawn.get(2).get("id").asLong()
                && drawn.get(0).get("id").asLong() != drawn.get(2).get("id").asLong());

        // 题型规则：抽判断题 2 道
        JsonNode byType = perform(jsonPost("/api/papers/random-draw/preview", teacher,
                rulesJson(3, null, null, 2)), 200).get("data");
        assertEquals(2, byType.get("total").asInt());

        // 容量不足（spec「题库容量不足」场景）：候选 5 < 99 -> 400 提示调整
        JsonNode insufficient = perform(jsonPost("/api/papers/random-draw/preview", teacher,
                rulesJson(null, null, List.of(mathTag), 99)), 400);
        assertTrue(insufficient.get("message").asText().contains("不足"));

        // 确认入卷：抽 3 题进试卷（默认分 5，总分 15 对齐）
        long paperId = createPaper(teacher, "抽题卷", 15.0);
        JsonNode detail = perform(jsonPost("/api/papers/" + paperId + "/questions/random", teacher,
                rulesJson(null, null, List.of(mathTag), 3)), 200).get("data");
        assertEquals(3, detail.get("questionCount").asInt());
        for (JsonNode q : detail.get("questions")) {
            assertEquals(5.0, q.get("score").asDouble());
        }

        // 再次抽 3 题：已入卷 3 题被排除，仅剩 2 道候选 -> 400
        perform(jsonPost("/api/papers/" + paperId + "/questions/random", teacher,
                rulesJson(null, null, List.of(mathTag), 3)), 400);
    }

    @Test
    void snapshotLocksAndIsImmutable() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "快照单选", "A", List.of("甲", "乙"), null);
        long q2 = createQuestion(teacher, 3, "快照判断", "T", null, null);
        long q3 = createQuestion(teacher, 2, "快照多选", "A,B", List.of("甲", "乙", "丙"), null);
        long paperId = createPaper(teacher, "快照卷", 15.0);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher, "{\"questionId\":" + q1 + "}"), 200);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher, "{\"questionId\":" + q2 + "}"), 200);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher, "{\"questionId\":" + q3 + "}"), 200);

        // 生成快照并锁定
        JsonNode snapshot = perform(jsonPost("/api/papers/" + paperId + "/snapshot", teacher, null), 200)
                .get("data");
        assertEquals(3, snapshot.get("content").get("questions").size());
        JsonNode paper = perform(jsonGet("/api/papers/" + paperId, teacher), 200).get("data");
        assertEquals(1, paper.get("status").asInt());
        assertEquals(snapshot.get("id").asLong(), paper.get("snapshotId").asLong());

        // 首次读取（刷新语义：内容恒定）
        JsonNode firstRead = perform(jsonGet("/api/papers/" + paperId + "/snapshot", teacher), 200).get("data");

        // 锁定后一切组卷编辑被拒
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                "{\"questionId\":" + q1 + ",\"score\":5}"), 400);
        perform(jsonPut("/api/papers/" + paperId, teacher, "{\"totalScore\":20}"), 400);
        perform(jsonPost("/api/papers/" + paperId + "/snapshot", teacher, null), 400);
        perform(jsonDelete("/api/papers/" + paperId, teacher), 400);

        // 题目修改 + 软删除（spec「题目变更不影响快照」场景）
        perform(jsonPut("/api/questions/" + q1, teacher,
                questionJson(1, "快照单选-已改", "B", List.of("甲", "乙"), 5.0, 1, null)), 200);
        perform(jsonDelete("/api/questions/" + q2, teacher), 200);

        // 再次读取快照：内容与首次完全一致（软删的 q2 仍在快照中，改过的 q1 保持原文）
        JsonNode secondRead = perform(jsonGet("/api/papers/" + paperId + "/snapshot", teacher), 200).get("data");
        assertEquals(firstRead, secondRead);

        // 草稿视图如实呈现：软删题目打标 questionDeleted
        JsonNode detail = perform(jsonGet("/api/papers/" + paperId, teacher), 200).get("data");
        assertTrue(hasPaperQuestion(detail, q2, true));
    }

    @Test
    void snapshotGenerationGuards() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "守卫单选", "A", List.of("甲", "乙"), null);
        long q2 = createQuestion(teacher, 3, "守卫判断", "T", null, null);

        // 空试卷不可生成快照
        long emptyPaper = createPaper(teacher, "空卷", 0);
        perform(jsonPost("/api/papers/" + emptyPaper + "/snapshot", teacher, null), 400);

        // 总分不一致不可生成快照（双保险校验）
        long mismatchPaper = createPaper(teacher, "分值不符卷", 100.0);
        perform(jsonPost("/api/papers/" + mismatchPaper + "/questions", teacher,
                "{\"questionId\":" + q1 + "}"), 200);
        perform(jsonPost("/api/papers/" + mismatchPaper + "/snapshot", teacher, null), 400);

        // 含已软删题目不可生成快照（快照必须是完整副本）
        long deletedPaper = createPaper(teacher, "含删题卷", 10.0);
        perform(jsonPost("/api/papers/" + deletedPaper + "/questions", teacher,
                "{\"questionId\":" + q1 + "}"), 200);
        perform(jsonPost("/api/papers/" + deletedPaper + "/questions", teacher,
                "{\"questionId\":" + q2 + "}"), 200);
        perform(jsonDelete("/api/questions/" + q2, teacher), 200);
        perform(jsonPost("/api/papers/" + deletedPaper + "/snapshot", teacher, null), 400);

        // 未生成快照的试卷读取快照 -> 404
        perform(jsonGet("/api/papers/" + emptyPaper + "/snapshot", teacher), 404);
    }

    @Test
    void endToEndQuestionTagToPaperToSnapshot() throws Exception {
        // 端到端验收：建题 -> 打标签 -> 手动组卷 + 随机抽题 -> 生成快照
        String teacher = registerTeacher();
        long mathTag = createTag(teacher, unique("代数"), "SUBJECT");

        long manualQuestion = createQuestion(teacher, 1, "E2E 单选", "A", List.of("甲", "乙"), List.of(mathTag));
        long spareQuestion = createQuestion(teacher, 1, "E2E 候选", "B", List.of("甲", "乙"), List.of(mathTag));
        createQuestion(teacher, 3, "E2E 判断甲", "对", null, null);
        createQuestion(teacher, 3, "E2E 判断乙", "错", null, null);

        long paperId = createPaper(teacher, "E2E 卷", 10.0);
        // 手动选题（覆盖分值 5） + 判断题随机抽 1 题（默认分 5）= 总分 10
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                "{\"questionId\":" + manualQuestion + ",\"score\":5}"), 200);
        JsonNode afterDraw = perform(jsonPost("/api/papers/" + paperId + "/questions/random", teacher,
                rulesJson(3, null, null, 1)), 200).get("data");
        assertEquals(2, afterDraw.get("questionCount").asInt());

        JsonNode snapshot = perform(jsonPost("/api/papers/" + paperId + "/snapshot", teacher, null), 200)
                .get("data");
        assertEquals(2, snapshot.get("content").get("questions").size());
        assertEquals(10.0, snapshot.get("totalScore").asDouble());
        // 抽中的判断题在快照内保持归一化答案 T/F
        JsonNode drawnInSnapshot = snapshot.get("content").get("questions").get(1);
        assertTrue(drawnInSnapshot.get("correctAnswer").asText().matches("[TF]"));
        // 备用题目未入卷（候选排除已选）
        JsonNode detail = perform(jsonGet("/api/papers/" + paperId, teacher), 200).get("data");
        assertTrue(!hasPaperQuestion(detail, spareQuestion, null));
    }

    /** 试卷详情中是否存在某题目（deletedFlag 非 null 时同时校验 questionDeleted 标记）。 */
    private static boolean hasPaperQuestion(JsonNode detail, long questionId, Boolean deletedFlag) {
        for (JsonNode question : detail.get("questions")) {
            if (question.get("questionId").asLong() == questionId
                    && (deletedFlag == null
                        || question.get("questionDeleted").asBoolean() == deletedFlag)) {
                return true;
            }
        }
        return false;
    }
}
