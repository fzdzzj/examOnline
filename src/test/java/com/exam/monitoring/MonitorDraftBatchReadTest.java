package com.exam.monitoring;

import com.exam.auth.service.JwtUtil;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.support.IntegrationTestBase;
import com.exam.support.RabbitTemplateInvokeStubs;
import com.exam.taking.service.ExamDraftService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 监考总览草稿读取护栏（change update-monitor-overview-draft-batch-read，阶段 3 step1）。
 *
 * <p>核心护栏：一次 {@code GET /monitor/overview} 的草稿读取不得逐人往返——
 * 在旧实现上 {@code ExamDraftService.get} 会按进行中学生数被调用多次（红灯），
 * 改为批读后 {@code get} 调用数必须为 0（绿灯）。
 * 同时守住语义：缺键/空白/损坏/非对象 answers 按 0 进度、无进行中学生不读草稿、
 * 学生与值不得错位、题数为 0 时进度仍为 0、Redis 读取异常不得伪装成全员零进度、
 * 高亮排序与权限归属不变。
 *
 * <p>调用计数在发起 overview 前用 {@code clearInvocations} 清零：进入考试
 * （ExamTakingService.enter 的断线恢复）本身会读草稿，不计入总览窗口。
 */
class MonitorDraftBatchReadTest extends IntegrationTestBase {

    /** 与 ExamDraftService.KEY_PREFIX 一致（包级可见，测试侧按字面量写键）。 */
    private static final String DRAFT_KEY_PREFIX = "exam:draft:";

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoSpyBean
    private ExamDraftService draftService;

    @MockitoSpyBean
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private ExamSubmissionMapper submissionMapper;
    @Autowired
    private JwtUtil jwtUtil;

    @BeforeEach
    void runRabbitInvokeCallbacks() {
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    // ==================== 造数 ====================

    private long prepareFourQuestionPaper(String token) throws Exception {
        long q1 = createQuestion(token, 1, "单选：1+1=?", "A", List.of("A", "B", "C", "D"), null);
        long q2 = createQuestion(token, 1, "单选：2+2=?", "B", List.of("A", "B", "C", "D"), null);
        long q3 = createQuestion(token, 1, "单选：3+3=?", "C", List.of("A", "B", "C", "D"), null);
        long q4 = createQuestion(token, 1, "单选：4+4=?", "D", List.of("A", "B", "C", "D"), null);
        long paperId = createPaper(token, unique("试卷"), 20);
        for (long q : new long[] {q1, q2, q3, q4}) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("questionId", q);
            body.put("score", 5);
            perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                    objectMapper.writeValueAsString(body)), 200);
        }
        return paperId;
    }

    private long createExam(String token, long paperId, Long classId, LocalDateTime start,
                            LocalDateTime end, int duration) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        if (classId != null) {
            body.put("classId", classId);
        }
        body.put("startTime", start.toString());
        body.put("endTime", end.toString());
        body.put("durationMinutes", duration);
        JsonNode data = perform(jsonPost("/api/exams", token,
                objectMapper.writeValueAsString(body)), 200).get("data");
        return data.get("id").asLong();
    }

    private long preparedInProgressExam(String teacher, long paperId) throws Exception {
        long classId = createClassForExam(teacher);
        long examId = createExam(teacher, paperId, classId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        return examId;
    }

    private void enter(String student, long examId) throws Exception {
        // 既有用例夹具适配：进入成功路径先确保学生已入班（不改变任何断言语义）
        ensureExamClassMembership(student, examId);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null), 200);
    }

    private long studentIdOf(String studentToken) {
        return jwtUtil.parseAccessToken(studentToken).getId();
    }

    /** 直接写入草稿原始值（覆盖损坏/空白/非对象等形态）。 */
    private void writeDraftRaw(long examId, long studentId, String value) {
        redisTemplate.opsForValue().set(DRAFT_KEY_PREFIX + examId + ":" + studentId, value);
    }

    /** 合法草稿：answers 含 count 个键（进度只取字段数，键名无关）。 */
    private String draftJson(int count) {
        ObjectNode answers = objectMapper.createObjectNode();
        for (int i = 1; i <= count; i++) {
            answers.put(String.valueOf(i), "A");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", 1);
        root.set("answers", answers);
        root.putArray("marked");
        root.put("savedTime", LocalDateTime.now().toString());
        return root.toString();
    }

    /** 直接把答卷置为已交卷（避免走交卷/判分/MQ 链路，本护栏只关心读路径）。 */
    private void markSubmitted(long examId, long studentId) {
        ExamSubmission sub = submissionMapper.selectByExamStudent(examId, studentId);
        assertNotNull(sub, "答卷应存在");
        sub.setStatus(ExamSubmission.STATUS_SUBMITTED);
        submissionMapper.updateById(sub);
    }

    private JsonNode overview(String teacher, long examId) throws Exception {
        return perform(jsonGet("/api/exams/" + examId + "/monitor/overview", teacher), 200).get("data");
    }

    private Map<Long, JsonNode> rowsById(JsonNode data) {
        Map<Long, JsonNode> rows = new HashMap<>();
        for (JsonNode row : data.get("students")) {
            rows.put(row.get("studentId").asLong(), row);
        }
        return rows;
    }

    private void report(String student, long examId, String eventType) throws Exception {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("eventType", eventType);
        event.putObject("eventData").put("awaySeconds", 5);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/behavior", student,
                objectMapper.writeValueAsString(event)), 200);
    }

    // ==================== 核心护栏：批读 + 逐学生进度对齐 ====================

    @Test
    void overviewReadsDraftsInBatchAndKeepsPerStudentProgress() throws Exception {
        String teacher = registerTeacher();
        long examId = preparedInProgressExam(teacher, prepareFourQuestionPaper(teacher));

        String s1 = registerStudent();
        String s2 = registerStudent();
        String s3 = registerStudent();
        String s4 = registerStudent();
        String s5 = registerStudent();
        String s6 = registerStudent();
        String s7 = registerStudent();
        for (String s : List.of(s1, s2, s3, s4, s5, s6, s7)) {
            enter(s, examId);
        }
        long id1 = studentIdOf(s1);
        long id2 = studentIdOf(s2);
        long id3 = studentIdOf(s3);
        long id4 = studentIdOf(s4);
        long id5 = studentIdOf(s5);
        long id6 = studentIdOf(s6);
        long id7 = studentIdOf(s7);

        // 进行中：1 答 / 2 答 / 缺键 / 损坏 / 空白 / answers 非对象；另加一名已交卷（混合状态）
        writeDraftRaw(examId, id1, draftJson(1));
        writeDraftRaw(examId, id2, draftJson(2));
        // id3 不写键（缺键）
        writeDraftRaw(examId, id4, "{\"version\":1,\"answers\":{\"1\":\"A\"");
        writeDraftRaw(examId, id5, "");
        writeDraftRaw(examId, id6,
                "{\"version\":1,\"answers\":\"oops\",\"marked\":[],\"savedTime\":\"2026-01-01T00:00:00\"}");
        markSubmitted(examId, id7);

        // 只统计本次总览窗口内的草稿读取（进入考试的断线恢复读草稿不计）
        clearInvocations(draftService);
        JsonNode data = overview(teacher, examId);

        assertEquals(7, data.get("totalStudents").asInt());
        assertEquals(4, data.get("totalQuestions").asInt());

        // 学生与值必须严格对齐：每人答案条数不同，错位会立刻暴露
        Map<Long, JsonNode> rows = rowsById(data);
        assertEquals(1, rows.get(id1).get("answeredCount").asInt());
        assertEquals(25, rows.get(id1).get("progressPercent").asInt());
        assertEquals(2, rows.get(id2).get("answeredCount").asInt());
        assertEquals(50, rows.get(id2).get("progressPercent").asInt());
        assertEquals(0, rows.get(id3).get("answeredCount").asInt());
        assertEquals(0, rows.get(id3).get("progressPercent").asInt());
        assertEquals(0, rows.get(id4).get("answeredCount").asInt());
        assertEquals(0, rows.get(id4).get("progressPercent").asInt());
        assertEquals(0, rows.get(id5).get("answeredCount").asInt());
        assertEquals(0, rows.get(id5).get("progressPercent").asInt());
        assertEquals(0, rows.get(id6).get("answeredCount").asInt());
        assertEquals(0, rows.get(id6).get("progressPercent").asInt());
        assertEquals("SUBMITTED", rows.get(id7).get("status").asText());
        assertEquals(4, rows.get(id7).get("answeredCount").asInt());
        assertEquals(100, rows.get(id7).get("progressPercent").asInt());

        // 核心护栏：总览不得逐人读草稿
        verify(draftService, never()).get(anyLong(), anyLong());
    }

    // ==================== 无进行中学生：不读草稿 ====================

    @Test
    void noInProgressStudentsSkipsDraftReads() throws Exception {
        String teacher = registerTeacher();
        long examId = preparedInProgressExam(teacher, prepareFourQuestionPaper(teacher));
        String s1 = registerStudent();
        enter(s1, examId);
        long id1 = studentIdOf(s1);
        writeDraftRaw(examId, id1, draftJson(2));
        markSubmitted(examId, id1);

        clearInvocations(draftService);
        JsonNode data = overview(teacher, examId);

        assertEquals(1, data.get("totalStudents").asInt());
        assertEquals(1, data.get("submittedCount").asInt());
        assertEquals(4, rowsById(data).get(id1).get("answeredCount").asInt());
        assertEquals(100, rowsById(data).get(id1).get("progressPercent").asInt());
        verify(draftService, never()).get(anyLong(), anyLong());
    }

    // ==================== 题数为 0：既有进度不变（仍为 0） ====================

    @Test
    void zeroTotalQuestionsKeepsZeroProgress() throws Exception {
        String teacher = registerTeacher();
        long examId = preparedInProgressExam(teacher, prepareFourQuestionPaper(teacher));
        String s1 = registerStudent();
        enter(s1, examId);
        long id1 = studentIdOf(s1);
        writeDraftRaw(examId, id1, draftJson(2));

        // 快照题数为 0（分母缺失）时，进度按既有口径仍为 0，不得除零或伪装成 100
        ExamSubmission sub = submissionMapper.selectByExamStudent(examId, id1);
        sub.setPaperJson("{\"questions\":[]}");
        submissionMapper.updateById(sub);

        JsonNode data = overview(teacher, examId);

        assertEquals(0, data.get("totalQuestions").asInt());
        assertEquals(0, rowsById(data).get(id1).get("progressPercent").asInt());
    }

    // ==================== Redis 读取异常：不得伪装成全员零进度 ====================

    @Test
    void redisReadFailureIsNotDisguisedAsZeroProgress() throws Exception {
        String teacher = registerTeacher();
        long examId = preparedInProgressExam(teacher, prepareFourQuestionPaper(teacher));
        String s1 = registerStudent();
        enter(s1, examId);
        writeDraftRaw(examId, studentIdOf(s1), draftJson(2));

        // Redis 读取故障（真实 TTL 键仍在）：若把故障当"无草稿"，会静默显示成 0 进度
        doThrow(new RuntimeException("redis down")).when(redisTemplate).opsForValue();

        perform(jsonGet("/api/exams/" + examId + "/monitor/overview", teacher), 500);
    }

    // ==================== 高亮排序与权限归属 ====================

    @Test
    void highlightSortAndAccessControlUnchanged() throws Exception {
        String teacher = registerTeacher();
        String otherTeacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher, prepareFourQuestionPaper(teacher));

        String sA = registerStudent();
        String sB = registerStudent();
        enter(sA, examId);
        enter(sB, examId);
        long idA = studentIdOf(sA);
        long idB = studentIdOf(sB);
        writeDraftRaw(examId, idB, draftJson(2));

        // A 连续切屏 3 次升"中" → 异常高亮并排前（B 进度 50% 更高但非异常）
        report(sA, examId, "SWITCH_SCREEN");
        report(sA, examId, "SWITCH_SCREEN");
        report(sA, examId, "SWITCH_SCREEN");

        clearInvocations(draftService);
        JsonNode data = overview(teacher, examId);

        assertEquals(1, data.get("abnormalCount").asInt());
        JsonNode first = data.get("students").get(0);
        assertEquals(idA, first.get("studentId").asLong());
        assertTrue(first.get("abnormal").asBoolean());
        JsonNode second = data.get("students").get(1);
        assertEquals(idB, second.get("studentId").asLong());
        assertEquals(50, second.get("progressPercent").asInt());
        verify(draftService, never()).get(anyLong(), anyLong());

        // 权限归属：学生 403、非归属教师 403
        perform(jsonGet("/api/exams/" + examId + "/monitor/overview", student), 403);
        perform(jsonGet("/api/exams/" + examId + "/monitor/overview", otherTeacher), 403);
    }

    // ==================== getBatch：与 get 同口径 + 结果无法对齐时报错 ====================

    @Test
    void getBatchSharesParsingWithGetAndRejectsMisalignedResult() throws Exception {
        String teacher = registerTeacher();
        long examId = preparedInProgressExam(teacher, prepareFourQuestionPaper(teacher));
        String s1 = registerStudent();
        String s2 = registerStudent();
        enter(s1, examId);
        enter(s2, examId);
        long id1 = studentIdOf(s1);
        long id2 = studentIdOf(s2);
        writeDraftRaw(examId, id1, draftJson(2));
        writeDraftRaw(examId, id2, "{\"version\":1,\"answers\":{\"1\":\"A\"");

        // 批读逐值口径必须与单份 get 等价（同一解析实现）
        Map<Long, ExamDraftService.DraftState> batch = draftService.getBatch(examId, List.of(id1, id2));
        assertEquals(draftService.get(examId, id1), batch.get(id1), "批读结果须与单份 get 等价");
        assertFalse(batch.containsKey(id2), "损坏草稿按无草稿处理（不在 Map 中）");
        assertNull(draftService.get(examId, id2));

        // 空入参：不触达 Redis，直接空 Map
        assertEquals(Map.of(), draftService.getBatch(examId, List.of()));

        // MGET 结果长度与请求学生无法一一对齐 → 抛错，不得错位或当零进度
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        doReturn(valueOps).when(redisTemplate).opsForValue();
        when(valueOps.multiGet(anyList())).thenReturn(List.of("only-one"));
        assertThrows(IllegalStateException.class, () -> draftService.getBatch(examId, List.of(id1, id2)));

        // 结果整段缺失同样抛错
        when(valueOps.multiGet(anyList())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> draftService.getBatch(examId, List.of(id1, id2)));
    }
}