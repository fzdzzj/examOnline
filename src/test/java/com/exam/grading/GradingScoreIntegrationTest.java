package com.exam.grading;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.service.JwtUtil;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.grading.dto.SubjectiveScoreRequest;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.service.SubjectiveGradingService;
import com.exam.score.entity.ScoreAuditLog;
import com.exam.score.mapper.ScoreAuditLogMapper;
import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mq.ExamSubmitConsumer;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.support.IntegrationTestBase;
import com.exam.support.RabbitTemplateInvokeStubs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 判分与成绩验收集成测试（spec add-grading-score 全场景，H2 + MockMvc，MQ 为 Mock）：
 * <ul>
 *   <li>端到端：交卷 → 客观自动判分（多选漏选部分分/错选0分）→ 简答初判建行 →
 *       教师逐题批改（留痕/打回重批）→ 汇总 → 预览排名 → 发布 → 学生查分 →
 *       管理员撤回（教师被拒）→ 审计 → 四种导出（Excel 解析 + PDF 魔数）；</li>
 *   <li>判分失败隔离：坏答案卷标记失败不影响他人，重判/手动给分可恢复；</li>
 *   <li>并发批改：两请求同批一题，乐观锁仅一个成功，另一个 409。</li>
 * </ul>
 */
class GradingScoreIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    /**
     * ExamSubmitSender 修复后「发送 + confirm 等待」整体移入 RabbitTemplate.invoke() 作用域；
     * mock 的 invoke 默认不执行 callback，会漏掉 callback 内的 3 参 convertAndSend 调用。
     * 本桩让 invoke 真实执行 callback，既有 verify 断言不变（fix-broker-confirm-and-dlq-roundtrip）。
     */
    @BeforeEach
    void runRabbitInvokeCallbacks() {
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private ExamSubmissionMapper submissionMapper;
    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Autowired
    private SubjectiveGradeMapper subjectiveGradeMapper;
    @Autowired
    private ScoreAuditLogMapper auditLogMapper;
    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private ExamSubmitConsumer submitConsumer;
    @Autowired
    private SubjectiveGradingService subjectiveGradingService;

    // ==================== 造数 ====================

    private long addPaperQuestion(String token, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
        return questionId;
    }

    /** 组卷：单选 4 + 多选 6 + 判断 4 + 简答 6 = 20 分（客观 14 / 主观 6）。 */
    private long preparePaper(String teacher) throws Exception {
        long qSingle = createQuestion(teacher, 1, "单选：1+1=?", "A", List.of("A", "B", "C", "D"), null);
        long qMultiple = createQuestion(teacher, 2, "多选：下列哪些是质数", "A,C", List.of("2", "4", "3", "9"), null);
        long qJudge = createQuestion(teacher, 3, "判断：水加热到100摄氏度会沸腾", "T", null, null);
        long qShort = createQuestion(teacher, 4, "简答：什么是HTTP", "HTTP,协议,无状态", null, null);
        long paperId = createPaper(teacher, unique("试卷"), 20);
        addPaperQuestion(teacher, paperId, qSingle, 4);
        addPaperQuestion(teacher, paperId, qMultiple, 6);
        addPaperQuestion(teacher, paperId, qJudge, 4);
        addPaperQuestion(teacher, paperId, qShort, 6);
        return paperId;
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

    /** 造一场"进行中"考试（开始时间拨到过去 → 发布 → 状态机推进）。
     *  绑定测试班级以满足进入考试的班级准入（学生入班由 enter 夹具按需补入）。 */
    private long preparedInProgressExam(String teacher) throws Exception {
        long paperId = preparePaper(teacher);
        long classId = createClassForExam(teacher);
        long examId = createExam(teacher, paperId, classId);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        return examId;
    }

    /** 学生进入考试并按题型定位题目 ID（个人快照题序随机，须按类型识别）。 */
    private Map<Integer, Long> enterAndMapQuestions(String student, long examId) throws Exception {
        // 既有用例夹具适配：进入成功路径先确保学生已入班（不改变任何断言语义）
        ensureExamClassMembership(student, examId);
        JsonNode data = perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null), 200)
                .get("data");
        Map<Integer, Long> byType = new HashMap<>();
        data.get("questions").forEach(q -> byType.put(q.get("type").asInt(), q.get("questionId").asLong()));
        return byType;
    }

    /** 学生提交指定答案并驱动真实消费者落库（MQ 为 Mock，交卷消息被捕获后手动消费）。 */
    private void submit(String student, long examId, Map<Integer, Long> questions,
                        String single, String multiple, String judge, String shortAnswer) throws Exception {
        ObjectNode answers = objectMapper.createObjectNode();
        if (single != null) {
            answers.put(String.valueOf(questions.get(1)), single);
        }
        if (multiple != null) {
            answers.put(String.valueOf(questions.get(2)), multiple);
        }
        if (judge != null) {
            answers.put(String.valueOf(questions.get(3)), judge);
        }
        if (shortAnswer != null) {
            answers.put(String.valueOf(questions.get(4)), shortAnswer);
        }
        ObjectNode request = objectMapper.createObjectNode();
        request.set("answers", answers);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(request)), 200);
        consumeCapturedMessages(examId);
    }

    /** 取 Mock 收到的交卷消息，驱动真实消费者批量落库（消费幂等，重复驱动不重复写）。 */
    private void consumeCapturedMessages(long examId) throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(anyString(), anyString(), captor.capture());
        for (Object raw : new ArrayList<>(captor.getAllValues())) {
            SubmitMessage message = (SubmitMessage) raw;
            if (!message.getExamId().equals(examId)) {
                continue;
            }
            Message amqp = new Message(objectMapper.writeValueAsBytes(message), new MessageProperties());
            amqp.getMessageProperties().setDeliveryTag(1L);
            submitConsumer.onBatch(List.of(amqp), mock(com.rabbitmq.client.Channel.class));
        }
    }

    private long studentIdOf(String token) {
        return jwtUtil.parseAccessToken(token).getId();
    }

    private String adminToken() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", "admin");
        body.put("password", "admin123");
        return perform(jsonPost("/api/auth/login", null, objectMapper.writeValueAsString(body)), 200)
                .get("data").get("accessToken").asText();
    }

    // ==================== 端到端 ====================

    @Test
    void endToEndGradingScoreFlow() throws Exception {
        String teacher = registerTeacher();
        String student1 = registerStudent();
        String student2 = registerStudent();
        String student3 = registerStudent();
        long examId = preparedInProgressExam(teacher);

        // ---- 三名学生交卷：预期客观分 14 / 3（漏选部分分）/ 8（错选0分） ----
        Map<Integer, Long> q1 = enterAndMapQuestions(student1, examId);
        submit(student1, examId, q1, "A", "A,C", "T", "HTTP 是无状态的协议");
        Map<Integer, Long> q2 = enterAndMapQuestions(student2, examId);
        submit(student2, examId, q2, "B", "A", "F", "HTTP");
        Map<Integer, Long> q3 = enterAndMapQuestions(student3, examId);
        submit(student3, examId, q3, "A", "A,D", "T", "");

        // ---- 教师提前结束 → 整场判分 ----
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        JsonNode run = perform(jsonPost("/api/exams/" + examId + "/grading/run", teacher, null), 200)
                .get("data");
        assertEquals(3, run.get("total").asInt());
        assertEquals(3, run.get("success").asInt());
        assertEquals(0, run.get("failed").asInt());

        // 客观分核对：全对 14；漏选 6×1×(1/2)=3；错选 4+0+4=8
        assertEquals(0, objectiveOf(examId, studentIdOf(student1)).compareTo(new BigDecimal("14")));
        assertEquals(0, objectiveOf(examId, studentIdOf(student2)).compareTo(new BigDecimal("3")));
        assertEquals(0, objectiveOf(examId, studentIdOf(student3)).compareTo(new BigDecimal("8")));

        // 简答初判建行（提示分不定分）：stu1 全命中 6.0；stu2 命中 1/3 → 2.0
        Long shortQuestionId = q1.get(4);
        SubjectiveGrade suggestion1 = subjectiveGradeMapper.selectOne(
                Wrappers.<SubjectiveGrade>lambdaQuery()
                        .eq(SubjectiveGrade::getExamId, examId)
                        .eq(SubjectiveGrade::getStudentId, studentIdOf(student1))
                        .eq(SubjectiveGrade::getQuestionId, shortQuestionId));
        assertNotNull(suggestion1);
        assertEquals(0, suggestion1.getSuggestedScore().compareTo(new BigDecimal("6.0")));
        assertEquals(null, suggestion1.getScore());
        SubjectiveGrade suggestion2 = subjectiveGradeMapper.selectOne(
                Wrappers.<SubjectiveGrade>lambdaQuery()
                        .eq(SubjectiveGrade::getExamId, examId)
                        .eq(SubjectiveGrade::getStudentId, studentIdOf(student2)));
        assertEquals(0, suggestion2.getSuggestedScore().compareTo(new BigDecimal("2.0")));

        // ---- 批改工作台：同题列出 3 名学生，逐题打分留痕 + 打回重批 ----
        JsonNode questions = perform(jsonGet("/api/exams/" + examId + "/grading/subjective/questions", teacher), 200)
                .get("data");
        assertEquals(1, questions.size());
        assertEquals(3, questions.get(0).get("totalStudents").asInt());
        assertEquals(0, questions.get(0).get("gradedStudents").asInt());

        JsonNode rows = perform(jsonGet("/api/exams/" + examId + "/grading/subjective?questionId="
                + shortQuestionId, teacher), 200).get("data");
        assertEquals(3, rows.size());

        saveSubjective(teacher, examId, studentIdOf(student1), shortQuestionId, "6.0", 0, "表述完整");
        saveSubjective(teacher, examId, studentIdOf(student2), shortQuestionId, "3.0", 0, "关键词命中少");
        saveSubjective(teacher, examId, studentIdOf(student3), shortQuestionId, "0.0", 0, "未作答");
        saveSubjective(teacher, examId, studentIdOf(student3), shortQuestionId, "2.0", 1, "复核补分");

        // ---- 汇总：总分 = 客观 + 主观 ----
        perform(jsonPost("/api/exams/" + examId + "/scores/summarize", teacher, null), 200);
        assertEquals(Exam.STATUS_GRADED, examMapper.selectById(examId).getStatus());
        assertEquals(0, totalOf(examId, studentIdOf(student1)).compareTo(new BigDecimal("20")));   // 14+6
        assertEquals(0, totalOf(examId, studentIdOf(student2)).compareTo(new BigDecimal("6")));    // 3+3
        assertEquals(0, totalOf(examId, studentIdOf(student3)).compareTo(new BigDecimal("10")));   // 8+2

        // ---- 发布前预览：排名 20 > 10 > 6 → 1,2,3 ----
        JsonNode preview = perform(jsonGet("/api/exams/" + examId + "/scores/publish-preview", teacher), 200)
                .get("data");
        assertEquals(3, preview.get("summarizedCount").asInt());
        assertEquals(1, findByStudent(preview, studentIdOf(student1)).get("rank").asInt());
        assertEquals(2, findByStudent(preview, studentIdOf(student3)).get("rank").asInt());
        assertEquals(3, findByStudent(preview, studentIdOf(student2)).get("rank").asInt());

        // ---- 发布 → 学生可见；教师撤回被拒；管理员撤回成功 ----
        ObjectNode publishBody = objectMapper.createObjectNode();
        publishBody.putArray("examIds").add(examId);
        perform(jsonPost("/api/scores/publish", teacher, objectMapper.writeValueAsString(publishBody)), 200);
        assertEquals(Exam.STATUS_PUBLISHED, examMapper.selectById(examId).getStatus());

        JsonNode myScore = perform(jsonGet("/api/scores/my?examId=" + examId, student1), 200).get("data");
        assertEquals(0, myScore.get("totalScore").decimalValue().compareTo(new BigDecimal("20")));
        assertEquals(1, myScore.get("rank").asInt());

        // 教师撤回：仅管理员（§5.3），注解层直接 403
        JsonNode teacherRevoke = perform(jsonPost("/api/scores/revoke", teacher,
                objectMapper.writeValueAsString(publishBody)), 403);
        assertEquals(403, teacherRevoke.get("code").asInt());

        ObjectNode revokeBody = objectMapper.createObjectNode();
        revokeBody.putArray("examIds").add(examId);
        revokeBody.put("reason", "客观题答案有误，需修正后重判");
        perform(jsonPost("/api/scores/revoke", adminToken(), objectMapper.writeValueAsString(revokeBody)), 200);
        assertEquals(Exam.STATUS_GRADED, examMapper.selectById(examId).getStatus());

        JsonNode hidden = perform(jsonGet("/api/scores/my?examId=" + examId, student1), 400);
        assertEquals("成绩待发布", hidden.get("message").asText());

        // 审计留痕：PUBLISH + REVOKE（含原因与操作人）
        List<ScoreAuditLog> audits = auditLogMapper.selectList(
                Wrappers.<ScoreAuditLog>lambdaQuery().eq(ScoreAuditLog::getExamId, examId));
        assertEquals(2, audits.size());
        ScoreAuditLog revoke = audits.stream()
                .filter(a -> ScoreAuditLog.ACTION_REVOKE.equals(a.getAction())).findFirst().orElseThrow();
        assertEquals("客观题答案有误，需修正后重判", revoke.getReason());
        assertEquals(studentIdOf(adminToken()), revoke.getOperatorId());

        // ---- 四种导出：Excel 可解析 + PDF 魔数 ----
        byte[] classSheet = exportGet(teacher, "/api/exams/" + examId + "/scores/export/class-sheet", 200);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(classSheet))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("学号", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(3, sheet.getLastRowNum(), "表头 + 3 名学生");
            assertEquals("20.0", sheet.getRow(1).getCell(4).getStringCellValue());
        }

        byte[] detail = exportGet(teacher, "/api/exams/" + examId + "/scores/export/detail", 200);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(detail))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals(3, sheet.getLastRowNum());
            assertEquals("6.0", sheet.getRow(1).getCell(5).getStringCellValue(), "简答批改终分应出现在明细");
        }

        byte[] stats = exportGet(teacher, "/api/exams/" + examId + "/scores/export/question-stats", 200);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(stats))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals(4, sheet.getLastRowNum(), "表头 + 4 题");
            assertEquals("66.7%", sheet.getRow(1).getCell(6).getStringCellValue(), "单选 2/3 答对（stu2 答错）");
            assertEquals("样本不足", sheet.getRow(2).getCell(7).getStringCellValue(), "3 人不足以计算区分度");
        }

        byte[] personal = exportGet(teacher, "/api/exams/" + examId
                + "/scores/export/personal/" + studentIdOf(student1) + "?format=xlsx", 200);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(personal))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertTrue(sheet.getRow(1).getCell(1).getStringCellValue().contains("客观题：14.0"),
                    "个人信息行应含客观/主观/总分");
        }

        byte[] pdf = exportGet(teacher, "/api/exams/" + examId
                + "/scores/export/personal/" + studentIdOf(student1) + "?format=pdf", 200);
        assertTrue(pdf.length > 0 && new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)
                .equals("%PDF"), "PDF 魔数");

        // 学生无导出权限（exam:manage 缺失）
        exportGet(student1, "/api/exams/" + examId + "/scores/export/class-sheet", 403);
    }

    // ==================== 判分失败隔离与恢复 ====================

    @Test
    void gradingFailureIsolationRejudgeAndManualScore() throws Exception {
        String teacher = registerTeacher();
        String healthy = registerStudent();
        String broken = registerStudent();
        long examId = preparedInProgressExam(teacher);

        Map<Integer, Long> qHealthy = enterAndMapQuestions(healthy, examId);
        submit(healthy, examId, qHealthy, "A", "A,C", "T", "HTTP 协议无状态");
        Map<Integer, Long> qBroken = enterAndMapQuestions(broken, examId);
        submit(broken, examId, qBroken, "A", "A,C", "T", "HTTP 协议无状态");

        // 注入脏数据：一份答卷答案 JSON 损坏（模拟落库数据损坏，spec §9.8 判分失败场景）
        GradingSubmission brokenRow = gradingSubmissionMapper.selectOne(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStudentId, studentIdOf(broken)));
        submissionMapper.update(null, Wrappers.<ExamSubmission>lambdaUpdate()
                .eq(ExamSubmission::getId, brokenRow.getId())
                .set(ExamSubmission::getAnswers, "{broken-json"));

        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        JsonNode run = perform(jsonPost("/api/exams/" + examId + "/grading/run", teacher, null), 200)
                .get("data");

        // 失败隔离：坏卷标记失败，好卷照常判分
        assertEquals(2, run.get("total").asInt());
        assertEquals(1, run.get("success").asInt());
        assertEquals(1, run.get("failed").asInt());
        assertEquals(1, run.get("failures").size());
        assertEquals(brokenRow.getId(), run.get("failures").get(0).get("submissionId").asLong());

        GradingSubmission healthyRow = gradingSubmissionMapper.selectOne(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStudentId, studentIdOf(healthy)));
        assertEquals(1, healthyRow.getGradingStatus());
        assertEquals(0, healthyRow.getObjectiveScore().compareTo(new BigDecimal("14")));

        GradingSubmission failedRow = gradingSubmissionMapper.selectById(brokenRow.getId());
        assertEquals(2, failedRow.getGradingStatus());
        assertNotNull(failedRow.getGradingError());

        // 重判坏卷：仍失败（JSON 坏），接口 200 并带回失败原因
        JsonNode rejudge = perform(jsonPost("/api/exams/" + examId + "/grading/submissions/"
                + brokenRow.getId() + "/rejudge", teacher, null), 200).get("data");
        assertTrue(!rejudge.get("error").asText().isEmpty());

        // 手动给分兜底：教师裁定客观分，状态恢复成功
        ObjectNode manual = objectMapper.createObjectNode();
        manual.put("objectiveScore", 10);
        perform(jsonPost("/api/exams/" + examId + "/grading/submissions/"
                + brokenRow.getId() + "/manual-score", teacher, objectMapper.writeValueAsString(manual)), 200);
        GradingSubmission recovered = gradingSubmissionMapper.selectById(brokenRow.getId());
        assertEquals(1, recovered.getGradingStatus());
        assertEquals(0, recovered.getObjectiveScore().compareTo(new BigDecimal("10.0")));
        assertEquals(null, recovered.getGradingError());

        // 手动给分超满分被拒（客观满分 14）
        ObjectNode tooMuch = objectMapper.createObjectNode();
        tooMuch.put("objectiveScore", 15);
        perform(jsonPost("/api/exams/" + examId + "/grading/submissions/"
                + brokenRow.getId() + "/manual-score", teacher, objectMapper.writeValueAsString(tooMuch)), 400);
    }

    // ==================== 并发批改乐观锁 ====================

    @Test
    void concurrentSubjectiveGradingOptimisticLock() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher);

        Map<Integer, Long> questions = enterAndMapQuestions(student, examId);
        submit(student, examId, questions, "A", "A,C", "T", "HTTP 是无状态的协议");
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        perform(jsonPost("/api/exams/" + examId + "/grading/run", teacher, null), 200);

        SubjectiveGrade row = subjectiveGradeMapper.selectOne(
                Wrappers.<SubjectiveGrade>lambdaQuery()
                        .eq(SubjectiveGrade::getExamId, examId)
                        .eq(SubjectiveGrade::getStudentId, studentIdOf(student)));
        assertEquals(0, row.getVersion());

        // 两路并发批改同一答卷同题（同教师两线程模拟）：
        // 均持 version=0 提交，乐观锁 CAS 裁决仅一个成功，另一个 409 冲突
        LoginUser teacherUser = jwtUtil.parseAccessToken(teacher);
        long questionId = row.getQuestionId();
        long submissionId = row.getSubmissionId();
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Integer> task = () -> {
            SecurityUtil.set(teacherUser);
            ready.countDown();
            go.await();
            try {
                SubjectiveScoreRequest request = new SubjectiveScoreRequest();
                request.setSubmissionId(submissionId);
                request.setQuestionId(questionId);
                request.setScore(new BigDecimal("5.0"));
                request.setComment("并发批改-" + Thread.currentThread().getName());
                request.setExpectedVersion(0);
                subjectiveGradingService.saveScore(examId, request);
                return 200;
            } catch (BusinessException e) {
                return e.getCode() == ResponseCode.STATE_CONFLICT.getCode() ? 409 : 500;
            } finally {
                SecurityUtil.clear();
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(task));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            int ok = 0;
            int conflict = 0;
            for (Future<Integer> future : futures) {
                int status = future.get(10, TimeUnit.SECONDS);
                if (status == 200) {
                    ok++;
                } else if (status == 409) {
                    conflict++;
                }
            }
            assertEquals(1, ok, "并发批改仅一个成功");
            assertEquals(1, conflict, "另一个收到冲突");

            // 版本号推进到 1，留痕（批改人/批改时间）
            SubjectiveGrade after = subjectiveGradeMapper.selectById(row.getId());
            assertEquals(1, after.getVersion());
            assertNotNull(after.getGraderId());
            assertNotNull(after.getGradedTime());
            assertEquals(0, after.getScore().compareTo(new BigDecimal("5.0")));
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 辅助 ====================

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

    private BigDecimal objectiveOf(long examId, long studentId) {
        return gradingSubmissionMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
                .eq(GradingSubmission::getExamId, examId)
                .eq(GradingSubmission::getStudentId, studentId)).getObjectiveScore();
    }

    private BigDecimal totalOf(long examId, long studentId) {
        return gradingSubmissionMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
                .eq(GradingSubmission::getExamId, examId)
                .eq(GradingSubmission::getStudentId, studentId)).getTotalScore();
    }

    private JsonNode findByStudent(JsonNode preview, long studentId) {
        for (JsonNode item : preview.get("items")) {
            if (item.get("studentId").asLong() == studentId) {
                return item;
            }
        }
        throw new AssertionError("预览缺少学生 " + studentId);
    }

    /** 导出类接口：断言 HTTP 状态并返回字节体。 */
    private byte[] exportGet(String token, String url, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(url).header("Authorization", bearer(token)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().is(expectedStatus))
                .andReturn();
        return result.getResponse().getContentAsByteArray();
    }
}
