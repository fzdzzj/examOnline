package com.exam.question;

import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题库批量删除集成测试（add-question-batch-delete，逐题结果信封语义）：
 * 部分成功仅合规子集落库软删且逐题 reason 与单删路径文案逐字一致、全部成功、
 * 全部失败零删除、请求内重复 400、空或超上限 400。全类不自行建表——建表以 schema.sql 为准。
 */
class QuestionBatchDeleteIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private ExamMapper examMapper;

    /** 与单删路径 getOwnedQuestion 的 404 文案逐字一致。 */
    private static final String REASON_NOT_FOUND = "题目不存在";
    /** 与单删路径 OwnershipGuard.assertOwner（resourceName="题目"）的 403 文案逐字一致。 */
    private static final String REASON_FORBIDDEN = "无权操作该题目（资源不属于当前用户）";
    /** 与单删路径 ExamPaperLockService.assertQuestionEditable 的 400 文案逐字一致。 */
    private static final String REASON_LOCKED = "考试进行中，试卷已锁定，不允许修改";

    /** 批量删除请求体：{"ids":[...]}。 */
    private String batchBody(long... ids) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode arr = body.putArray("ids");
        for (long id : ids) {
            arr.add(id);
        }
        return objectMapper.writeValueAsString(body);
    }

    /** 取信封中某题的失败原因（failed 列表按 id 建索引）。 */
    private String reasonOf(JsonNode data, long id) {
        Map<Long, String> reasons = new HashMap<>();
        for (JsonNode item : data.get("failed")) {
            reasons.put(item.get("id").asLong(), item.get("reason").asText());
        }
        return reasons.get(id);
    }

    private boolean succeededContains(JsonNode data, long id) {
        for (JsonNode item : data.get("succeeded")) {
            if (item.asLong() == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * 造一场进行中考试并让试卷引用给定题目（发布 → 手工驱动状态机推进）：
     * 该题目此后被 ExamPaperLockService 的题目级锁定校验拒绝软删。
     */
    private void lockQuestionByOngoingExam(String teacher, long questionId) throws Exception {
        long paperId = createPaper(teacher, unique("试卷"), 5);
        ObjectNode add = objectMapper.createObjectNode();
        add.put("questionId", questionId);
        add.put("score", 5);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                objectMapper.writeValueAsString(add)), 200);

        ObjectNode exam = objectMapper.createObjectNode();
        exam.put("title", unique("考试"));
        exam.put("paperId", paperId);
        exam.put("startTime", LocalDateTime.now().minusMinutes(1).toString());
        exam.put("endTime", LocalDateTime.now().plusHours(2).toString());
        exam.put("durationMinutes", 30);
        long examId = perform(jsonPost("/api/exams", teacher,
                objectMapper.writeValueAsString(exam)), 200).get("data").get("id").asLong();
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        assertEquals(Exam.STATUS_IN_PROGRESS, examMapper.selectById(examId).getStatus());
    }

    @Test
    void batchDeletePartialSuccessOnlyCompliantSubsetDeleted() throws Exception {
        String teacherA = registerTeacher();
        String teacherB = registerTeacher();

        long q1 = createQuestion(teacherA, 1, "批量删甲", "A", java.util.List.of("A选项", "B选项"), null);
        long q2 = createQuestion(teacherA, 3, "批量删乙", "对", null, null);
        long qLocked = createQuestion(teacherA, 1, "被进行中考试引用", "A", java.util.List.of("A选项", "B选项"), null);
        long qForeign = createQuestion(teacherB, 1, "教师B的题目", "B", java.util.List.of("A选项", "B选项"), null);
        long qAlreadyDeleted = createQuestion(teacherA, 2, "先走单删的题", "A,B", java.util.List.of("A选项", "B选项"), null);
        perform(jsonDelete("/api/questions/" + qAlreadyDeleted, teacherA), 200);

        lockQuestionByOngoingExam(teacherA, qLocked);

        // 混合批次：2 合规 + 被锁 + 非归属 + 已软删 + 不存在
        long missing = 999_999_999L;
        JsonNode data = perform(jsonPost("/api/questions/batch-delete", teacherA,
                batchBody(q1, qLocked, qForeign, qAlreadyDeleted, missing, q2)), 200).get("data");

        // 信封：仅合规子集 succeeded，逐题 reason 与单删路径文案一致
        assertTrue(succeededContains(data, q1));
        assertTrue(succeededContains(data, q2));
        assertEquals(2, data.get("succeeded").size());
        assertEquals(4, data.get("failed").size());
        assertEquals(REASON_LOCKED, reasonOf(data, qLocked));
        assertEquals(REASON_FORBIDDEN, reasonOf(data, qForeign));
        assertEquals(REASON_NOT_FOUND, reasonOf(data, qAlreadyDeleted));
        assertEquals(REASON_NOT_FOUND, reasonOf(data, missing));

        // 落库核验：合规子集真被软删，失败题目原样保留
        perform(jsonGet("/api/questions/" + q1, teacherA), 404);
        perform(jsonGet("/api/questions/" + q2, teacherA), 404);
        perform(jsonGet("/api/questions/" + qLocked, teacherA), 200);
        perform(jsonGet("/api/questions/" + qForeign, teacherB), 200);
    }

    @Test
    void batchDeleteAllSuccessRemovesAllFromList() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "全成甲", "A", java.util.List.of("A选项", "B选项"), null);
        long q2 = createQuestion(teacher, 1, "全成乙", "B", java.util.List.of("A选项", "B选项"), null);
        long q3 = createQuestion(teacher, 3, "全成丙", "对", null, null);

        JsonNode data = perform(jsonPost("/api/questions/batch-delete", teacher,
                batchBody(q1, q2, q3)), 200).get("data");
        assertEquals(3, data.get("succeeded").size());
        assertEquals(0, data.get("failed").size());

        perform(jsonGet("/api/questions/" + q1, teacher), 404);
        perform(jsonGet("/api/questions/" + q3, teacher), 404);
        // 题库列表刷新后不再展示（软删被逻辑删除过滤）
        assertEquals(0, perform(jsonGet("/api/questions?type=1&size=10", teacher), 200)
                .get("data").get("total").asLong());
    }

    @Test
    void batchDeleteAllFailedDeletesNothing() throws Exception {
        String teacherA = registerTeacher();
        String teacherB = registerTeacher();
        long qForeign = createQuestion(teacherB, 1, "他人题目", "A", java.util.List.of("A选项", "B选项"), null);
        long missing = 999_999_998L;

        // 全部失败：succeeded 空数组仍 200，零删除
        JsonNode data = perform(jsonPost("/api/questions/batch-delete", teacherA,
                batchBody(qForeign, missing)), 200).get("data");
        assertEquals(0, data.get("succeeded").size());
        assertEquals(2, data.get("failed").size());
        assertEquals(REASON_FORBIDDEN, reasonOf(data, qForeign));
        assertEquals(REASON_NOT_FOUND, reasonOf(data, missing));

        perform(jsonGet("/api/questions/" + qForeign, teacherB), 200);
    }

    @Test
    void batchDeleteRejectsDuplicateIds() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "重复批甲", "A", java.util.List.of("A选项", "B选项"), null);
        long q2 = createQuestion(teacher, 1, "重复批乙", "B", java.util.List.of("A选项", "B选项"), null);

        // 请求内重复 → 400「请求内存在重复题目」（与批量入卷口径一致），零删除
        JsonNode rejected = perform(jsonPost("/api/questions/batch-delete", teacher,
                batchBody(q1, q2, q1)), 400);
        assertEquals("请求内存在重复题目", rejected.get("message").asText());

        perform(jsonGet("/api/questions/" + q1, teacher), 200);
        perform(jsonGet("/api/questions/" + q2, teacher), 200);
    }

    @Test
    void batchDeleteRejectsEmptyAndOverLimitIds() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, "上限批甲", "A", java.util.List.of("A选项", "B选项"), null);

        // 空列表 → 400
        perform(jsonPost("/api/questions/batch-delete", teacher, batchBody()), 400);

        // 超上限（101 条）→ 400
        long[] oversized = new long[101];
        for (int i = 0; i < oversized.length; i++) {
            oversized[i] = 900_000_000L + i;
        }
        perform(jsonPost("/api/questions/batch-delete", teacher, batchBody(oversized)), 400);

        perform(jsonGet("/api/questions/" + q1, teacher), 200);
    }
}
