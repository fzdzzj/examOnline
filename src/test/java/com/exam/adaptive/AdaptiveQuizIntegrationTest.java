package com.exam.adaptive;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.adaptive.entity.CatQuizSession;
import com.exam.adaptive.entity.QuestionIrtParam;
import com.exam.adaptive.mapper.CatQuizSessionMapper;
import com.exam.adaptive.mapper.QuestionIrtParamMapper;
import com.exam.auth.service.JwtUtil;
import com.exam.score.entity.PracticeAttempt;
import com.exam.score.mapper.PracticeAttemptMapper;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAT 自适应测验三端点集成测试（spec add-irt-adaptive-testing，H2 + MockMvc 全链路）：
 *
 * <ul>
 *   <li>开局：b 最接近 0 的首题、会话落库、响应不泄露答案解析；</li>
 *   <li>作答：判分同源（GradingStrategyRegistry）→ 即时 θ 上调 → 落 practice_attempt；</li>
 *   <li>终止：达最大题数返回终局信号，能力报告给出 θ/SE/正确率/终止原因/逐题轨迹；</li>
 *   <li>归属隔离：他人会话作答与报告一律 403；</li>
 *   <li>题池为空：按 400 明确拒绝（不静默返回空卷）。</li>
 * </ul>
 *
 * <p>题池参数由 Mapper 直插（不在本用例重复验证 CTT 聚合——那由 IrtParamServiceTest 覆盖），
 * 全程不建表、不使用 @Sql（AGENTS 硬约定 2）。
 */
class AdaptiveQuizIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private QuestionIrtParamMapper questionIrtParamMapper;
    @Autowired
    private CatQuizSessionMapper catQuizSessionMapper;
    @Autowired
    private PracticeAttemptMapper practiceAttemptMapper;

    private String teacher;
    private String studentA;
    private String studentB;
    private long easyQuestionId;
    private long mediumQuestionId;
    private long hardQuestionId;

    @BeforeEach
    void setUpAdaptiveFixture() throws Exception {
        clearAdaptiveTables();
        teacher = registerTeacher();
        studentA = registerStudent();
        studentB = registerStudent();

        easyQuestionId = createQuestion(teacher, 1, unique("CAT易"), "A", List.of("A", "B"), null);
        mediumQuestionId = createQuestion(teacher, 1, unique("CAT中"), "A", List.of("A", "B"), null);
        hardQuestionId = createQuestion(teacher, 1, unique("CAT难"), "A", List.of("A", "B"), null);
        seedParam(easyQuestionId, 1.2, -2.0, 0.25);
        seedParam(mediumQuestionId, 1.2, 0.05, 0.25);
        seedParam(hardQuestionId, 2.5, 2.0, 0.25);
    }

    // ==================== 开局 ====================

    @Test
    @DisplayName("开局：返回 b 最接近 0 的首题，会话落库，响应不含正确答案与解析")
    void startReturnsMediumQuestionWithoutAnswerLeak() throws Exception {
        JsonNode data = perform(jsonPost("/api/scores/my/adaptive-quiz/start", studentA, "{}"), 200)
                .get("data");

        assertTrue(data.get("sessionId").asLong() > 0);
        assertEquals("IN_PROGRESS", data.get("status").asText());
        assertEquals(mediumQuestionId, data.get("currentQuestion").get("questionId").asLong());
        assertEquals(10, data.get("maxItems").asInt());
        assertEquals(0.30, data.get("seThreshold").asDouble(), 1e-9);
        assertEquals("A", data.get("currentQuestion").get("choices").get(0).asText());
        // 作答前不泄露：首题视图不含正确答案与解析
        assertFalse(data.get("currentQuestion").has("correctAnswer"));
        assertFalse(data.get("currentQuestion").has("analysis"));
        // 会话确实落 DB（CAT 正确性不依赖 Redis）
        CatQuizSession session = catQuizSessionMapper.selectById(data.get("sessionId").asLong());
        assertNotNull(session);
        assertEquals(CatQuizSession.STATUS_IN_PROGRESS, session.getStatus());
        assertEquals(mediumQuestionId, session.getCurrentQuestionId());
    }

    @Test
    @DisplayName("题池为空：开局按 400 明确拒绝，不返回空卷")
    void startRejectsWhenPoolEmpty() throws Exception {
        clearAdaptiveTables();

        JsonNode body = perform(jsonPost("/api/scores/my/adaptive-quiz/start", studentB, "{}"), 400);

        assertTrue(body.get("message").asText().contains("题池"));
    }

    // ==================== 作答 ====================

    @Test
    @DisplayName("作答：同源判分 → θ 上调 → 逐题落 practice_attempt → 返回下一题")
    void answerUpdatesThetaAndPersistsPracticeAttempt() throws Exception {
        JsonNode start = perform(jsonPost("/api/scores/my/adaptive-quiz/start", studentA, "{}"), 200)
                .get("data");
        long sessionId = start.get("sessionId").asLong();
        long questionId = start.get("currentQuestion").get("questionId").asLong();

        JsonNode data = perform(jsonPost("/api/scores/my/adaptive-quiz/" + sessionId + "/answer",
                studentA, answerBody(questionId, "A")), 200).get("data");

        assertTrue(data.get("correct").asBoolean());
        assertEquals(1, data.get("answeredCount").asInt());
        assertTrue(data.get("theta").asDouble() > 0.2, "答对后 θ 必须上调");
        assertFalse(data.get("terminated").asBoolean());
        assertNotNull(data.get("nextQuestion"));
        assertTrue(data.get("nextQuestion").get("questionId").asLong() != questionId);

        Long studentId = jwtUtil.parseAccessToken(studentA).getId();
        List<PracticeAttempt> attempts = practiceAttemptMapper.selectList(
                Wrappers.<PracticeAttempt>lambdaQuery()
                        .eq(PracticeAttempt::getStudentId, studentId)
                        .eq(PracticeAttempt::getQuestionId, questionId));
        assertEquals(1, attempts.size(), "CAT 作答必须同源落 practice_attempt");
        assertTrue(Boolean.TRUE.equals(attempts.get(0).getIsCorrect()));
    }

    // ==================== 终止与报告 ====================

    @Test
    @DisplayName("终止：达最大题数 → 终局信号；能力报告含 θ/SE/正确率/终止原因/逐题轨迹")
    void terminatesAtMaxItemsAndReportReturnsAbility() throws Exception {
        JsonNode start = perform(jsonPost("/api/scores/my/adaptive-quiz/start", studentA,
                "{\"maxItems\":1}"), 200).get("data");
        long sessionId = start.get("sessionId").asLong();
        long questionId = start.get("currentQuestion").get("questionId").asLong();

        JsonNode answer = perform(jsonPost("/api/scores/my/adaptive-quiz/" + sessionId + "/answer",
                studentA, answerBody(questionId, "A")), 200).get("data");

        assertTrue(answer.get("terminated").asBoolean());
        assertEquals("MAX_ITEMS", answer.get("terminationReason").asText());
        assertTrue(answer.get("nextQuestion") == null || answer.get("nextQuestion").isNull());
        assertTrue(answer.get("theta").asDouble() > 0.0);

        JsonNode report = perform(jsonGet("/api/scores/my/adaptive-quiz/" + sessionId + "/report",
                studentA), 200).get("data");

        assertEquals(sessionId, report.get("sessionId").asLong());
        assertEquals("COMPLETED", report.get("status").asText());
        assertEquals("MAX_ITEMS", report.get("terminationReason").asText());
        assertEquals(1, report.get("answeredCount").asInt());
        assertEquals(1, report.get("correctCount").asInt());
        assertEquals(100.0, report.get("accuracy").asDouble(), 1e-9);
        assertTrue(report.get("theta").asDouble() > 0.0);
        assertTrue(report.get("se").asDouble() > 0.0);
        assertEquals(1, report.get("items").size());
        assertEquals(questionId, report.get("items").get(0).get("questionId").asLong());
        assertTrue(report.get("items").get(0).get("correct").asBoolean());
        assertTrue(report.get("items").get(0).get("thetaAfter").asDouble() > 0.0);
    }

    // ==================== 归属隔离 ====================

    @Test
    @DisplayName("归属隔离：他人会话的报告与作答一律 403")
    void otherStudentCannotReadOrAnswerSession() throws Exception {
        JsonNode start = perform(jsonPost("/api/scores/my/adaptive-quiz/start", studentA, "{}"), 200)
                .get("data");
        long sessionId = start.get("sessionId").asLong();
        long questionId = start.get("currentQuestion").get("questionId").asLong();

        perform(jsonGet("/api/scores/my/adaptive-quiz/" + sessionId + "/report", studentB), 403);
        perform(jsonPost("/api/scores/my/adaptive-quiz/" + sessionId + "/answer",
                studentB, answerBody(questionId, "A")), 403);
    }

    // ==================== 夹具 ====================

    private String answerBody(long questionId, String answer) {
        return "{\"questionId\":" + questionId + ",\"answer\":\"" + answer + "\"}";
    }

    private void seedParam(long questionId, double a, double b, double c) {
        QuestionIrtParam param = new QuestionIrtParam();
        param.setQuestionId(questionId);
        param.setDiscrimination(BigDecimal.valueOf(a));
        param.setDifficulty(BigDecimal.valueOf(b));
        param.setGuessing(BigDecimal.valueOf(c));
        param.setSampleSize(40);
        param.setPoolAvailable(true);
        param.setCalibratedTime(LocalDateTime.now());
        questionIrtParamMapper.insert(param);
    }

    private void clearAdaptiveTables() {
        List<CatQuizSession> sessions = catQuizSessionMapper.selectList(null);
        if (sessions != null && !sessions.isEmpty()) {
            catQuizSessionMapper.deleteBatchIds(sessions.stream().map(CatQuizSession::getId).toList());
        }
        List<QuestionIrtParam> params = questionIrtParamMapper.selectList(null);
        if (params != null && !params.isEmpty()) {
            questionIrtParamMapper.deleteBatchIds(params.stream().map(QuestionIrtParam::getId).toList());
        }
    }
}
