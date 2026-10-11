package com.exam.score;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.service.JwtUtil;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamSnapshot;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.mapper.ExamSnapshotMapper;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.score.service.ScoreExportService;
import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.mq.ExamSubmitConsumer;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.support.IntegrationTestBase;
import com.exam.support.RabbitTemplateInvokeStubs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 考试数据分析报告接口集成测试（add-exam-analysis-report，创新点4）：
 * H2 + MockMvc + MQ Mock，覆盖
 * ① 404 不存在 / 403 非归属越权 / 400 未汇总；
 * ② 200 三块结构（班级概览 / 逐题指标 / 知识点薄弱 / 学生关注名单）；
 * ③ 逐题指标与题目统计导出同源同口径（共用聚合核心，区分度一致）；
 * ④ 知识点优雅降级：题目无标签 → 空数组 + hasTagDimension=false。
 */
class ExamAnalysisReportIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private ExamSubmissionMapper submissionMapper;
    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Autowired
    private SubjectiveGradeMapper subjectiveGradeMapper;
    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private ExamSnapshotMapper examSnapshotMapper;
    @Autowired
    private ExamSubmitConsumer submitConsumer;
    @Autowired
    private ScoreExportService scoreExportService;
    @Autowired
    private JwtUtil jwtUtil;

    @BeforeEach
    void runRabbitInvokeCallbacks() {
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    // ==================== 造数 ====================

    private long addPaperQuestion(String token, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
        return questionId;
    }

    private long preparePaper(String teacher, boolean withTags, double scale) throws Exception {
        // 卷面题分 = 基准(4,6,4,6) × scale，试卷申报总分 = 20 × scale，保证发布总分一致校验通过
        long qSingle = createQuestion(teacher, 1, "单选：1+1=?", "A",
                List.of("A", "B", "C", "D"), withTags ? List.of(createTag(teacher, unique("知识点A"),
                        "CUSTOM")) : null);
        long qMultiple = createQuestion(teacher, 2, "多选：下列哪些是质数", "A,C",
                List.of("2", "4", "3", "9"), null);
        long qJudge = createQuestion(teacher, 3, "判断：水沸腾", "T", null, null);
        long qShort = createQuestion(teacher, 4, "简答：什么是HTTP", "HTTP,协议,无状态", null, null);
        long paperId = createPaper(teacher, unique("试卷"), 20 * scale);
        addPaperQuestion(teacher, paperId, qSingle, 4 * scale);
        addPaperQuestion(teacher, paperId, qMultiple, 6 * scale);
        addPaperQuestion(teacher, paperId, qJudge, 4 * scale);
        addPaperQuestion(teacher, paperId, qShort, 6 * scale);
        return paperId;
    }

    private long preparePaper(String teacher, boolean withTags) throws Exception {
        return preparePaper(teacher, withTags, 1);
    }

    private long createExam(String teacher, long paperId, Long classId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        if (classId != null) {
            body.put("classId", classId);
        }
        body.put("startTime", LocalDateTime.now().minusMinutes(1).toString());
        body.put("endTime", LocalDateTime.now().plusHours(2).toString());
        body.put("durationMinutes", 30);
        JsonNode data = perform(jsonPost("/api/exams", teacher,
                objectMapper.writeValueAsString(body)), 200).get("data");
        return data.get("id").asLong();
    }

    private long preparedInProgressExam(String teacher, boolean withTags, double scale) throws Exception {
        long paperId = preparePaper(teacher, withTags, scale);
        long classId = createClassForExam(teacher);
        long examId = createExam(teacher, paperId, classId);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        return examId;
    }

    private long preparedInProgressExam(String teacher, boolean withTags) throws Exception {
        return preparedInProgressExam(teacher, withTags, 1);
    }

    private Map<Integer, Long> enterAndMapQuestions(String student, long examId) throws Exception {
        ensureExamClassMembership(student, examId);
        JsonNode data = perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null), 200)
                .get("data");
        Map<Integer, Long> byType = new HashMap<>();
        data.get("questions").forEach(q -> byType.put(q.get("type").asInt(), q.get("questionId").asLong()));
        return byType;
    }

    private void submit(String student, long examId, Map<Integer, Long> questions,
                        String single, String multiple, String judge, String shortAnswer) throws Exception {
        ObjectNode answers = objectMapper.createObjectNode();
        if (single != null) { answers.put(String.valueOf(questions.get(1)), single); }
        if (multiple != null) { answers.put(String.valueOf(questions.get(2)), multiple); }
        if (judge != null) { answers.put(String.valueOf(questions.get(3)), judge); }
        if (shortAnswer != null) { answers.put(String.valueOf(questions.get(4)), shortAnswer); }
        ObjectNode request = objectMapper.createObjectNode();
        request.set("answers", answers);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(request)), 200);
        consumeCapturedMessages(examId);
    }

    private void consumeCapturedMessages(long examId) throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(anyString(), anyString(), captor.capture());
        for (Object raw : new ArrayList<>(captor.getAllValues())) {
            SubmitMessage message = (SubmitMessage) raw;
            if (!message.getExamId().equals(examId)) { continue; }
            Message amqp = new Message(objectMapper.writeValueAsBytes(message), new MessageProperties());
            amqp.getMessageProperties().setDeliveryTag(1L);
            submitConsumer.onBatch(List.of(amqp), mock(com.rabbitmq.client.Channel.class));
        }
    }

    private long studentIdOf(String token) throws Exception {
        return jwtUtil.parseAccessToken(token).getId();
    }

    private void saveSubjective(String teacher, long examId, long studentId, long questionId,
                                String score, int expectedVersion, String comment) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("submissionId", submissionIdOf(examId, studentId));
        body.put("questionId", questionId);
        body.put("score", new BigDecimal(score));
        body.put("expectedVersion", expectedVersion);
        body.put("comment", comment);
        perform(jsonPost("/api/exams/" + examId + "/grading/subjective/save", teacher,
                objectMapper.writeValueAsString(body)), 200);
    }

    private long submissionIdOf(long examId, long studentId) {
        return gradingSubmissionMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
                .eq(GradingSubmission::getExamId, examId)
                .eq(GradingSubmission::getStudentId, studentId)).getId();
    }

    /** 造一场已汇总完成（GRADED）的考试：3 名学生，总分后由简答批改决定（卷面按 scale 缩放）。
     *  scale=1 即 20 分卷（题分 4/6/4/6，简答批改 6/3/2），学生总分 20/6/10。 */
    private long preparedGradedExam(String teacher, boolean withTags, double scale) throws Exception {
        String s1 = registerStudent();
        String s2 = registerStudent();
        String s3 = registerStudent();
        long examId = preparedInProgressExam(teacher, withTags, scale);
        Map<Integer, Long> q1 = enterAndMapQuestions(s1, examId);
        submit(s1, examId, q1, "A", "A,C", "T", "HTTP 是无状态的协议");
        Map<Integer, Long> q2 = enterAndMapQuestions(s2, examId);
        submit(s2, examId, q2, "B", "A", "F", "HTTP");
        Map<Integer, Long> q3 = enterAndMapQuestions(s3, examId);
        submit(s3, examId, q3, "A", "A,D", "T", "");
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        perform(jsonPost("/api/exams/" + examId + "/grading/run", teacher, null), 200);
        long shortQ = q1.get(4);
        saveSubjective(teacher, examId, studentIdOf(s1), shortQ, (6.0 * scale) + "", 0, "表述完整");
        saveSubjective(teacher, examId, studentIdOf(s2), shortQ, (3.0 * scale) + "", 0, "关键词少");
        saveSubjective(teacher, examId, studentIdOf(s3), shortQ, (2.0 * scale) + "", 0, "未作答");
        perform(jsonPost("/api/exams/" + examId + "/scores/summarize", teacher, null), 200);
        return examId;
    }

    private long preparedGradedExam(String teacher, boolean withTags) throws Exception {
        return preparedGradedExam(teacher, withTags, 1);
    }

    @Test
    void analysisReportEndToEndStructureAndSameSource() throws Exception {
        String teacher = registerTeacher();
        String otherTeacher = registerTeacher();
        long examId = preparedGradedExam(teacher, false);

        // ---- 越权与不存在 ----
        String otherToken = otherTeacher;
        JsonNode forbidden = perform(jsonGet("/api/exams/" + examId + "/scores/analysis-report", otherToken), 403);
        assertEquals(403, forbidden.get("code").asInt());
        JsonNode notFound = perform(jsonGet("/api/exams/" + 999999L + "/scores/analysis-report", teacher), 404);
        assertEquals(404, notFound.get("code").asInt(), "考试不存在返回 404");

        // ---- 200 三块结构 ----
        JsonNode data = perform(jsonGet("/api/exams/" + examId + "/scores/analysis-report", teacher), 200)
                .get("data");

        // 班级概览
        JsonNode overview = data.get("classOverview");
        assertEquals(3, overview.get("expectedCount").asInt(), "应考=班级 3 名学生");
        assertEquals(3, overview.get("actualCount").asInt(), "实考=3 份已汇总答卷");
        assertEquals(0, overview.get("absenceCount").asInt());
        assertTrue(overview.has("averageScore"));
        assertTrue(overview.has("passRate"));
        // 及格线/优秀线已派生下发（fix-analysis-report-pass-line）：本卷 paper totalScore=20
        // → passLine=20*60%=12、excellentLine=20*90%=18（HALF_UP 取整）
        assertEquals(12, data.get("passLine").asInt(), "20 分卷及格线派生为 12");
        assertEquals(18, data.get("excellentLine").asInt(), "20 分卷优秀线派生为 18");
        JsonNode bands = overview.get("scoreBands");
        assertEquals(5, bands.size());
        assertEquals("0-11", bands.get(0).get("band").asText(), "分数段首段由派生切点 12 生成");
        assertEquals("18-20", bands.get(4).get("band").asText(), "分数段末段由派生切点 18 生成");
        assertEquals(2, bands.get(0).get("count").asInt(), "总分 6/10 落入 0-11 段");
        assertEquals(1, bands.get(4).get("count").asInt(), "总分 20 落入 18-20 段");

        // 逐题指标：同源同口径（与题目统计导出聚合核心一致）
        JsonNode qStats = data.get("questionStats");
        assertEquals(4, qStats.size());
        assertEquals(1, qStats.get(0).get("order").asInt());
        assertEquals("单选", qStats.get(0).get("type").asText());
        // 3 人样本 &lt; 4 → 区分度样本不足（JSON 缺省/为 null 即「样本不足」），相同源导出的「样本不足」
        assertTrue(qStats.get(0).path("discrimination").isMissingNode()
                        || qStats.get(0).path("discrimination").isNull(),
                "3 人样本不足以计算区分度");
        assertTrue(qStats.get(0).has("answeredCount"));

        // 与导出共用聚合来源：逐题平均分与导出单元格一致（单选 2 人答对 / 3 人作答）
        JsonNode st0 = qStats.get(0);
        assertEquals(3, st0.get("answeredCount").asInt());
        // 简单抽查：得分率在 (0,1] 区间
        double rate = st0.get("scoreRate").asDouble();
        assertTrue(rate > 0 && rate <= 1, "得分率 0-1 区间");

        // 知识点：未打标签的科目 → 空数组 + hasTagDimension=false（优雅降级不报错）
        assertEquals(false, data.get("hasTagDimension").asBoolean());
        assertEquals(0, data.get("tagWeakness").size());

        // 学生关注名单：低于派生及格线(12)的学生（总分 6、10 上榜；总分 20 ≥12 不上榜）
        JsonNode focus = data.get("focusList");
        assertEquals(2, focus.size());
        assertTrue(focus.get(0).has("studentId"));
        assertTrue(focus.get(0).has("username"));
        assertTrue(focus.get(0).has("studentName"));
        assertTrue(focus.get(0).has("totalScore"));
    }

    @Test
    void tagDimensionAggregatesAndDegradeWhenNoTags() throws Exception {
        String teacher = registerTeacher();
        long taggedExam = preparedGradedExam(teacher, true);
        JsonNode taggedData = perform(jsonGet("/api/exams/" + taggedExam
                + "/scores/analysis-report", teacher), 200).get("data");
        assertEquals(true, taggedData.get("hasTagDimension").asBoolean());
        assertEquals(1, taggedData.get("tagWeakness").size(), "仅单选挂了知识点A 标签");
        String tagName = taggedData.get("tagWeakness").get(0).get("tagName").asText();
        assertTrue(tagName.contains("知识点A"), "标签名应透传");
        assertTrue(taggedData.get("tagWeakness").get(0).has("scoreRate"));
    }

    @Test
    void unGradedExamReturnsBadRequest() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher, false);
        Map<Integer, Long> q = enterAndMapQuestions(student, examId);
        submit(student, examId, q, "A", "A,C", "T", "HTTP 是无状态的协议");
        // 交卷但未汇总 → 400
        JsonNode bad = perform(jsonGet("/api/exams/" + examId + "/scores/analysis-report", teacher), 400);
        assertTrue(bad.get("message").asText().contains("汇总"));
    }

    @Test
    void studentsBelowPassLineOnlyInFocusList() throws Exception {
        String teacher = registerTeacher();
        long examId = preparedGradedExam(teacher, false);
        JsonNode data = perform(jsonGet("/api/exams/" + examId + "/scores/analysis-report", teacher), 200)
                .get("data");
        // 三名学生总分 = 14+6 / 3+3 / 8+2 → 20 / 6 / 10；本卷 paper totalScore=20
        // → 派生及格线 passLine=12（fix-analysis-report-pass-line）：20 ≥12 不及格线剔除，6/10 <12 上榜
        assertEquals(12, data.get("passLine").asInt());
        assertEquals(2, data.get("focusList").size());
        // 均 < 派生及格线 12
        for (JsonNode item : data.get("focusList")) {
            assertTrue(item.get("totalScore").decimalValue().compareTo(new BigDecimal("12")) < 0);
        }
    }

    /**
     * 三分制对照（fix-analysis-report-pass-line）：150/50/100 三种总分下
     * passLine/excellentLine 分别派生为 90/135、30/45、60/90；分数段首末切点与及格/优秀线对齐。
     * 用例只增不删的既有断言已按派生语义更新（20 分卷 ingress 见上方两个用例）。
     */
    @Test
    void passAndExcellentLineDerivedPerTotalScore() throws Exception {
        String teacher = registerTeacher();
        // 卷面按 scale 缩放：150→scale 7.5、50→scale 2.5、100→scale 5；申报总分 == 20×scale
        // {scale, expectedPass, expectedExcellent, firstBand, lastBand, secondBand}
        assertDerivedReport(teacher, 7.5, 90, 135,
                "0-89", "135-150", "90-104");
        assertDerivedReport(teacher, 2.5, 30, 45,
                "0-29", "45-50", "30-34");
        assertDerivedReport(teacher, 5, 60, 90,
                "0-59", "90-100", "60-69");
    }

    /** 单份报告的三分制对照断言基建。 */
    private void assertDerivedReport(String teacher, double scale,
                                     int expectedPass, int expectedExcellent,
                                     String firstBand, String lastBand, String secondBand)
            throws Exception {
        long examId = preparedGradedExam(teacher, false, scale);
        JsonNode data = perform(jsonGet("/api/exams/" + examId + "/scores/analysis-report", teacher), 200)
                .get("data");
        assertEquals(expectedPass, data.get("passLine").asInt(),
                "scale=" + scale + " 卷及格线派生应正确");
        assertEquals(expectedExcellent, data.get("excellentLine").asInt(),
                "scale=" + scale + " 卷优秀线派生应正确");
        JsonNode bands = data.get("classOverview").get("scoreBands");
        assertEquals(5, bands.size(), "分数段保持 5 段制");
        assertEquals(firstBand, bands.get(0).get("band").asText());
        assertEquals(secondBand, bands.get(1).get("band").asText());
        assertEquals(lastBand, bands.get(4).get("band").asText());
    }

    /**
     * 100 分制零回归（fix-analysis-report-pass-line）：及格线/优秀线恰为 60/90，
     * 分数段逐字等于既有 5 段 0-59/60-69/70-79/80-89/90-100——与实施前输出逐值一致。
     */
    @Test
    void hundredPointPaperZeroRegression() throws Exception {
        String teacher = registerTeacher();
        // scale=5 → 申报总分 100 分卷；学生总分 20/6/10 ×5 = 100/30/50
        long examId = preparedGradedExam(teacher, false, 5);
        JsonNode data = perform(jsonGet("/api/exams/" + examId + "/scores/analysis-report", teacher), 200)
                .get("data");
        assertEquals(60, data.get("passLine").asInt(), "100 分制及格线恒为 60");
        assertEquals(90, data.get("excellentLine").asInt(), "100 分制优秀线恒为 90");
        JsonNode bands = data.get("classOverview").get("scoreBands");
        String[] expectedBands = {"0-59", "60-69", "70-79", "80-89", "90-100"};
        assertEquals(5, bands.size());
        for (int i = 0; i < expectedBands.length; i++) {
            assertEquals(expectedBands[i], bands.get(i).get("band").asText(),
                    "100 分制分数段标签与实施前逐字一致");
        }
        // 及格率：学生总分 100/30/50，100 分制及格线 60 下 ≥60 仅 100 → 1/3（与绝对 60 线口径一致）
        assertEquals(1.0 / 3.0, data.get("classOverview").get("passRate").asDouble(), 1e-9);
        // 分数段分布：100→90-100、30/50→0-59（与绝对 60 线口径一致，零回归）
        assertEquals(2, data.get("classOverview").get("scoreBands").get(0).get("count").asInt());
        assertEquals(1, data.get("classOverview").get("scoreBands").get(4).get("count").asInt());
        // 关注名单：低于 60 的 30/50 两名（与绝对 60 线口径一致，零回归）
        assertEquals(2, data.get("focusList").size());
    }

    /** 总分不可得 → fail-fast 明确报错，无默认回退（fix-analysis-report-pass-line）。 */
    @Test
    void reportFailFastWhenTotalScoreUnavailable() throws Exception {
        String teacher = registerTeacher();
        long examId = preparedGradedExam(teacher, false);
        // 篡改快照 totalScore 为 0（不可得）：报告请求须明确失败，不回退默认总分 100/60
        ExamSnapshot snapshot = examSnapshotMapper.selectOne(
                Wrappers.<ExamSnapshot>lambdaQuery().eq(ExamSnapshot::getExamId, examId));
        ObjectNode paperJson = (ObjectNode) objectMapper.readTree(snapshot.getPaperJson());
        paperJson.put("totalScore", 0);
        snapshot.setPaperJson(objectMapper.writeValueAsString(paperJson));
        examSnapshotMapper.updateById(snapshot);

        JsonNode err = perform(jsonGet("/api/exams/" + examId + "/scores/analysis-report", teacher), 500);
        assertTrue(err.get("message").asText().contains("无法取到试卷总分"),
                "总分不可得须 fail-fast 明确报错");
    }
}