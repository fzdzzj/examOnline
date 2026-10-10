package com.exam.grading;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.service.JwtUtil;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.submission.mq.ExamSubmitConsumer;
import com.exam.support.IntegrationTestBase;
import com.exam.support.RabbitTemplateInvokeStubs;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 主观题批改行分页与筛选集成测试（add-subjective-grading-pagination，H2 + MockMvc，MQ 为 Mock）。
 *
 * <p>覆盖 spec-delta 全场景：分页取数（page/size 稳定子集 + total/graded 信封）、缺省全量、
 * onlyUngraded / name 筛选下沉、submissionId 单行取数（冲突回填链路）、非法 page/size 400、
 * 越权 403 口径不变、graded 与题级进度（SubjectiveQuestionItem.gradedStudents）口径一致。
 * 造数走真实链路（交卷 → 整场判分 → 教师批改 2 人），无 @Sql 自建表。
 */
class SubjectiveRowsPaginationIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    /**
     * ExamSubmitSender 的「发送 + confirm 等待」在 RabbitTemplate.invoke() 作用域内；
     * mock 的 invoke 默认不执行 callback，会漏掉 callback 内的 3 参 convertAndSend 调用。
     */
    @BeforeEach
    void runRabbitInvokeCallbacks() {
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Autowired
    private SubjectiveGradeMapper subjectiveGradeMapper;
    @Autowired
    private ExamSubmitConsumer submitConsumer;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JwtUtil jwtUtil;

    /** 一次造数的产物：两个教师、5 名学生（按 id 序）、考试、简答题快照 ID、各生答卷 ID。 */
    private record Seed(String teacher, String otherTeacher, long examId, long shortQuestionId,
                        List<Long> studentIdsInIdOrder, Map<Long, Long> submissionIdByStudent) {
    }

    // ==================== 造数 ====================

    /**
     * 5 名学生交卷并整场判分，教师批改 id 序最小的 2 人：
     * 行总数 5、已批 2、未批 3；学生姓名可控（name 筛选按 id 序对齐：甲一/甲二/乙一/乙二/丙）。
     */
    private Seed seed() throws Exception {
        String teacher = registerTeacher();
        String otherTeacher = registerTeacher();
        List<String> studentTokens = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            studentTokens.add(registerStudent());
        }

        // 试卷：只有 1 道 6 分简答（行全部落在这道题上，快照题 ID 从进入考试响应取）
        long qShort = createQuestion(teacher, 4, "简答：什么是HTTP", "HTTP,协议,无状态", null, null);
        long paperId = createPaper(teacher, unique("试卷"), 6);
        addPaperQuestion(teacher, paperId, qShort, 6);
        long classId = createClassForExam(teacher);
        ObjectNode examBody = objectMapper.createObjectNode();
        examBody.put("title", unique("考试"));
        examBody.put("paperId", paperId);
        examBody.put("classId", classId);
        examBody.put("startTime", LocalDateTime.now().minusMinutes(1).toString());
        examBody.put("endTime", LocalDateTime.now().plusHours(2).toString());
        examBody.put("durationMinutes", 30);
        long examId = perform(jsonPost("/api/exams", teacher,
                objectMapper.writeValueAsString(examBody)), 200).get("data").get("id").asLong();
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();

        // 学生姓名可控（行联查 users.name；注册序 = id 序，姓名按下标对齐）
        String[] names = {"测试甲一", "测试甲二", "测试乙一", "测试乙二", "测试丙"};
        List<Long> studentIds = studentTokens.stream()
                .map(token -> jwtUtil.parseAccessToken(token).getId())
                .sorted()
                .collect(Collectors.toList());
        for (int i = 0; i < studentIds.size(); i++) {
            User user = new User();
            user.setId(studentIds.get(i));
            user.setName(names[i]);
            userMapper.updateById(user);
        }

        // 交卷 → 消费落库 → 强制结束 → 整场判分（简答建行，score=null）
        long shortQuestionId = 0;
        for (String token : studentTokens) {
            ensureExamClassMembership(token, examId);
            JsonNode data = perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", token, null), 200)
                    .get("data");
            long questionId = data.get("questions").get(0).get("questionId").asLong();
            shortQuestionId = questionId;
            ObjectNode answers = objectMapper.createObjectNode();
            answers.put(String.valueOf(questionId), "HTTP 是无状态的协议");
            ObjectNode request = objectMapper.createObjectNode();
            request.set("answers", answers);
            perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", token,
                    objectMapper.writeValueAsString(request)), 200);
        }
        consumeCapturedMessages(examId);
        Map<Long, Long> submissionIdByStudent = new LinkedHashMap<>();
        for (Long studentId : studentIds) {
            submissionIdByStudent.put(studentId, submissionIdOf(examId, studentId));
        }
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        perform(jsonPost("/api/exams/" + examId + "/grading/run", teacher, null), 200);

        // 教师批改 id 序最小的 2 人 → graded=2
        for (int i = 0; i < 2; i++) {
            long studentId = studentIds.get(i);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("submissionId", submissionIdByStudent.get(studentId));
            body.put("questionId", shortQuestionId);
            body.put("score", new BigDecimal("5.0"));
            body.put("expectedVersion", 0);
            body.put("comment", "批改-" + i);
            perform(jsonPost("/api/exams/" + examId + "/grading/subjective/save", teacher,
                    objectMapper.writeValueAsString(body)), 200);
        }
        return new Seed(teacher, otherTeacher, examId, shortQuestionId,
                studentIds, submissionIdByStudent);
    }

    private long addPaperQuestion(String token, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
        return questionId;
    }

    /** 取 Mock 收到的交卷消息，驱动真实消费者批量落库（消费幂等，重复驱动不重复写）。 */
    private void consumeCapturedMessages(long examId) throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(anyString(), anyString(), captor.capture());
        for (Object raw : new ArrayList<>(captor.getAllValues())) {
            com.exam.submission.dto.SubmitMessage message = (com.exam.submission.dto.SubmitMessage) raw;
            if (!message.getExamId().equals(examId)) {
                continue;
            }
            Message amqp = new Message(objectMapper.writeValueAsBytes(message), new MessageProperties());
            amqp.getMessageProperties().setDeliveryTag(1L);
            submitConsumer.onBatch(List.of(amqp), mock(com.rabbitmq.client.Channel.class));
        }
    }

    private long submissionIdOf(long examId, long studentId) {
        return gradingSubmissionMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
                .eq(GradingSubmission::getExamId, examId)
                .eq(GradingSubmission::getStudentId, studentId)).getId();
    }

    /** 以指定教师身份请求行端点，断言 HTTP 状态后返回响应体（questionId 已内置，其余参数经 param 追加）。 */
    private JsonNode rowsBody(Seed seed, String token, Map<String, String> params,
                              int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder builder = get("/api/exams/" + seed.examId()
                        + "/grading/subjective")
                .param("questionId", String.valueOf(seed.shortQuestionId()))
                .header("Authorization", bearer(token));
        params.forEach(builder::param);
        MvcResult result = mockMvc.perform(builder).andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode rowsBody(Seed seed, Map<String, String> params, int expectedStatus) throws Exception {
        return rowsBody(seed, seed.teacher(), params, expectedStatus);
    }

    // ==================== 分页取数 ====================

    @Test
    void pagedSubsetStableOrderWithTotalAndGraded() throws Exception {
        Seed seed = seed();
        List<Long> ids = seed.studentIdsInIdOrder();

        JsonNode data1 = rowsBody(seed, Map.of("page", "1", "size", "2"), 200).get("data");
        assertEquals(5, data1.get("total").asInt(), "total=全部行数");
        assertEquals(2, data1.get("graded").asInt(), "graded=已批行数");
        assertEquals(2, data1.get("rows").size());
        assertEquals(ids.get(0), data1.get("rows").get(0).get("studentId").asLong());
        assertEquals(ids.get(1), data1.get("rows").get(1).get("studentId").asLong());

        JsonNode data2 = rowsBody(seed, Map.of("page", "2", "size", "2"), 200).get("data");
        assertEquals(5, data2.get("total").asInt());
        assertEquals(2, data2.get("rows").size());
        assertEquals(ids.get(2), data2.get("rows").get(0).get("studentId").asLong());
        assertEquals(ids.get(3), data2.get("rows").get(1).get("studentId").asLong());

        JsonNode data3 = rowsBody(seed, Map.of("page", "3", "size", "2"), 200).get("data");
        assertEquals(1, data3.get("rows").size(), "末页只余 1 行");
        assertEquals(ids.get(4), data3.get("rows").get(0).get("studentId").asLong());

        // 翻页不丢行不错行：三页拼接 = 全量 id 序，无重复
        List<Long> collected = new ArrayList<>();
        for (JsonNode data : List.of(data1, data2, data3)) {
            data.get("rows").forEach(row -> collected.add(row.get("studentId").asLong()));
        }
        assertEquals(ids, collected);
    }

    @Test
    void outOfBoundsPageReturnsEmptyRows() throws Exception {
        Seed seed = seed();
        // 总数 5 条，size=2 时共 3 页；page=4 或 page=999 为越界页，rows 为空，total=5，graded=2
        JsonNode dataPage4 = rowsBody(seed, Map.of("page", "4", "size", "2"), 200).get("data");
        assertEquals(5, dataPage4.get("total").asInt(), "越界页 total 仍为真实总数");
        assertEquals(2, dataPage4.get("graded").asInt(), "越界页 graded 仍为真实已批数");
        assertEquals(0, dataPage4.get("rows").size(), "越界页 rows 为空");

        JsonNode dataPage999 = rowsBody(seed, Map.of("page", "999", "size", "10"), 200).get("data");
        assertEquals(5, dataPage999.get("total").asInt());
        assertEquals(2, dataPage999.get("graded").asInt());
        assertEquals(0, dataPage999.get("rows").size());
    }

    @Test
    void adjacentPagesAreDisjointAndConsecutiveWithoutOverlap() throws Exception {
        Seed seed = seed();
        JsonNode page1 = rowsBody(seed, Map.of("page", "1", "size", "2"), 200).get("data");
        JsonNode page2 = rowsBody(seed, Map.of("page", "2", "size", "2"), 200).get("data");

        assertEquals(2, page1.get("rows").size());
        assertEquals(2, page2.get("rows").size());

        long page1LastStudentId = page1.get("rows").get(1).get("studentId").asLong();
        long page2FirstStudentId = page2.get("rows").get(0).get("studentId").asLong();

        // 排序稳定严格递增：page1 末行 student_id < page2 首行 student_id，无缝隙无重叠
        assertTrue(page1LastStudentId < page2FirstStudentId,
                "相邻页严格递增无重叠：page1 末行 " + page1LastStudentId + " < page2 首行 " + page2FirstStudentId);
    }

    // ==================== 缺省全量 ====================


    @Test
    void defaultParamsReturnFullEnvelope() throws Exception {
        Seed seed = seed();
        List<Long> ids = seed.studentIdsInIdOrder();

        JsonNode data = rowsBody(seed, Map.of(), 200).get("data");
        assertEquals(5, data.get("rows").size(), "缺省时 rows=该题全部行");
        assertEquals(5, data.get("total").asInt());
        assertEquals(2, data.get("graded").asInt());
        List<Long> collected = new ArrayList<>();
        data.get("rows").forEach(row -> collected.add(row.get("studentId").asLong()));
        assertEquals(ids, collected, "缺省全量仍按 student_id 稳定排序");
    }

    // ==================== 筛选下沉 ====================

    @Test
    void onlyUngradedFilterPushedDown() throws Exception {
        Seed seed = seed();

        JsonNode data = rowsBody(seed, Map.of("onlyUngraded", "true"), 200).get("data");
        assertEquals(3, data.get("rows").size(), "5 行中 3 行未批");
        assertEquals(3, data.get("total").asInt(), "total=筛选后行数（服务端分页基数）");
        assertEquals(2, data.get("graded").asInt(), "graded 与题级进度口径一致，不受行筛选影响");
        // 全局 ObjectMapper 配置 NON_NULL：未批行的 score 字段缺省（而非显式 null 节点）
        data.get("rows").forEach(row -> assertTrue(row.get("score") == null || row.get("score").isNull(),
                "筛选结果每行都未批"));

        JsonNode all = rowsBody(seed, Map.of("onlyUngraded", "false"), 200).get("data");
        assertEquals(5, all.get("rows").size(), "onlyUngraded=false 不过滤");
    }

    @Test
    void nameFilterLikePushedDown() throws Exception {
        Seed seed = seed();

        JsonNode data = rowsBody(seed, Map.of("name", "甲"), 200).get("data");
        assertEquals(2, data.get("rows").size(), "甲一/甲二命中");
        assertEquals(2, data.get("total").asInt());
        data.get("rows").forEach(row -> assertTrue(row.get("studentName").asText().contains("甲"),
                "返回行姓名都含筛选词"));

        JsonNode single = rowsBody(seed, Map.of("name", "丙"), 200).get("data");
        assertEquals(1, single.get("rows").size(), "丙单独命中");
    }

    // ==================== 冲突回填单行取数 ====================

    @Test
    void submissionIdReturnsSingleRow() throws Exception {
        Seed seed = seed();
        long targetStudent = seed.studentIdsInIdOrder().get(3);
        long submissionId = seed.submissionIdByStudent().get(targetStudent);

        JsonNode data = rowsBody(seed, Map.of("submissionId", String.valueOf(submissionId)), 200).get("data");
        assertEquals(1, data.get("rows").size(), "submissionId 单行取数至多 1 行");
        assertEquals(targetStudent, data.get("rows").get(0).get("studentId").asLong());
        assertEquals(submissionId, data.get("rows").get(0).get("submissionId").asLong());
        assertEquals(1, data.get("total").asInt());
    }

    // ==================== 非法参数与越权 ====================

    @Test
    void invalidPageOrSizeRejected() throws Exception {
        Seed seed = seed();

        for (Map<String, String> bad : List.of(Map.of("page", "0"), Map.of("page", "-1"),
                Map.of("size", "0"), Map.of("size", "101"))) {
            JsonNode body = rowsBody(seed, bad, 400);
            assertEquals(400, body.get("code").asInt(), "业务码 400：" + bad);
        }

        // 合法边界不拒：page=1 + size=100（上限）
        rowsBody(seed, Map.of("page", "1", "size", "100"), 200);
    }

    @Test
    void nonOwnerTeacherForbidden() throws Exception {
        Seed seed = seed();
        rowsBody(seed, seed.otherTeacher(), Map.of("page", "1", "size", "10"), 403);
    }

    // ==================== graded 与题级进度口径一致 ====================

    @Test
    void gradedMatchesQuestionLevelProgress() throws Exception {
        Seed seed = seed();

        JsonNode questions = perform(jsonGet(
                "/api/exams/" + seed.examId() + "/grading/subjective/questions", seed.teacher()), 200)
                .get("data");
        assertEquals(1, questions.size());
        int gradedStudents = questions.get(0).get("gradedStudents").asInt();
        int totalStudents = questions.get(0).get("totalStudents").asInt();
        assertEquals(2, gradedStudents, "题级进度：已批 2 人");
        assertEquals(5, totalStudents);

        JsonNode data = rowsBody(seed, Map.of(), 200).get("data");
        assertEquals(gradedStudents, data.get("graded").asInt(), "信封 graded 与题级 gradedStudents 同口径");
        assertEquals(totalStudents, data.get("total").asInt(), "缺省全量下 total 与题级 totalStudents 同口径");
    }
}
