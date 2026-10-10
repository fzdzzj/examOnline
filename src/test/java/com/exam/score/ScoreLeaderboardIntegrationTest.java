package com.exam.score;

import com.exam.auth.service.JwtUtil;
import com.exam.clazz.entity.UserClass;
import com.exam.clazz.mapper.UserClassMapper;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 班级匿名榜单接口集成测试（add-class-leaderboard，真实链路）：
 * 覆盖：
 * 1. 已发布考试返回匿名前 10、并列同分同名次跳号（与 publishPreview 口径一致）；
 * 2. 本人两种形态（在前 10 内实名高亮 myRow=null，不在前 10 榜尾返回 myRow）；
 * 3. 门控：未发布考试 404；无本人成绩记录 404；
 * 4. 门控：非本班考生 403；
 * 5. 匿名不泄露他人 studentId / 学号等可定位标识；
 * 6. LIMIT 10 边界截断。
 */
class ScoreLeaderboardIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Autowired
    private ExamSubmissionMapper examSubmissionMapper;
    @Autowired
    private UserClassMapper userClassMapper;
    @Autowired
    private JwtUtil jwtUtil;

    private long studentIdOf(String token) {
        return jwtUtil.parseAccessToken(token).getId();
    }

    private long createExamWithClass(String teacherToken, long classId, int status) throws Exception {
        long paperId = createPaper(teacherToken, unique("试卷"), 100);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("期末考"));
        body.put("paperId", paperId);
        body.put("classId", classId);
        body.put("startTime", LocalDateTime.now().minusHours(2).toString());
        body.put("endTime", LocalDateTime.now().minusHours(1).toString());
        body.put("durationMinutes", 60);

        JsonNode data = perform(jsonPost("/api/exams", teacherToken,
                objectMapper.writeValueAsString(body)), 200).get("data");
        long examId = data.get("id").asLong();

        Exam exam = examMapper.selectById(examId);
        exam.setStatus(status);
        exam.setPublished(status == Exam.STATUS_PUBLISHED ? 1 : 0);
        examMapper.updateById(exam);
        return examId;
    }

    private void createGradedSubmission(long examId, long studentId, String score) {
        ExamSubmission sub = new ExamSubmission();
        sub.setExamId(examId);
        sub.setStudentId(studentId);
        sub.setStatus(ExamSubmission.STATUS_GRADED);
        sub.setStartTime(LocalDateTime.now().minusMinutes(50));
        sub.setDeadlineTime(LocalDateTime.now());
        sub.setPaperJson("{}");
        examSubmissionMapper.insert(sub);

        GradingSubmission gs = new GradingSubmission();
        gs.setId(sub.getId());
        gs.setStatus(ExamSubmission.STATUS_GRADED);
        gs.setObjectiveScore(new BigDecimal(score));
        gs.setSubjectiveScore(BigDecimal.ZERO);
        gs.setTotalScore(new BigDecimal(score));
        gs.setGradingStatus(1);
        gradingSubmissionMapper.updateById(gs);
    }

    @Test
    void leaderboardFlowWithAnonymizationTiesAndMeInTop() throws Exception {
        String teacher = registerTeacher();
        String student1 = registerStudent();
        String student2 = registerStudent();
        String student3 = registerStudent();
        String student4 = registerStudent();

        long classId = createClassForExam(teacher, student1, student2, student3, student4);
        long examId = createExamWithClass(teacher, classId, Exam.STATUS_PUBLISHED);

        long s1Id = studentIdOf(student1);
        long s2Id = studentIdOf(student2);
        long s3Id = studentIdOf(student3);
        long s4Id = studentIdOf(student4);

        // 构造分数：s1=100(第1), s2=90(第2), s3=90(第2并列), s4=80(第4跳号)
        createGradedSubmission(examId, s1Id, "100.0");
        createGradedSubmission(examId, s2Id, "90.0");
        createGradedSubmission(examId, s3Id, "90.0");
        createGradedSubmission(examId, s4Id, "80.0");

        // student2 查询榜单（student2 排第 2 名，在前 10 内）
        JsonNode resp = perform(jsonGet("/api/exams/" + examId + "/scores/leaderboard", student2), 200)
                .get("data");

        assertEquals(examId, resp.get("examId").asLong());
        assertTrue(resp.get("myRow") == null || resp.get("myRow").isNull(), "本人在前 10 内时 myRow 应为 null");

        JsonNode top = resp.get("top");
        assertEquals(4, top.size());

        // 第 1 名：s1，非本人 -> 脱敏，isMe=false，无 studentId
        assertEquals(1, top.get(0).get("rank").asInt());
        assertEquals(100.0, top.get(0).get("totalScore").asDouble(), 0.01);
        assertTrue(top.get(0).get("displayName").asText().endsWith("**"));
        assertFalse(top.get(0).get("isMe").asBoolean());
        assertNull(top.get(0).get("studentId"), "榜单条目严禁包含 studentId");

        // 第 2 名（并列）：s2（本人）-> 实名，isMe=true
        assertEquals(2, top.get(1).get("rank").asInt());
        assertEquals(90.0, top.get(1).get("totalScore").asDouble(), 0.01);
        assertFalse(top.get(1).get("displayName").asText().contains("**"), "本人行必须为实名");
        assertTrue(top.get(1).get("isMe").asBoolean(), "本人行 isMe 必须为 true");

        // 第 2 名（并列）：s3（他人）-> 脱敏，isMe=false
        assertEquals(2, top.get(2).get("rank").asInt());
        assertEquals(90.0, top.get(2).get("totalScore").asDouble(), 0.01);
        assertTrue(top.get(2).get("displayName").asText().endsWith("**"));
        assertFalse(top.get(2).get("isMe").asBoolean());

        // 第 4 名（跳号）：s4 -> rank=4
        assertEquals(4, top.get(3).get("rank").asInt());
        assertEquals(80.0, top.get(3).get("totalScore").asDouble(), 0.01);
        assertTrue(top.get(3).get("displayName").asText().endsWith("**"));
        assertFalse(top.get(3).get("isMe").asBoolean());
    }

    @Test
    void leaderboardReturnsMyRowWhenOutsideTop10AndRespectsLimit10() throws Exception {
        String teacher = registerTeacher();
        String myToken = registerStudent();
        long myId = studentIdOf(myToken);

        // 注册 12 名同学 + 本人共 13 人
        String[] otherTokens = new String[12];
        long[] otherIds = new long[12];
        for (int i = 0; i < 12; i++) {
            otherTokens[i] = registerStudent();
            otherIds[i] = studentIdOf(otherTokens[i]);
        }

        long classId = createClassForExam(teacher, otherTokens);
        joinClass(teacher, classId, myToken);

        long examId = createExamWithClass(teacher, classId, Exam.STATUS_PUBLISHED);

        // 前 12 名同学总分 99 到 88
        for (int i = 0; i < 12; i++) {
            createGradedSubmission(examId, otherIds[i], String.valueOf(99 - i));
        }
        // 本人总分 60（第 13 名）
        createGradedSubmission(examId, myId, "60.0");

        JsonNode resp = perform(jsonGet("/api/exams/" + examId + "/scores/leaderboard", myToken), 200)
                .get("data");

        JsonNode top = resp.get("top");
        assertEquals(10, top.size(), "top 必须严格限制为 LIMIT 10 行");
        for (JsonNode item : top) {
            assertFalse(item.get("isMe").asBoolean(), "前 10 名中不应有本人");
            assertTrue(item.get("displayName").asText().endsWith("**"));
            assertNull(item.get("studentId"));
        }

        // 本人行在 myRow 单独返回
        JsonNode myRow = resp.get("myRow");
        assertNotNull(myRow);
        assertFalse(myRow.isNull());
        assertEquals(13, myRow.get("rank").asInt());
        assertEquals(60.0, myRow.get("totalScore").asDouble(), 0.01);
        assertTrue(myRow.get("isMe").asBoolean());
    }

    @Test
    void leaderboardRejectsUnpublishedExamWith404() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);
        long examId = createExamWithClass(teacher, classId, Exam.STATUS_GRADED); // 未发布状态
        createGradedSubmission(examId, studentIdOf(student), "90.0");

        JsonNode resp = perform(jsonGet("/api/exams/" + examId + "/scores/leaderboard", student), 404);
        assertEquals(404, resp.get("code").asInt());
        assertEquals("暂无本人成绩记录", resp.get("message").asText());
    }

    @Test
    void leaderboardRejectsStudentWithoutScoreRecordWith404() throws Exception {
        String teacher = registerTeacher();
        String studentWithScore = registerStudent();
        String studentWithoutScore = registerStudent();

        long classId = createClassForExam(teacher, studentWithScore, studentWithoutScore);
        long examId = createExamWithClass(teacher, classId, Exam.STATUS_PUBLISHED);
        createGradedSubmission(examId, studentIdOf(studentWithScore), "90.0");

        // studentWithoutScore 在班内但没有成绩记录
        JsonNode resp = perform(jsonGet("/api/exams/" + examId + "/scores/leaderboard", studentWithoutScore), 404);
        assertEquals(404, resp.get("code").asInt());
        assertEquals("暂无本人成绩记录", resp.get("message").asText());
    }

    @Test
    void leaderboardRejectsNonExamineeWith403() throws Exception {
        String teacher = registerTeacher();
        String member = registerStudent();
        String outsider = registerStudent();

        long classId = createClassForExam(teacher, member); // 仅 member 入班
        long examId = createExamWithClass(teacher, classId, Exam.STATUS_PUBLISHED);
        createGradedSubmission(examId, studentIdOf(member), "90.0");

        // outsider 不在班内且无答卷记录 -> 403
        JsonNode resp = perform(jsonGet("/api/exams/" + examId + "/scores/leaderboard", outsider), 403);
        assertEquals(403, resp.get("code").asInt());
        assertTrue(resp.get("message").asText().contains("非本场考试考生"));
    }
}
