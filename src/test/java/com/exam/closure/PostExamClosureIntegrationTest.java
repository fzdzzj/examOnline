package com.exam.closure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.service.JwtUtil;
import com.exam.exam.dto.MakeupCandidateItem;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamAbsence;
import com.exam.exam.mapper.ExamAbsenceMapper;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.AbsenceService;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.score.entity.ScoreReview;
import com.exam.score.mapper.ScoreReviewMapper;
import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.submission.mq.ExamSubmitConsumer;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 考后闭环端到端验收（spec add-post-exam-closure-e2e，阶段 12）：
 *
 * <p>主用例 {@link #fullClosureChainEndToEnd} 走一条真实链路：
 * 建班 → 入班 → 建卷 → 建考试（classId 必填）→ 发布 → 自然到点结束 → 缺考标记 → 筛补考名单 →
 * 建补考（独立记录）→ 名单限制进入 → 批改 → 汇总 → 发布 → 复核申请（隐藏）→ 复核处理（调分 + 解除隐藏）。
 *
 * <p>边界用例每条对应一个真实不变量：
 * <ul>
 *   <li>{@link #forceEndAlsoMarksAbsence}——教师提前结束路径同样标记缺考（缺陷一回归护栏，修复前必红）；</li>
 *   <li>{@link #markAbsenceIsIdempotent}——重复触发结束/重复标记行数不变（INSERT IGNORE + 唯一索引真跑）；</li>
 *   <li>{@link #examWithoutClassSkipsAbsence}——未绑班级的考试不标记缺考；</li>
 *   <li>{@link #makeupRequiresEndedMainExam} / {@link #makeupCannotChain}——主考未结束不能开补考、补考不能再开补考；</li>
 *   <li>{@link #reviewOncePerExamAndOwnerOnly} / {@link #reviewAlreadyHandledRejected}——复核限 1 次、仅归属教师处理、已处理不可再处理。</li>
 * </ul>
 *
 * <p>手法与既有 ExamTakingIntegrationTest 一致：RabbitTemplate 以 MockitoBean 替身，
 * 交卷消息用 ArgumentCaptor 取出后手工驱动 submitConsumer.onBatch(...) 真落库；状态机用
 * stateMachineService.autoAdvance() 手工驱动。全类不自行建表——建表一律以 schema.sql 为准
 * （application-test.yml 已配 mode: always + continue-on-error: false 建全 24 张表）。
 *
 * <p>复核申请用「有已发布成绩的学生 A」而非缺考学生 B：缺考者无 GradingSubmission，
 * "成绩被更新 / 隐藏解除"的断言对 B 不成立（myScore 对无成绩者返回 400），故用 A 承载复核链路。
 */
class PostExamClosureIntegrationTest extends IntegrationTestBase {

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
    private AbsenceService absenceService;
    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private ExamAbsenceMapper absenceMapper;
    @Autowired
    private ExamSubmissionMapper submissionMapper;
    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Autowired
    private ScoreReviewMapper reviewMapper;
    @Autowired
    private ExamSubmitConsumer submitConsumer;
    @Autowired
    private JwtUtil jwtUtil;

    // ==================== 主链路 ====================

    /** 建班入班 → 建卷建考 → 结束 → 缺考 → 补考 → 名单限制 → 批改汇总发布 → 复核申请与处理。 */
    @Test
    void fullClosureChainEndToEnd() throws Exception {
        String teacher = registerTeacher();
        String studentA = registerStudent();
        String studentB = registerStudent();
        long aId = studentIdOf(studentA);
        long bId = studentIdOf(studentB);

        // 1. 建班 → 两名学生入班（user_class，应考名单来源）
        long classId = createClassWithStudents(teacher, aId, bId);

        // 2. 建题 → 建卷 → 绑题
        long paperId = preparePaper(teacher);

        // 3. 建考试（classId 必填——markAbsence 靠它推导应考名单）→ 发布 → 推进到进行中
        long examId = createExam(teacher, paperId, classId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        assertEquals(Exam.STATUS_IN_PROGRESS, examMapper.selectById(examId).getStatus());

        // 4. 学生 A enter + submit + 驱动消费者落库（A 有答卷）；B 从不进入（无答卷 = 缺考）
        JsonNode enterData = enter(studentA, examId, 200);
        long q1 = questionIds(enterData).get(0);
        submitAndConsume(studentA, examId, q1, "A");
        assertEquals(ExamSubmission.STATUS_SUBMITTED,
                submissionMapper.selectByExamStudent(examId, aId).getStatus());
        assertTrue(submissionMapper.selectByExamStudent(examId, aId).getAnswers().contains(String.valueOf(q1)),
                "答案应已由消费者真落库");
        assertNull(submissionMapper.selectByExamStudent(examId, bId), "B 从未进入，不应有答卷行");

        // 5. endTime 拨到过去 → autoAdvance → 已结束 + 缺考标记
        moveEndTimePast(examId);
        stateMachineService.autoAdvance();
        assertEquals(Exam.STATUS_ENDED, examMapper.selectById(examId).getStatus());

        // 6. 缺考名单恰好只有 B
        JsonNode absences = perform(jsonGet("/api/exams/" + examId + "/absences", teacher), 200).get("data");
        assertEquals(1, absences.size());
        assertEquals(bId, absences.get(0).get("studentId").asLong());

        // 7. 可补考名单只有 B 且 reason=ABSENT
        JsonNode eligible = perform(jsonGet("/api/exams/" + examId + "/makeup-eligible", teacher), 200).get("data");
        assertEquals(1, eligible.size());
        assertEquals(bId, eligible.get(0).get("studentId").asLong());
        assertEquals(MakeupCandidateItem.REASON_ABSENT, eligible.get(0).get("reason").asText());

        // 8. 创建补考（名单 [B]）→ 独立记录：parentExamId 指向主考、时间窗/时长来自请求
        // H2 DATETIME 只存微秒（6 位小数），请求带纳秒（9 位），比对前截断到微秒
        LocalDateTime mkStart = LocalDateTime.now().minusMinutes(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        LocalDateTime mkEnd = LocalDateTime.now().plusHours(2).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        JsonNode makeup = perform(jsonPost("/api/exams/" + examId + "/makeups", teacher,
                objectMapper.writeValueAsString(makeupBody(mkStart, mkEnd, 20, bId))), 200).get("data");
        long makeupId = makeup.get("examId").asLong();
        assertTrue(makeupId != examId, "补考必须是独立考试记录");
        assertEquals(examId, makeup.get("parentExamId").asLong());
        assertEquals(1, makeup.get("candidateCount").asInt());
        Exam makeupExam = examMapper.selectById(makeupId);
        assertEquals(mkStart, makeupExam.getStartTime());
        assertEquals(mkEnd, makeupExam.getEndTime());
        assertEquals(20, makeupExam.getDurationMinutes());
        assertEquals(examId, makeupExam.getParentExamId());

        // 9. 补考发布 + autoAdvance → 名单限制：B 可进入，A 被拒 403
        perform(jsonPost("/api/exams/" + makeupId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        enter(studentB, makeupId, 200);
        perform(jsonPost("/api/exam-taking/exams/" + makeupId + "/enter", studentA, null), 403);

        // 10. 主考批改 → 汇总（已结束→已批改）→ 发布（已批改→已发布 + PUBLISH 审计）
        JsonNode run = perform(jsonPost("/api/exams/" + examId + "/grading/run", teacher, null), 200).get("data");
        assertEquals(1, run.get("total").asInt());
        assertEquals(1, run.get("success").asInt());
        perform(jsonPost("/api/exams/" + examId + "/scores/summarize", teacher, null), 200);
        assertEquals(Exam.STATUS_GRADED, examMapper.selectById(examId).getStatus());
        ObjectNode publishBody = objectMapper.createObjectNode();
        publishBody.putArray("examIds").add(examId);
        perform(jsonPost("/api/scores/publish", teacher, objectMapper.writeValueAsString(publishBody)), 200);
        assertEquals(Exam.STATUS_PUBLISHED, examMapper.selectById(examId).getStatus());

        // 11. A 申请复核 → 复核中成绩隐藏（reviewing=true、不返回分数）
        JsonNode review = perform(jsonPost("/api/exams/" + examId + "/score-reviews", studentA,
                objectMapper.writeValueAsString(reviewBody("对客观题得分有疑问"))), 200).get("data");
        long reviewId = review.get("id").asLong();
        assertEquals(ScoreReview.STATUS_PENDING, review.get("status").asInt());
        JsonNode hidden = perform(jsonGet("/api/scores/my?examId=" + examId, studentA), 200).get("data");
        assertTrue(hidden.get("reviewing").asBoolean());
        assertNull(hidden.get("totalScore"), "复核中不返回分数");

        // 12. 教师处理 AGREE + 调分 → 成绩被更新、复核状态 AGREED、隐藏解除
        perform(jsonPost("/api/score-reviews/" + reviewId + "/handle", teacher,
                objectMapper.writeValueAsString(handleBody("AGREE", 16, "复核确认给分有误，补 1 分"))), 200);
        ScoreReview after = reviewMapper.selectById(reviewId);
        assertEquals(ScoreReview.STATUS_AGREED, after.getStatus());
        GradingSubmission graded = gradingSubmissionMapper.selectOne(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStudentId, aId));
        assertEquals(0, graded.getTotalScore().compareTo(new BigDecimal("16")), "调分后成绩应被更新");
        JsonNode shown = perform(jsonGet("/api/scores/my?examId=" + examId, studentA), 200).get("data");
        assertEquals(false, shown.get("reviewing").asBoolean());
        assertEquals(0, shown.get("totalScore").decimalValue().compareTo(new BigDecimal("16")), "隐藏解除后显示新成绩");
    }

    // ==================== 边界：缺考标记 ====================

    /** 缺陷一回归护栏：教师提前结束路径也必须标记缺考（修复前必红）。 */
    @Test
    void forceEndAlsoMarksAbsence() throws Exception {
        String teacher = registerTeacher();
        String studentA = registerStudent();
        String studentB = registerStudent();
        long aId = studentIdOf(studentA);
        long bId = studentIdOf(studentB);
        long classId = createClassWithStudents(teacher, aId, bId);
        long examId = inProgressExam(teacher, classId);
        enter(studentA, examId, 200);   // A 有答卷；B 从不进入

        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        assertEquals(Exam.STATUS_ENDED, examMapper.selectById(examId).getStatus());

        JsonNode absences = perform(jsonGet("/api/exams/" + examId + "/absences", teacher), 200).get("data");
        assertEquals(1, absences.size(), "提前结束同样必须标记缺考");
        assertEquals(bId, absences.get(0).get("studentId").asLong());
    }

    /** 同一场考试重复触发结束/重复标记，exam_absence 行数不变（INSERT IGNORE + 唯一索引在 H2 上的首次真跑）。 */
    @Test
    void markAbsenceIsIdempotent() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassWithStudents(teacher, studentIdOf(student));
        long examId = inProgressExam(teacher, classId);   // 学生从不进入 → 唯一缺考候选

        moveEndTimePast(examId);
        stateMachineService.autoAdvance();
        assertEquals(1, absenceCount(examId));

        // 重复扫表（已结束不再被扫到，无操作）+ 直接再调 markAbsence：INSERT IGNORE 跳过已存在行
        stateMachineService.autoAdvance();
        assertEquals(0, absenceService.markAbsence(examId));
        assertEquals(1, absenceCount(examId), "重复标记不得新增行");
    }

    /** class_id 为空的考试不标记缺考（无应考名单可言）。 */
    @Test
    void examWithoutClassSkipsAbsence() throws Exception {
        String teacher = registerTeacher();
        registerStudent();   // 存在学生但不属于任何班级
        long examId = inProgressExam(teacher, null);

        moveEndTimePast(examId);
        stateMachineService.autoAdvance();
        assertEquals(Exam.STATUS_ENDED, examMapper.selectById(examId).getStatus());
        assertEquals(0, absenceCount(examId), "未绑班级的考试不产生缺考行");
    }

    // ==================== 边界：补考组织 ====================

    /** 主考未结束时创建补考 → 400。 */
    @Test
    void makeupRequiresEndedMainExam() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = inProgressExam(teacher, null);

        JsonNode resp = perform(jsonPost("/api/exams/" + examId + "/makeups", teacher,
                objectMapper.writeValueAsString(makeupBody(
                        LocalDateTime.now().plusMinutes(1), LocalDateTime.now().plusHours(2), 20,
                        studentIdOf(student)))), 400);
        assertEquals(400, resp.get("code").asInt());
        assertTrue(resp.get("message").asText().contains("尚未结束"));
    }

    /** 不能以补考再开补考 → 400。 */
    @Test
    void makeupCannotChain() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long studentId = studentIdOf(student);
        long mainExamId = inProgressExam(teacher, null);
        moveEndTimePast(mainExamId);
        stateMachineService.autoAdvance();
        long makeupId = createMakeup(teacher, mainExamId, studentId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 20);
        // 补考自身也要"已结束"才轮到 parentExamId 校验（否则先被"主考尚未结束"拦截，测不到链式场景）
        perform(jsonPost("/api/exams/" + makeupId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        moveEndTimePast(makeupId);
        stateMachineService.autoAdvance();
        assertEquals(Exam.STATUS_ENDED, examMapper.selectById(makeupId).getStatus());

        JsonNode resp = perform(jsonPost("/api/exams/" + makeupId + "/makeups", teacher,
                objectMapper.writeValueAsString(makeupBody(
                        LocalDateTime.now().plusMinutes(1), LocalDateTime.now().plusHours(2), 20, studentId))), 400);
        assertEquals(400, resp.get("code").asInt());
        assertTrue(resp.get("message").asText().contains("补考再开补考"));
    }

    // ==================== 边界：成绩复核 ====================

    /** 复核限 1 次（重复申请 → 1001）；非归属教师处理 → 403。 */
    @Test
    void reviewOncePerExamAndOwnerOnly() throws Exception {
        String teacher = registerTeacher();
        String otherTeacher = registerTeacher();
        String student = registerStudent();
        long examId = publishedExamWithScore(teacher, student);

        long reviewId = applyReview(examId, student, "分数有疑义");

        // 重复申请 → DATA_ALREADY_EXISTS 1001
        JsonNode dup = perform(jsonPost("/api/exams/" + examId + "/score-reviews", student,
                objectMapper.writeValueAsString(reviewBody("再次申请"))), 400);
        assertEquals(1001, dup.get("code").asInt());

        // 非归属教师处理 → 403
        perform(jsonPost("/api/score-reviews/" + reviewId + "/handle", otherTeacher,
                objectMapper.writeValueAsString(handleBody("AGREE", null, "越权处理"))), 403);
    }

    /** 已处理的复核再次处理 → 400。 */
    @Test
    void reviewAlreadyHandledRejected() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = publishedExamWithScore(teacher, student);
        long reviewId = applyReview(examId, student, "分数有疑义");

        perform(jsonPost("/api/score-reviews/" + reviewId + "/handle", teacher,
                objectMapper.writeValueAsString(handleBody("AGREE", 16, "复核确认"))), 200);
        assertEquals(ScoreReview.STATUS_AGREED, reviewMapper.selectById(reviewId).getStatus());

        JsonNode again = perform(jsonPost("/api/score-reviews/" + reviewId + "/handle", teacher,
                objectMapper.writeValueAsString(handleBody("AGREE", 17, "再次处理"))), 400);
        assertEquals(400, again.get("code").asInt());
        assertTrue(again.get("message").asText().contains("已处理"));
    }

    /**
     * 教师复核清单（按考试查）——本用例是这条 GET 的<b>首个执行者</b>。
     *
     * <p>为什么值得单独一条：该端点的路径模板是 {@code /api/exams/{examId}/score-reviews}，
     * 但参数写成了 {@code @RequestParam Long examId}（与同路径的申请端点用 @PathVariable 不一致），
     * 于是按 REST 语义调用（仅路径、不带 query）必然 400。它长期未被发现，正是因为闭环四端点
     * 零集成覆盖——"有实现、无端到端用例"的地方最容易藏缺陷。断言三件事：归属教师可见、
     * 非归属教师 403（服务层 requireOwnedExam）、学生 403（方法级 exam:manage 权限）。
     */
    @Test
    void reviewListByExamOwnerOnly() throws Exception {
        String teacher = registerTeacher();
        String otherTeacher = registerTeacher();
        String student = registerStudent();
        long examId = publishedExamWithScore(teacher, student);
        long reviewId = applyReview(examId, student, "清单可见性验证");

        JsonNode list = perform(jsonGet("/api/exams/" + examId + "/score-reviews", teacher), 200).get("data");
        assertTrue(list.isArray(), "复核清单应返回数组");
        assertEquals(1, list.size());
        assertEquals(reviewId, list.get(0).get("id").asLong());
        assertEquals(ScoreReview.STATUS_PENDING, list.get(0).get("status").asInt());

        // 非归属教师：可见性由服务层 requireOwnedExam 拦截
        perform(jsonGet("/api/exams/" + examId + "/score-reviews", otherTeacher), 403);
        // 学生：方法级 @RequirePermission("exam:manage") 只授予 TEACHER/ADMIN
        perform(jsonGet("/api/exams/" + examId + "/score-reviews", student), 403);
    }

    // ==================== 造数 ====================

    private long createClassWithStudents(String teacher, long... studentIds) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", unique("班"));
        long classId = perform(jsonPost("/api/classes", teacher,
                objectMapper.writeValueAsString(body)), 200).get("data").get("id").asLong();
        for (long studentId : studentIds) {
            ObjectNode join = objectMapper.createObjectNode();
            join.put("userId", studentId);
            perform(jsonPost("/api/classes/" + classId + "/students", teacher,
                    objectMapper.writeValueAsString(join)), 200);
        }
        return classId;
    }

    /** 组一卷三题（单选 5 + 多选 6 + 判断 4 = 15 分，全客观，批改/汇总无简答分支）。 */
    private long preparePaper(String token) throws Exception {
        long q1 = createQuestion(token, 1, "单选：1+1=?", "A", List.of("A", "B", "C", "D"), null);
        long q2 = createQuestion(token, 2, "多选：下列哪些是质数", "A,C", List.of("2", "4", "3", "9"), null);
        long q3 = createQuestion(token, 3, "判断：水加热到100摄氏度会沸腾", "T", null, null);
        long paperId = createPaper(token, unique("试卷"), 15);
        addPaperQuestion(token, paperId, q1, 5);
        addPaperQuestion(token, paperId, q2, 6);
        addPaperQuestion(token, paperId, q3, 4);
        return paperId;
    }

    private void addPaperQuestion(String token, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
    }

    private long createExam(String token, long paperId, Long classId,
                            LocalDateTime start, LocalDateTime end, int duration) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        if (classId != null) {
            body.put("classId", classId);
        }
        body.put("startTime", start.toString());
        body.put("endTime", end.toString());
        body.put("durationMinutes", duration);
        return perform(jsonPost("/api/exams", token, objectMapper.writeValueAsString(body)), 200)
                .get("data").get("id").asLong();
    }

    /** 造一场"进行中"考试：发布 → 手工驱动状态机推进。 */
    private long inProgressExam(String teacher, Long classId) throws Exception {
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId, classId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        assertEquals(Exam.STATUS_IN_PROGRESS, examMapper.selectById(examId).getStatus());
        return examId;
    }

    /** 造一场"已发布成绩"的考试：A 交卷 → 提前结束 → 判分 → 汇总 → 发布（复核类边界用例共用）。 */
    private long publishedExamWithScore(String teacher, String student) throws Exception {
        long examId = inProgressExam(teacher, null);
        JsonNode enterData = enter(student, examId, 200);
        submitAndConsume(student, examId, questionIds(enterData).get(0), "A");
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        perform(jsonPost("/api/exams/" + examId + "/grading/run", teacher, null), 200);
        perform(jsonPost("/api/exams/" + examId + "/scores/summarize", teacher, null), 200);
        ObjectNode publishBody = objectMapper.createObjectNode();
        publishBody.putArray("examIds").add(examId);
        perform(jsonPost("/api/scores/publish", teacher, objectMapper.writeValueAsString(publishBody)), 200);
        assertEquals(Exam.STATUS_PUBLISHED, examMapper.selectById(examId).getStatus());
        return examId;
    }

    private long createMakeup(String teacher, long mainExamId, long studentId,
                              LocalDateTime start, LocalDateTime end, int duration) throws Exception {
        return perform(jsonPost("/api/exams/" + mainExamId + "/makeups", teacher,
                objectMapper.writeValueAsString(makeupBody(start, end, duration, studentId))), 200)
                .get("data").get("examId").asLong();
    }

    private ObjectNode makeupBody(LocalDateTime start, LocalDateTime end,
                                  int duration, long... studentIds) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("startTime", start.toString());
        body.put("endTime", end.toString());
        body.put("durationMinutes", duration);
        for (long studentId : studentIds) {
            body.putArray("studentIds").add(studentId);
        }
        return body;
    }

    private ObjectNode reviewBody(String reason) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("reason", reason);
        return body;
    }

    private ObjectNode handleBody(String action, Integer adjustedTotalScore, String reason) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("action", action);
        if (adjustedTotalScore != null) {
            body.put("adjustedTotalScore", adjustedTotalScore);
        }
        body.put("reason", reason);
        return body;
    }

    private long applyReview(long examId, String student, String reason) throws Exception {
        JsonNode data = perform(jsonPost("/api/exams/" + examId + "/score-reviews", student,
                objectMapper.writeValueAsString(reviewBody(reason))), 200).get("data");
        return data.get("id").asLong();
    }

    // ==================== 工具 ====================

    private JsonNode enter(String student, long examId, int expectedStatus) throws Exception {
        return perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null),
                expectedStatus).get("data");
    }

    private List<Long> questionIds(JsonNode enterData) {
        List<Long> ids = new ArrayList<>();
        enterData.get("questions").forEach(q -> ids.add(q.get("questionId").asLong()));
        return ids;
    }

    private void submitAndConsume(String student, long examId, long questionId, String answer) throws Exception {
        ObjectNode answers = objectMapper.createObjectNode();
        answers.put(String.valueOf(questionId), answer);
        ObjectNode request = objectMapper.createObjectNode();
        request.set("answers", answers);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(request)), 200);
        consumeCapturedMessages(examId, studentIdOf(student));
    }

    /** 取出 Mock 收到的本场交卷消息，手工驱动真实消费者批量落库。 */
    private void consumeCapturedMessages(long examId, long studentId) throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(anyString(), anyString(), captor.capture());
        for (Object raw : new ArrayList<>(captor.getAllValues())) {
            SubmitMessage message = (SubmitMessage) raw;
            if (!message.getExamId().equals(examId) || !message.getStudentId().equals(studentId)) {
                continue;
            }
            Message amqp = new Message(objectMapper.writeValueAsBytes(message), new MessageProperties());
            amqp.getMessageProperties().setDeliveryTag(1L);
            submitConsumer.onBatch(List.of(amqp), mock(com.rabbitmq.client.Channel.class));
        }
    }

    private void moveEndTimePast(long examId) {
        examMapper.update(null, Wrappers.<Exam>lambdaUpdate()
                .eq(Exam::getId, examId)
                .set(Exam::getEndTime, LocalDateTime.now().minusMinutes(1)));
    }

    private long absenceCount(long examId) {
        return absenceMapper.selectCount(Wrappers.<ExamAbsence>lambdaQuery()
                .eq(ExamAbsence::getExamId, examId));
    }

    private long studentIdOf(String token) {
        return jwtUtil.parseAccessToken(token).getId();
    }
}
