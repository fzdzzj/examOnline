package com.exam.message;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.service.JwtUtil;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.message.entity.UserMessage;
import com.exam.message.mapper.UserMessageMapper;
import com.exam.score.entity.ScoreReview;
import com.exam.score.mapper.ScoreReviewMapper;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 考务生命周期通知服务集成测试（add-exam-lifecycle-notifications）：
 * <ul>
 *   <li>三类通知投递——考试发布/成绩发布/成绩复核结果，均落站内消息中心（user_messages）；</li>
 *   <li>全部已读——老端点 unread-count 归零、list 全部 readFlag=1、按 user_id 隔离（他人未读数不受影响）；</li>
 *   <li>旁路异常容错——通知写入抛异常时，主考务操作（考试发布）不受影响、不回滚。</li>
 * </ul>
 * 造数走真实 MockMvc 主链路 + mapper 直插（无 @Sql，建表唯一来源 schema.sql）。
 */
class ExamLifecycleNotificationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Autowired
    private ScoreReviewMapper reviewMapper;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private JdbcTemplate jdbc;

    /** spy 而非 mock：未打桩的方法照常走真实 SQL；通知旁路测试在此注入 insert 异常。 */
    @MockitoSpyBean
    private UserMessageMapper messageMapper;

    // ==================== 造数 ====================

    private long preparePaper(String teacher) throws Exception {
        long q = createQuestion(teacher, 1, "单选：1+1=?", "A", List.of("A", "B", "C", "D"), null);
        long paperId = createPaper(teacher, unique("试卷"), 5);
        ObjectNode pq = objectMapper.createObjectNode();
        pq.put("questionId", q);
        pq.put("score", 5);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                objectMapper.writeValueAsString(pq)), 200);
        return paperId;
    }

    /** 造一场已绑定班级、尚未发布的考试，返回考试 ID。 */
    private long createBoundExam(String teacher, long paperId, long classId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        body.put("classId", classId);
        body.put("startTime", LocalDateTime.now().minusMinutes(1).toString());
        body.put("endTime", LocalDateTime.now().plusHours(2).toString());
        body.put("durationMinutes", 30);
        return perform(jsonPost("/api/exams", teacher, objectMapper.writeValueAsString(body)), 200)
                .get("data").get("id").asLong();
    }

    /**
     * 直插一条已批改答卷（score publish 通知的应考名单来源，status=GRADED）。
     * GradingSubmission 实体未映射 start_time/deadline_time（非空），走 JdbcTemplate 补全非空列。
     */
    private long insertGradedSubmission(long examId, long studentId) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("INSERT INTO exam_submissions "
                        + "(exam_id, student_id, start_time, deadline_time, submit_time, status, version, "
                        + "objective_score, subjective_score, total_score, grading_status, partial_graded) "
                        + "VALUES (?, ?, ?, ?, ?, 3, 0, 5.0, 0.0, 5.0, 1, 0)",
                examId, studentId, now.minusMinutes(30), now.plusMinutes(30), now.minusMinutes(5));
        return gradingSubmissionMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
                .eq(GradingSubmission::getExamId, examId)
                .eq(GradingSubmission::getStudentId, studentId)).getId();
    }

    /** 推动考试到「已批改」以便发布成绩。 */
    private void setExamGraded(long examId) {
        examMapper.update(null, Wrappers.<Exam>lambdaUpdate()
                .eq(Exam::getId, examId)
                .set(Exam::getStatus, Exam.STATUS_GRADED));
    }

    private long messageCountFor(long userId, Long examId, String title) {
        return messageMapper.selectCount(Wrappers.<UserMessage>lambdaQuery()
                .eq(UserMessage::getUserId, userId)
                .eq(examId != null, UserMessage::getExamId, examId)
                .eq(title != null, UserMessage::getTitle, title));
    }

    private long studentIdOf(String token) {
        return jwtUtil.parseAccessToken(token).getId();
    }

    // ==================== 考试发布通知 ====================

    @Test
    void examPublishNotifiesClassStudents() throws Exception {
        String teacher = registerTeacher();
        String studentA = registerStudent();
        String studentB = registerStudent();
        long aId = studentIdOf(studentA);
        long bId = studentIdOf(studentB);
        long classId = createClassForExam(teacher, studentA, studentB);
        long paperId = preparePaper(teacher);
        long examId = createBoundExam(teacher, paperId, classId);

        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);

        // 班级两名学生各收一条「考试发布通知」（examId 命中、未读）
        assertEquals(1, messageCountFor(aId, examId, "考试发布通知"));
        assertEquals(1, messageCountFor(bId, examId, "考试发布通知"));
        UserMessage forA = messageMapper.selectOne(Wrappers.<UserMessage>lambdaQuery()
                .eq(UserMessage::getUserId, aId).eq(UserMessage::getExamId, examId));
        assertEquals("考试发布通知", forA.getTitle());
        assertEquals(UserMessage.READ_FLAG_UNREAD, forA.getReadFlag());
        assertTrue(forA.getContent().contains("已发布"), "content 应含发布提示");
    }

    // ==================== 成绩发布通知 ====================

    @Test
    void scorePublishNotifiesGradedStudentsOnly() throws Exception {
        String teacher = registerTeacher();
        String pupilGraded = registerStudent();
        String pupilNotTook = registerStudent();
        long gradedId = studentIdOf(pupilGraded);
        long notTookId = studentIdOf(pupilNotTook);
        long classId = createClassForExam(teacher, pupilGraded, pupilNotTook);
        long paperId = preparePaper(teacher);
        long examId = createBoundExam(teacher, paperId, classId);

        // 仅 pupilGraded 有已批改答卷，pupilNotTook 未参加
        insertGradedSubmission(examId, gradedId);
        setExamGraded(examId);

        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("examIds").add(examId);
        perform(jsonPost("/api/scores/publish", teacher, objectMapper.writeValueAsString(body)), 200);
        assertEquals(Exam.STATUS_PUBLISHED, examMapper.selectById(examId).getStatus());

        // 实考考生（已批改答卷）收到成绩发布通知；未参考学生（班内但无答卷）不收
        assertEquals(1, messageCountFor(gradedId, examId, "成绩发布通知"));
        assertEquals(0, messageCountFor(notTookId, examId, "成绩发布通知"));
        UserMessage forGraded = messageMapper.selectOne(Wrappers.<UserMessage>lambdaQuery()
                .eq(UserMessage::getUserId, gradedId).eq(UserMessage::getExamId, examId)
                .eq(UserMessage::getTitle, "成绩发布通知"));
        assertTrue(forGraded.getContent().contains("成绩已发布"), "content 应含查分指引");
    }

    // ==================== 成绩复核结果通知 ====================

    @Test
    void scoreReviewHandledNotifiesApplyingStudent() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long studentId = studentIdOf(student);
        long classId = createClassForExam(teacher, student);
        long paperId = preparePaper(teacher);
        long examId = createBoundExam(teacher, paperId, classId);

        // 发成绩（需已批改答卷 + 已批改状态）
        insertGradedSubmission(examId, studentId);
        setExamGraded(examId);
        ObjectNode publish = objectMapper.createObjectNode();
        publish.putArray("examIds").add(examId);
        perform(jsonPost("/api/scores/publish", teacher, objectMapper.writeValueAsString(publish)), 200);

        // 学生提交复核申请（成绩已发布）
        ObjectNode applyBody = objectMapper.createObjectNode();
        applyBody.put("reason", "感觉分数计算有误");
        perform(jsonPost("/api/exams/" + examId + "/score-reviews", student,
                objectMapper.writeValueAsString(applyBody)), 200);
        ScoreReview review = reviewMapper.selectOne(Wrappers.<ScoreReview>lambdaQuery()
                .eq(ScoreReview::getExamId, examId).eq(ScoreReview::getStudentId, studentId));

        // 教师同意并调分 → 学生收到复核结果通知（含调整后总分）
        ObjectNode handle = objectMapper.createObjectNode();
        handle.put("action", "AGREE");
        handle.put("adjustedTotalScore", "5");
        handle.put("reason", "复核无误");
        perform(jsonPost("/api/score-reviews/" + review.getId() + "/handle", teacher,
                objectMapper.writeValueAsString(handle)), 200);

        UserMessage notify = messageMapper.selectOne(Wrappers.<UserMessage>lambdaQuery()
                .eq(UserMessage::getUserId, studentId)
                .eq(UserMessage::getExamId, examId)
                .eq(UserMessage::getTitle, "成绩复核结果通知"));
        assertEquals(UserMessage.READ_FLAG_UNREAD, notify.getReadFlag());
        assertTrue(notify.getContent().contains("已同意"), "同意调分应给出处理结论");
        assertTrue(notify.getContent().contains("调整后总分为 5"), "同意调分应包含调整后总分");
    }

    @Test
    void scoreReviewRejectNotifiesApplyingStudent() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long studentId = studentIdOf(student);
        long classId = createClassForExam(teacher, student);
        long paperId = preparePaper(teacher);
        long examId = createBoundExam(teacher, paperId, classId);

        insertGradedSubmission(examId, studentId);
        setExamGraded(examId);
        ObjectNode publish = objectMapper.createObjectNode();
        publish.putArray("examIds").add(examId);
        perform(jsonPost("/api/scores/publish", teacher, objectMapper.writeValueAsString(publish)), 200);

        ObjectNode applyBody = objectMapper.createObjectNode();
        applyBody.put("reason", "成绩有误");
        perform(jsonPost("/api/exams/" + examId + "/score-reviews", student,
                objectMapper.writeValueAsString(applyBody)), 200);
        ScoreReview review = reviewMapper.selectOne(Wrappers.<ScoreReview>lambdaQuery()
                .eq(ScoreReview::getExamId, examId).eq(ScoreReview::getStudentId, studentId));

        // 教师驳回（不调分）→ 学生收到复核结果通知（结论已驳回、无调整分）
        ObjectNode handle = objectMapper.createObjectNode();
        handle.put("action", "REJECT");
        handle.put("reason", "判分无误");
        perform(jsonPost("/api/score-reviews/" + review.getId() + "/handle", teacher,
                objectMapper.writeValueAsString(handle)), 200);

        UserMessage notify = messageMapper.selectOne(Wrappers.<UserMessage>lambdaQuery()
                .eq(UserMessage::getUserId, studentId)
                .eq(UserMessage::getExamId, examId)
                .eq(UserMessage::getTitle, "成绩复核结果通知"));
        assertTrue(notify.getContent().contains("已驳回"), "驳回应给出处理结论");
    }

    // ==================== 全部已读 ====================

    @Test
    void markAllReadZeroesOwnUnreadAndRespectsUserIsolation() throws Exception {
        String teacher = registerTeacher();
        String studentA = registerStudent();
        String studentB = registerStudent();
        long aId = studentIdOf(studentA);
        long bId = studentIdOf(studentB);
        long classId = createClassForExam(teacher, studentA, studentB);
        long paperId = preparePaper(teacher);
        // 两场考试分别触发考试发布通知，A/B 各收一条
        long examA = createBoundExam(teacher, paperId, classId);
        long examB = createBoundExam(teacher, paperId, classId);
        perform(jsonPost("/api/exams/" + examA + "/publish", teacher, null), 200);
        perform(jsonPost("/api/exams/" + examB + "/publish", teacher, null), 200);
        assertEquals(1, messageCountFor(aId, examA, "考试发布通知"));
        assertEquals(1, messageCountFor(aId, examB, "考试发布通知"));
        assertEquals(1, messageCountFor(bId, examA, "考试发布通知"));
        assertEquals(1, messageCountFor(bId, examB, "考试发布通知"));

        // A 全部标已读 → A 未读归 0、列表全已读；B 未读不受影响（user_id 隔离）
        assertEquals(2, perform(jsonGet("/api/messages/unread-count", studentA), 200).get("data").asLong());
        perform(jsonPut("/api/messages/read-all", studentA, null), 200);
        assertEquals(0, perform(jsonGet("/api/messages/unread-count", studentA), 200).get("data").asLong());
        assertEquals(2, perform(jsonGet("/api/messages/unread-count", studentB), 200).get("data").asLong(),
                "A 的全部已读不得影响 B 的未读数");

        JsonNode aList = perform(jsonGet("/api/messages", studentA), 200).get("data");
        assertEquals(2, aList.size());
        for (JsonNode m : aList) {
            assertEquals(1, m.get("readFlag").asInt(), "A 的列表消息应全部为已读态");
        }

        // 幂等：再次全部已读不报错
        perform(jsonPut("/api/messages/read-all", studentA, null), 200);

        // 未登录访问 → 401
        perform(jsonPut("/api/messages/read-all", null, null), 401);
    }

    // ==================== 旁路异常容错 ====================

    @Test
    void notificationFailureDoesNotBreakExamPublish() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);
        long paperId = preparePaper(teacher);
        long examId = createBoundExam(teacher, paperId, classId);

        // 注入：通知消息落库抛异常（模拟 DB 故障）。真实 inline-call 的异常被
        // ExamLifecycleNotificationService.notifyExamPublished 内部 try-catch 捕获降级。
        doThrow(new IllegalStateException("db down"))
                .when(messageMapper).insert(any(UserMessage.class));

        // 发布必须照常成功——通知旁路降级，绝不影响/回滚考务主事务
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        Exam published = examMapper.selectById(examId);
        assertEquals(1, published.getPublished().intValue(), "通知失败不得回滚考试发布（published=1）");
        assertEquals(Exam.STATUS_NOT_STARTED, published.getStatus().intValue());
        // 未投递任何通知（旁路降级后不产生半截消息）
        assertEquals(0, messageCountFor(studentIdOf(student), examId, "考试发布通知"));
    }
}