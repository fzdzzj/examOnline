package com.exam.paper;

import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试卷分值智能配平集成测试（add-batch-tagging-and-score-balancing）：
 * 离散最大余数保和算法严格保证各题分值之和 === 目标总分；EVEN 一键均分、
 * BY_TYPE 按题型配比；锁定试卷/待配平 0 题/目标不可被步长整除/超出下限 400 拦截，
 * 且拒绝时零数据更改。全类不自行建表——建表以 schema.sql 为准，测试禁 @Sql。
 */
class PaperRebalanceScoresIntegrationTest extends IntegrationTestBase {

    /** 便捷：把题目加入试卷（默认分 5），返回详情。 */
    private JsonNode addQuestions(String teacher, long paperId, long... questionIds) throws Exception {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < questionIds.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"questionId\":").append(questionIds[i]).append('}');
        }
        json.append("]}");
        return perform(jsonPost("/api/papers/" + paperId + "/questions/batch", teacher,
                json.toString()), 200).get("data");
    }

    /** 汇总详情内各题分值之和。 */
    private double sumScores(JsonNode detail) {
        double sum = 0;
        for (JsonNode q : detail.get("questions")) {
            sum += q.get("score").asDouble();
        }
        return sum;
    }

    private String rebalanceBody(String mode, double target, String fixedJson) {
        String targetPart = String.format("\"targetScore\":%.1f", target);
        String stepPart = "\"step\":0.5";
        if (fixedJson == null || fixedJson.isBlank()) {
            return String.format("{\"mode\":\"%s\",%s,%s}", mode, stepPart, targetPart);
        }
        // fixed 放在最后，将其嵌入合法 JSON
        String inner = String.format("{\"mode\":\"%s\",%s,%s,%s}", mode, stepPart, targetPart, fixedJson);
        return inner;
    }

    @Test
    void evenModeDistributesByLargestRemainderWithExactSum() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "配平单选一", "A", List.of("甲", "乙"), null);
        long q2 = createQuestion(teacher, 3, "配平判断二", "对", null, null);
        long q3 = createQuestion(teacher, 2, "配平多选三", "A,B", List.of("甲", "乙", "丙"), null);
        long paperId = createPaper(teacher, "均分卷", 0);
        // 默认分 5×3=15，申报总分先按 15 建卷以满足分组前一致性（后续以请求 target 为准）
        addQuestions(teacher, paperId, q1, q2, q3);

        // 3 题、目标 10：T=20、Q=floor(20/3)=6、R=2 → 前两题 3.5 分、最后一题 3 分，和=10
        JsonNode detail = perform(jsonPost("/api/papers/" + paperId + "/questions/scores/rebalance",
                teacher, rebalanceBody("EVEN", 10, null)), 200).get("data");
        assertTrue(Math.abs(sumScores(detail) - 10.0) < 0.001, "EVEN 保和 sum 应等于目标 10");
        JsonNode questions = detail.get("questions");
        assertEquals(3.5, questions.get(0).get("score").asDouble());
        assertEquals(3.5, questions.get(1).get("score").asDouble());
        assertEquals(3.0, questions.get(2).get("score").asDouble());
    }

    @Test
    void evenModeDefaultTargetUsesPaperTotalScore() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "默认目标单选一", "A", List.of("甲", "乙"), null);
        long q2 = createQuestion(teacher, 3, "默认目标判断二", "对", null, null);
        long paperId = createPaper(teacher, "默认目标卷", 7); // 申报总分 7
        addQuestions(teacher, paperId, q1, q2);

        // mode=EVEN 且不传 targetScore：以申报总分 7 为目标，T=14、Q=7、R=0 → 各 3.5
        String body = "{\"mode\":\"EVEN\",\"step\":0.5}";
        JsonNode detail = perform(jsonPost("/api/papers/" + paperId + "/questions/scores/rebalance",
                teacher, body), 200).get("data");
        assertTrue(Math.abs(sumScores(detail) - 7.0) < 0.001);
        assertEquals(3.5, detail.get("questions").get(0).get("score").asDouble());
        assertEquals(3.5, detail.get("questions").get(1).get("score").asDouble());
    }

    @Test
    void byTypeModeAssignsFixedScoresAndAutoDistributesRemainder() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "单选固定", "A", List.of("甲", "乙"), null);
        long q2 = createQuestion(teacher, 1, "单选固定二", "B", List.of("甲", "乙"), null);
        long q3 = createQuestion(teacher, 4, "简答自动", "参考", null, null);
        long q4 = createQuestion(teacher, 4, "简答自动二", "参考二", null, null);
        long paperId = createPaper(teacher, "配比卷", 0);
        addQuestions(teacher, paperId, q1, q2, q3, q4);

        // 目标 16：单选(1) 固定 2 分 ×2=4，剩余 12 分给 2 道简答(4)→ T=24、Q=12、R=0 → 各 6 分
        String fixed = "\"fixedScores\":{\"1\":2.0}";
        JsonNode detail = perform(jsonPost("/api/papers/" + paperId + "/questions/scores/rebalance",
                teacher, rebalanceBody("BY_TYPE", 16, fixed)), 200).get("data");
        assertTrue(Math.abs(sumScores(detail) - 16.0) < 0.001, "BY_TYPE 保和 sum 应等于目标 16");

        JsonNode questions = detail.get("questions");
        for (JsonNode q : questions) {
            if (q.get("questionType").asInt() == 1) {
                assertEquals(2.0, q.get("score").asDouble(), "单选应得固定 2 分");
            } else {
                assertEquals(6.0, q.get("score").asDouble(), "简答应自动均分 6 分");
            }
        }
    }

    @Test
    void byTypeWithNonDivisibleRemainderUsesLargestRemainder() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "配比单选", "A", List.of("甲", "乙"), null);
        long q2 = createQuestion(teacher, 4, "配比简答一", "参考", null, null);
        long q3 = createQuestion(teacher, 4, "配比简答二", "参考二", null, null);
        long paperId = createPaper(teacher, "配比余数卷", 0);
        addQuestions(teacher, paperId, q1, q2, q3);

        // 目标 7：单选(1) 固定 2 分，剩余 5 给 2 道简答 → T=10、Q=5、R=0 → 各 2.5
        String fixed = "\"fixedScores\":{\"1\":2.0}";
        JsonNode detail = perform(jsonPost("/api/papers/" + paperId + "/questions/scores/rebalance",
                teacher, rebalanceBody("BY_TYPE", 7, fixed)), 200).get("data");
        assertTrue(Math.abs(sumScores(detail) - 7.0) < 0.001);
        for (JsonNode q : detail.get("questions")) {
            if (q.get("questionType").asInt() != 1) {
                assertEquals(2.5, q.get("score").asDouble());
            }
        }
    }

    @Test
    void lockedPaperRejectsRebalanceWithoutDataChange() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "锁定配平单选", "A", List.of("甲", "乙"), null);
        long paperId = createPaper(teacher, "锁定配平卷", 5);
        JsonNode detail = addQuestions(teacher, paperId, q1);
        // 生成快照即锁定（1 题默认分 5 = 总分 5，可锁定）
        perform(jsonPost("/api/papers/" + paperId + "/snapshot", teacher, null), 200);

        JsonNode rejected = perform(jsonPost("/api/papers/" + paperId + "/questions/scores/rebalance",
                teacher, rebalanceBody("EVEN", 5, null)), 400);
        assertTrue(rejected.get("message").asText().contains("已锁定"));
        JsonNode after = perform(jsonGet("/api/papers/" + paperId, teacher), 200).get("data");
        assertEquals(5.0, after.get("questions").get(0).get("score").asDouble());
    }

    @Test
    void rejectsTargetNotMultipleOfStepAndBelowMinHalf() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "非法目标单选", "A", List.of("甲", "乙"), null);
        long q2 = createQuestion(teacher, 3, "非法目标判断", "对", null, null);
        long paperId = createPaper(teacher, "非法目标卷", 0);
        JsonNode before = addQuestions(teacher, paperId, q1, q2);

        // 目标 10.3 非 0.5 整数倍 → 400
        JsonNode notMultiple = perform(jsonPost("/api/papers/" + paperId + "/questions/scores/rebalance",
                teacher, rebalanceBody("EVEN", 10.3, null)), 400);
        assertTrue(notMultiple.get("message").asText().contains("整数倍"));

        // 目标 0.5、2 题 → T=1、Q=0、R=1 → 最小单题 0 < 0.5 → 400
        JsonNode belowMin = perform(jsonPost("/api/papers/" + paperId + "/questions/scores/rebalance",
                teacher, rebalanceBody("EVEN", 0.5, null)), 400);
        assertTrue(belowMin.get("message").asText().contains("低于 0.5"));

        // 非法 mode → 400
        perform(jsonPost("/api/papers/" + paperId + "/questions/scores/rebalance",
                teacher, rebalanceBody("FOO", 10, null)), 400);

        // 以上均不改动
        JsonNode after = perform(jsonGet("/api/papers/" + paperId, teacher), 200).get("data");
        assertEquals(before.get("questions").get(0).get("score").asDouble(),
                after.get("questions").get(0).get("score").asDouble());
        assertEquals(before.get("questions").get(1).get("score").asDouble(),
                after.get("questions").get(1).get("score").asDouble());
    }

    @Test
    void rejectsEmptyPaperAndOwnershipViolation() throws Exception {
        String teacherA = registerTeacher();
        String teacherB = registerTeacher();
        long q1 = createQuestion(teacherB, 1, "他人配平单选", "A", List.of("甲", "乙"), null);
        long paperA = createPaper(teacherA, "空卷", 0);
        long paperB = createPaper(teacherB, "他人卷", 5);
        addQuestions(teacherB, paperB, q1);

        // 空卷（无题目）→ 400
        perform(jsonPost("/api/papers/" + paperA + "/questions/scores/rebalance",
                teacherA, rebalanceBody("EVEN", 10, null)), 400);

        // 教师 A 对教师 B 的卷配平 → 403 越权
        JsonNode forbidden = perform(jsonPost("/api/papers/" + paperB + "/questions/scores/rebalance",
                teacherA, rebalanceBody("EVEN", 5, null)), 403);
        assertTrue(forbidden.get("message").asText().contains("无权操作"));
    }
}