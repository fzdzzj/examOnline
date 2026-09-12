package com.exam.datasource;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.exam.auth.service.JwtUtil;
import com.exam.config.ReadYourWriteMark;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读写分离集成测试（add-performance-deepening task4）：
 * 验证：
 * <ul>
 *   <li>@DS("slave") 静态路由生效——默认（无写标记）读走从库组；</li>
 *   <li>读己之写——写后短窗口内 @DS("slave") 读被强制转主库，窗口/清除后恢复从库；</li>
 *   <li>窗口过期——标记时间戳超过窗口即失效（读回从库）；</li>
 *   <li>端到端读己之写——学生刚交卷(写)随即拉取答卷(读)能读到最新已交卷状态。
 *       （主从在 test 共用同一 H2，路由正确性以上面 SlaveReadProbe 的 peek 断言为准）</li>
 * </ul>
 * 嵌套 {@code @TestConfiguration} 注册 @DS("slave") 探针，由 @SpringBootTest 自动探测。
 */
class ReaddWriteSoSeperationIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ReadYourWriteMark mark;
    @Autowired
    private SlaveReadProbe slaveProbe;
    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private ExamSubmissionMapper submissionMapper;
    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 每个用例前清空读己之写标记：{@link #submitThenImmediateReadSeesLatestStatus()} 里真实交卷
     * 会调用单例 {@code ReadYourWriteMark#mark()} 在当前线程留下约 5s 时间戳，若不清空会泄漏到
     * 同线程的后续用例（其"默认走从库"断言会被切面误转主库而失败）。
     */
    @org.junit.jupiter.api.BeforeEach
    void clearReadYourWriteMark() {
        mark.clear();
    }

    /** 注册 @DS("slave") 的探针 bean，方法内返回当前路由栈顶，用于断言路由组。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class ProbeConfig {
        @org.springframework.context.annotation.Bean
        SlaveReadProbe slaveReadProbe() {
            return new SlaveReadProbe();
        }
    }

    /** @DS("slave") 的方法：读路由栈顶，验证静态路由与读己之写强制转主库。 */
    public static class SlaveReadProbe {
        @DS("slave")
        public String currentDs() {
            return DynamicDataSourceContextHolder.peek();
        }
    }

    // ==================== @DS 静态路由 + 读己之写强制转主库 ====================

    @Test
    void slaveReadRoutesToMasterWithinWriteWindow() {
        // 无写标记：@DS("slave") 静态路由生效，本次读走从库
        assertEquals(ReadWriteConstants.SLAVE, slaveProbe.currentDs(),
                "非强一致读默认应路由到从库");

        // 写后短窗口：同线程标记后，@DS("slave") 读被强制转主库（读己之写）
        mark.mark();
        assertEquals(ReadWriteConstants.MASTER, slaveProbe.currentDs(),
                "写后窗口内的从库读应被强制转到主库（避免主从延迟读旧）");

        // 窗口清标记：恢复从库
        mark.clear();
        assertEquals(ReadWriteConstants.SLAVE, slaveProbe.currentDs(),
                "窗口过期后读应回到从库");
    }

    @Test
    void writeWindowExpiresAndReadsReturnToSlave() throws Exception {
        // 使用 80ms 的短窗口标记：mark 后立即生效，sleep 越过窗口后失效
        ReadYourWriteMark shortWindow = new ReadYourWriteMark(80);
        shortWindow.mark();
        assertTrue(shortWindow.isWindowActive(), "标记后应处于写后窗口");
        Thread.sleep(150);
        assertFalse(shortWindow.isWindowActive(), "越过窗口后标记应失效");
        // 失效后标记被清除，读回从库
        assertEquals(ReadWriteConstants.SLAVE, slaveProbe.currentDs());
    }

    // ==================== 端到端读己之写：刚交卷立即能读到最新 ====================

    /** 学生 enter → 交卷(写) → 随即拉取答卷(读)：立即读到已交卷最新状态。 */
    @Test
    void submitThenImmediateReadSeesLatestStatus() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher);
        long studentId = jwtUtil.parseAccessToken(student).getId();

        // 进入考试（创建答卷：进行中）
        JsonNode enterData = perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null), 200)
                .get("data");
        long q1 = enterData.get("questions").get(0).get("questionId").asLong();
        assertEquals(ExamSubmission.STATUS_IN_PROGRESS, enterData.get("status").asInt());

        // 交卷（写操作，成功后当前线程置读己之写标记）
        ObjectNode request = objectMapper.createObjectNode();
        request.set("answers", answersJson(q1, "A"));
        request.put("submitType", "MANUAL");
        JsonNode ack = perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(request)), 200).get("data");
        assertEquals(ExamSubmission.STATUS_SUBMITTED, ack.get("status").asInt());

        // 立即（同线程，窗口内）拉取答卷：读到的必须是最新"已交卷"状态 —— 读己之写
        JsonNode paper = perform(jsonGet("/api/exam-taking/exams/" + examId + "/paper", student), 200)
                .get("data");
        assertEquals(ExamSubmission.STATUS_SUBMITTED, paper.get("status").asInt(),
                "刚交卷立即读答卷必须读到已交卷（若误走从库，主从延迟会读到进行中旧状态）");
        assertEquals(0, paper.get("questions").size());

        // 答卷状态持久化为已交卷（写侧最终落库）
        assertEquals(ExamSubmission.STATUS_SUBMITTED,
                submissionMapper.selectByExamStudent(examId, studentId).getStatus());
    }

    // ==================== 造数（复用 taking 集成测试同款） ====================

    private long preparedInProgressExam(String teacher) throws Exception {
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        return examId;
    }

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

    private long createExam(String token, long paperId, LocalDateTime start,
                            LocalDateTime end, int duration) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        body.put("startTime", start.toString());
        body.put("endTime", end.toString());
        body.put("durationMinutes", duration);
        JsonNode data = perform(jsonPost("/api/exams", token,
                objectMapper.writeValueAsString(body)), 200).get("data");
        return data.get("id").asLong();
    }

    private ObjectNode answersJson(long questionId, String answer) {
        ObjectNode answers = objectMapper.createObjectNode();
        answers.put(String.valueOf(questionId), answer);
        return answers;
    }

    /** 路由常量（与 main 侧 ReadYourWriteRouter 取值保持一致）。 */
    private static final class ReadWriteConstants {
        static final String MASTER = "master";
        static final String SLAVE = "slave";
    }
}