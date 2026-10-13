package com.exam.question;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.service.JwtUtil;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamSnapshot;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.mapper.ExamSnapshotMapper;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.submission.dto.SubmitMessage;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 题干图片集成测试（spec add-question-image，H2 + MockMvc，MQ 为 Mock）：
 * <ul>
 *   <li>上传/下载：合法 PNG 上传→{url}→公开下载字节一致；魔数拒绝（伪装扩展名）、
 *       超 5MB 拒绝、非白名单扩展名拒绝、无 question:manage 权限 403；</li>
 *   <li>下载护栏：文件名白名单（非 UUID 形态即使同名文件在盘也 404）、查无文件 404；</li>
 *   <li>建题带图：imageUrl 可选、/images/ 前缀校验（任意外链 400）、超长 400、空串按无图；</li>
 *   <li>全链路：建带图题→组卷→发布（快照含 imageUrl，无图题为 null）→拉卷（学生所见含图）→
 *       判分→批改工作台/逐题回顾/错题本均携带 imageUrl；既有无图题零回归。</li>
 * </ul>
 */
class QuestionImageIntegrationTest extends IntegrationTestBase {

    /** PNG 文件头（89 50 4E 47 0D 0A 1A 0A）+ 数字节体，足够过魔数校验并被原样下载。 */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03};

    private static final String IMAGE_URL = "/images/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.png";

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private ExamSnapshotMapper examSnapshotMapper;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private ExamSubmitConsumer submitConsumer;

    @Value("${exam.question-image.dir:./data/question-images}")
    private String imageDir;

    @BeforeEach
    void runRabbitInvokeCallbacks() {
        RabbitTemplateInvokeStubs.runInvokeCallbacks(rabbitTemplate);
    }

    // ==================== 上传与下载 ====================

    @Test
    void uploadSuccessAndPublicDownloadRoundTrip() throws Exception {
        String teacher = registerTeacher();
        JsonNode upload = perform(multipart("/api/question-images")
                        .file(new MockMultipartFile("file", "photo.png",
                                MediaType.IMAGE_PNG_VALUE, PNG_BYTES))
                        .header("Authorization", bearer(teacher)), 200)
                .get("data");
        String url = upload.get("url").asText();
        assertTrue(url.matches("^/images/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png$"),
                "url 应为 /images/{uuid}.png：实际 " + url);

        // 下载端点公开只读：不带 Token 直接可取，字节与上传一致，Content-Type 按扩展名
        MvcResult download = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        assertArrayEquals(PNG_BYTES, download.getResponse().getContentAsByteArray(), "下载字节与上传一致");
        assertEquals("image/png", download.getResponse().getContentType());
    }

    @Test
    void uploadRejectsFakeMagicOversizeAndBadExtension() throws Exception {
        String teacher = registerTeacher();

        // 魔数不匹配（.png 扩展名但内容是纯文本）→ 400，不落盘
        perform(multipart("/api/question-images")
                        .file(new MockMultipartFile("file", "fake.png",
                                MediaType.IMAGE_PNG_VALUE, "我不是图片".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(teacher)), 400);

        // 超大（>5MB）→ 400
        byte[] oversize = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG_BYTES, 0, oversize, 0, PNG_BYTES.length);
        perform(multipart("/api/question-images")
                        .file(new MockMultipartFile("file", "big.png", MediaType.IMAGE_PNG_VALUE, oversize))
                        .header("Authorization", bearer(teacher)), 400);

        // 非白名单扩展名（.txt 携带合法 PNG 头）→ 400
        perform(multipart("/api/question-images")
                        .file(new MockMultipartFile("file", "evil.txt", MediaType.TEXT_PLAIN_VALUE, PNG_BYTES))
                        .header("Authorization", bearer(teacher)), 400);
    }

    @Test
    void uploadRequiresQuestionManagePermission() throws Exception {
        String student = registerStudent();
        // 学生无 question:manage → 403
        perform(multipart("/api/question-images")
                        .file(new MockMultipartFile("file", "photo.png",
                                MediaType.IMAGE_PNG_VALUE, PNG_BYTES))
                        .header("Authorization", bearer(student)), 403);
        // 未登录 → 401
        perform(multipart("/api/question-images")
                .file(new MockMultipartFile("file", "photo.png",
                        MediaType.IMAGE_PNG_VALUE, PNG_BYTES)), 401);
    }

    @Test
    void downloadRejectsNonWhitelistedFilenameEvenIfFileExists() throws Exception {
        // 存储目录内真实放置一个非 UUID 形态文件名：白名单仍必须 404（防任意文件被服务/路径穿越）
        Path dir = Paths.get(imageDir);
        Files.createDirectories(dir);
        Path planted = dir.resolve("not-a-uuid.png");
        Files.write(planted, PNG_BYTES);
        try {
            perform(get("/images/not-a-uuid.png"), 404);
            // 路径穿越形态同样 404（白名单正则直接拒绝）
            perform(get("/images/..%2F..%2Fpom.xml"), 404);
            // 合法 UUID 形态但文件不存在 → 404
            perform(get("/images/00000000-0000-0000-0000-000000000000.png"), 404);
        } finally {
            Files.deleteIfExists(planted);
        }
    }

    // ==================== 建题带图与快照携带 ====================

    @Test
    void createQuestionValidatesImageUrl() throws Exception {
        String teacher = registerTeacher();

        // 任意外链 URL → 400
        ObjectNode external = createBody(4, "外链题", "参考答案");
        external.put("imageUrl", "https://evil.com/x.png");
        perform(jsonPost("/api/questions", teacher, objectMapper.writeValueAsString(external)), 400);

        // 非 /images/ 前缀（如缺前导斜杠）→ 400
        ObjectNode noPrefix = createBody(4, "前缀题", "参考答案");
        noPrefix.put("imageUrl", "images/foo.png");
        perform(jsonPost("/api/questions", teacher, objectMapper.writeValueAsString(noPrefix)), 400);

        // 超 512 → 400
        ObjectNode tooLong = createBody(4, "超长题", "参考答案");
        tooLong.put("imageUrl", "/images/" + "a".repeat(512));
        perform(jsonPost("/api/questions", teacher, objectMapper.writeValueAsString(tooLong)), 400);

        // 空串 = 无图（允许，入库归一为 null；响应 JSON 按 non_null 全局配置省略 null 字段）
        ObjectNode empty = createBody(4, "空图题", "参考答案");
        empty.put("imageUrl", "");
        JsonNode created = perform(jsonPost("/api/questions", teacher,
                objectMapper.writeValueAsString(empty)), 200).get("data");
        assertTrue(isNullish(created.get("imageUrl")), "空串应入库为 null（响应省略或显式 null）");
    }

    @Test
    void fullChainImageCarriesThroughSnapshotsAndViews() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();

        // 建两题：简答带图 + 单选无图（零回归对照，正确答案 B——学生答 A 必错进错题本）
        long imageShortId = createImageQuestion(teacher, 4, "简答：描述题干图片内容", "描述准确", IMAGE_URL);
        long plainSingleId = createImageQuestion(teacher, 1, "无图单选题", "B", null);
        long paperId = createPaper(teacher, unique("图片试卷"), 10);
        addPaperQuestion(teacher, paperId, imageShortId, 6);
        addPaperQuestion(teacher, paperId, plainSingleId, 4);

        long classId = createClassForExam(teacher);
        long examId = createExam(teacher, paperId, classId);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();

        // 发布后考试快照（exam_snapshots.paper_json）携带 imageUrl：带图题有值、无图题为 null
        ExamSnapshot snapshot = examSnapshotMapper.selectOne(
                Wrappers.<ExamSnapshot>lambdaQuery().eq(ExamSnapshot::getExamId, examId));
        Map<Long, JsonNode> snapshotByQuestion = new HashMap<>();
        objectMapper.readTree(snapshot.getPaperJson()).get("questions")
                .forEach(q -> snapshotByQuestion.put(q.get("questionId").asLong(), q));
        assertEquals(IMAGE_URL, snapshotByQuestion.get(imageShortId).get("imageUrl").asText());
        assertTrue(snapshotByQuestion.get(plainSingleId).get("imageUrl").isNull(), "无图题快照 imageUrl 为 null");

        // 学生拉卷：个人快照视图携带 imageUrl（带图题有值、无图题为 null）
        ensureExamClassMembership(student, examId);
        JsonNode entered = perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null), 200)
                .get("data");
        Map<Long, JsonNode> viewByQuestion = new HashMap<>();
        entered.get("questions").forEach(q -> viewByQuestion.put(q.get("questionId").asLong(), q));
        assertEquals(IMAGE_URL, viewByQuestion.get(imageShortId).get("imageUrl").asText(), "拉卷视图应携带题干图");
        assertTrue(isNullish(viewByQuestion.get(plainSingleId).get("imageUrl")), "无图题拉卷零回归");

        // 学生作答提交（简答原文 + 单选 A）
        ObjectNode answers = objectMapper.createObjectNode();
        answers.put(String.valueOf(imageShortId), "完整描述了图片内容");
        answers.put(String.valueOf(plainSingleId), "A");
        ObjectNode request = objectMapper.createObjectNode();
        request.set("answers", answers);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(request)), 200);
        consumeCapturedMessages(examId);

        // 判分：简答建批改行（主观链），单选客观自动判分
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200);
        JsonNode run = perform(jsonPost("/api/exams/" + examId + "/grading/run", teacher, null), 200).get("data");
        assertEquals(1, run.get("total").asInt());
        assertEquals(1, run.get("success").asInt());

        // 批改工作台（主观题清单）：题干图随快照事实源携带
        JsonNode subjectiveQuestions = perform(jsonGet(
                "/api/exams/" + examId + "/grading/subjective/questions", teacher), 200).get("data");
        assertEquals(1, subjectiveQuestions.size());
        assertEquals(imageShortId, subjectiveQuestions.get(0).get("questionId").asLong());
        assertEquals(IMAGE_URL, subjectiveQuestions.get(0).get("imageUrl").asText());

        // 简答批改（部分分 3/6 → 必进错题本）→ 汇总发布 → 查分
        long studentId = jwtUtil.parseAccessToken(student).getId();
        saveSubjective(teacher, examId, studentId, imageShortId, "3.0", 0, "部分要点");
        perform(jsonPost("/api/exams/" + examId + "/scores/summarize", teacher, null), 200);
        assertEquals(Exam.STATUS_GRADED, examMapper.selectById(examId).getStatus());
        ObjectNode publishBody = objectMapper.createObjectNode();
        publishBody.putArray("examIds").add(examId);
        perform(jsonPost("/api/scores/publish", teacher, objectMapper.writeValueAsString(publishBody)), 200);

        // 逐题回顾：带图题携带 imageUrl，无图题为 null
        JsonNode review = perform(jsonGet("/api/scores/my/exams/" + examId + "/review", student), 200).get("data");
        Map<Long, JsonNode> reviewByQuestion = new HashMap<>();
        review.get("questions").forEach(q -> reviewByQuestion.put(q.get("questionId").asLong(), q));
        assertEquals(IMAGE_URL, reviewByQuestion.get(imageShortId).get("imageUrl").asText(), "逐题回顾应携带题干图");
        assertTrue(isNullish(reviewByQuestion.get(plainSingleId).get("imageUrl")), "回顾无图题零回归");

        // 错题本：带图简答题（部分分 < 满分）携带 imageUrl；无图单选（答错）imageUrl 为 null 零回归
        JsonNode wrong = perform(jsonGet("/api/scores/my/wrong-questions?page=1&size=10", student), 200).get("data");
        boolean foundImage = false;
        boolean foundPlain = false;
        for (JsonNode group : wrong.get("groups")) {
            for (JsonNode item : group.get("wrongQuestions")) {
                if (item.get("questionId").asLong() == imageShortId) {
                    foundImage = true;
                    assertEquals(IMAGE_URL, item.get("imageUrl").asText(), "错题本应携带题干图");
                }
                if (item.get("questionId").asLong() == plainSingleId) {
                    foundPlain = true;
                    assertTrue(isNullish(item.get("imageUrl")), "无图错题条目 imageUrl 为 null（零回归）");
                }
            }
        }
        assertTrue(foundImage, "带图简答题（3/6 分）应进错题本");
        assertTrue(foundPlain, "单选答错（A vs B）应进错题本");
    }

    // ==================== 辅助 ====================

    /** Jackson 响应按全局 non_null 配置省略 null 字段：get() 可能返回 null，统一按「无图」判定。 */
    private static boolean isNullish(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    /** 建题带图（imageUrl 可空）：返回题目 ID。 */
    private long createImageQuestion(String token, int type, String content, String answer,
                                     String imageUrl) throws Exception {
        ObjectNode body = createBody(type, content, answer);
        if (imageUrl != null) {
            body.put("imageUrl", imageUrl);
        }
        return perform(jsonPost("/api/questions", token, objectMapper.writeValueAsString(body)), 200)
                .get("data").get("id").asLong();
    }

    private ObjectNode createBody(int type, String content, String answer) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", type);
        body.put("content", content);
        body.put("correctAnswer", answer);
        body.put("score", 6);
        body.put("difficulty", 1);
        if (type == 1) {
            var arr = body.putArray("choices");
            arr.add("选项A");
            arr.add("选项B");
        }
        return body;
    }

    private void addPaperQuestion(String teacher, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                objectMapper.writeValueAsString(body)), 200);
    }

    private long createExam(String teacher, long paperId, Long classId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("图片考试"));
        body.put("paperId", paperId);
        if (classId != null) {
            body.put("classId", classId);
        }
        body.put("startTime", LocalDateTime.now().minusMinutes(1).toString());
        body.put("endTime", LocalDateTime.now().plusHours(2).toString());
        body.put("durationMinutes", 30);
        return perform(jsonPost("/api/exams", teacher, objectMapper.writeValueAsString(body)), 200)
                .get("data").get("id").asLong();
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
            submitConsumer.onBatch(java.util.List.of(amqp), mock(com.rabbitmq.client.Channel.class));
        }
    }
}
