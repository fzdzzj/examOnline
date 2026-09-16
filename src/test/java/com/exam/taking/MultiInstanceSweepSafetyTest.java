package com.exam.taking;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.auth.service.JwtUtil;
import com.exam.common.BusinessException;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamAbsence;
import com.exam.exam.mapper.ExamAbsenceMapper;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamService;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.submission.mq.ExamSubmitConsumer;
import com.exam.support.IntegrationTestBase;
import com.exam.taking.service.ExamSweepService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * 多实例扫描安全证据（add-multi-instance-sweep-safety）：
 * 用两线程同构模拟双实例并发执行同一轮扫描，证明下游幂等足以保证正确性。
 */
class MultiInstanceSweepSafetyTest extends IntegrationTestBase {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private ExamService examService;
    @Autowired
    private ExamSweepService sweepService;
    @Autowired
    private ExamSubmissionMapper submissionMapper;
    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private ExamAbsenceMapper absenceMapper;
    @Autowired
    private ExamSubmitConsumer submitConsumer;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private MeterRegistry meterRegistry;

    // ==================== 用例 ====================

    /** 双实例并发 sweep：同一份超时答卷只强制交卷一次，MQ 只发 1 条。 */
    @Test
    void concurrentSweepForcesSubmitOnce() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher);
        long studentId = studentIdOf(student);
        enter(student, examId, 200);

        submissionMapper.update(null, Wrappers.<ExamSubmission>lambdaUpdate()
                .eq(ExamSubmission::getExamId, examId)
                .eq(ExamSubmission::getStudentId, studentId)
                .set(ExamSubmission::getDeadlineTime, LocalDateTime.now().minusMinutes(1)));

        reset(rabbitTemplate);
        runTwoThreads(() -> {
            sweepService.sweep();
            return null;
        });

        List<ExamSubmission> rows = submissionMapper.selectList(Wrappers.<ExamSubmission>lambdaQuery()
                .eq(ExamSubmission::getExamId, examId)
                .eq(ExamSubmission::getStudentId, studentId));
        assertEquals(1, rows.size(), "该 (examId, studentId) 应只有一条答卷");
        assertEquals(ExamSubmission.STATUS_SUBMITTED, rows.get(0).getStatus());
        assertNotNull(rows.get(0).getSubmitType());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(anyString(), anyString(), captor.capture());
        List<SubmitMessage> mine = captor.getAllValues().stream()
                .map(raw -> (SubmitMessage) raw)
                .filter(m -> m.getExamId().equals(examId) && m.getStudentId().equals(studentId))
                .toList();
        // sweep() = forceSubmit + republish：CAS 赢家只发 1 条交卷消息；随后 answers 仍为 NULL，
        // 两线程对账补发最多再各发 1 条 → 同 (examId,studentId) 总计 1..3 条，且 submissionId 唯一。
        assertTrue(mine.size() >= 1 && mine.size() <= 3,
                "强制交卷单发 + 并发补发至多 3 条，实际=" + mine.size());
        assertEquals(1L, mine.stream().map(SubmitMessage::getSubmissionId).distinct().count(),
                "同答卷的消息应指向同一 submissionId（强制交卷只成功一次）");
    }

    /** 双实例并发补发：消息可重复投递，消费端 casFillAnswers 只写一次，答案与草稿一致。 */
    @Test
    void concurrentSweepRepublishesWithoutDuplicating() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long examId = preparedInProgressExam(teacher);
        long studentId = studentIdOf(student);
        JsonNode enterData = enter(student, examId, 200);
        long q1 = questionIds(enterData).get(0);

        ObjectNode save = objectMapper.createObjectNode();
        save.put("version", 1);
        ObjectNode answers = objectMapper.createObjectNode();
        answers.put(String.valueOf(q1), "B");
        save.set("answers", answers);
        perform(jsonPut("/api/exam-taking/exams/" + examId + "/draft", student,
                objectMapper.writeValueAsString(save)), 200);

        ObjectNode request = objectMapper.createObjectNode();
        request.set("answers", answers);
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/submit", student,
                objectMapper.writeValueAsString(request)), 200);

        ExamSubmission before = submissionMapper.selectByExamStudent(examId, studentId);
        assertEquals(ExamSubmission.STATUS_SUBMITTED, before.getStatus());
        assertTrue(before.getAnswers() == null || before.getAnswers().isBlank(),
                "MQ 未消费时 answers 应为 NULL");

        reset(rabbitTemplate);
        runTwoThreads(() -> {
            sweepService.sweep();
            return null;
        });

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(anyString(), anyString(), captor.capture());
        List<SubmitMessage> mine = captor.getAllValues().stream()
                .map(raw -> (SubmitMessage) raw)
                .filter(m -> m.getExamId().equals(examId) && m.getStudentId().equals(studentId))
                .toList();
        assertTrue(mine.size() >= 1, "补发至少投递 1 条本答卷消息");

        // 驱动消费者（含可能的重复投递）：casFillAnswers 幂等，answers 只写一次。
        // 计数用增量：H2/Spring 上下文跨用例共享，禁止绝对值断言。
        double sweepDupBefore = sweepDuplicateDetectedCount();
        for (SubmitMessage message : mine) {
            Message amqp = new Message(objectMapper.writeValueAsBytes(message), new MessageProperties());
            amqp.getMessageProperties().setDeliveryTag(1L);
            submitConsumer.onBatch(List.of(amqp), mock(com.rabbitmq.client.Channel.class));
        }
        assertTrue(sweepDuplicateDetectedCount() > sweepDupBefore,
                "重复投递消费后 task=sweep 重复扫描计数应增加（filled==0 埋点）");

        ExamSubmission after = submissionMapper.selectByExamStudent(examId, studentId);
        assertNotNull(after.getAnswers());
        assertTrue(after.getAnswers().contains(String.valueOf(q1)), "最终 answers 应含草稿题目");
        assertTrue(after.getAnswers().contains("B"), "最终 answers 应与草稿一致");
    }

    /** 一线程 force-end + 一线程 autoAdvance：缺考每人恰好一行。 */
    @Test
    void concurrentEndMarksAbsenceOnce() throws Exception {
        String teacher = registerTeacher();
        String studentA = registerStudent();
        String studentB = registerStudent();
        long aId = studentIdOf(studentA);
        long bId = studentIdOf(studentB);
        long classId = createClassWithStudents(teacher, aId, bId);
        long examId = inProgressExam(teacher, classId);
        enter(studentA, examId, 200); // A 有答卷；B 缺考

        // 让 autoAdvance 也能命中进行中→已结束
        examMapper.update(null, Wrappers.<Exam>lambdaUpdate()
                .eq(Exam::getId, examId)
                .set(Exam::getEndTime, LocalDateTime.now().minusMinutes(1)));

        LoginUser teacherUser = jwtUtil.parseAccessToken(teacher);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger forceEndStatus = new AtomicInteger(-1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> forceEndFuture = pool.submit(() -> {
                SecurityUtil.set(teacherUser);
                ready.countDown();
                go.await();
                try {
                    examService.forceEnd(examId);
                    forceEndStatus.set(200);
                } catch (BusinessException e) {
                    forceEndStatus.set(e.getHttpStatus());
                } finally {
                    SecurityUtil.clear();
                }
                return null;
            });
            Future<?> autoAdvanceFuture = pool.submit(() -> {
                ready.countDown();
                go.await();
                stateMachineService.autoAdvance();
                return null;
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            forceEndFuture.get(15, TimeUnit.SECONDS);
            autoAdvanceFuture.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(Exam.STATUS_ENDED, examMapper.selectById(examId).getStatus());
        assertTrue(forceEndStatus.get() == 200 || forceEndStatus.get() == 400 || forceEndStatus.get() == 409,
                "force-end 成功或因竞态失败均可；实际=" + forceEndStatus.get());

        List<ExamAbsence> absences = absenceMapper.selectList(Wrappers.<ExamAbsence>lambdaQuery()
                .eq(ExamAbsence::getExamId, examId));
        assertEquals(1, absences.size(), "缺考应恰好一人（B）");
        assertEquals(bId, absences.get(0).getStudentId());
        // 每个 student 恰好一行（唯一索引 + INSERT IGNORE）
        long distinctStudents = absences.stream().map(ExamAbsence::getStudentId).distinct().count();
        assertEquals(absences.size(), distinctStudents);
    }

    /** 双实例并发 autoAdvance：状态只迁一次，version 只 +1。 */
    @Test
    void concurrentAutoAdvanceAdvancesOnce() throws Exception {
        String teacher = registerTeacher();
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId, null,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        Exam before = examMapper.selectById(examId);
        assertEquals(Exam.STATUS_IN_PROGRESS, before.getStatus());
        int versionBefore = before.getVersion();

        examMapper.update(null, Wrappers.<Exam>lambdaUpdate()
                .eq(Exam::getId, examId)
                .set(Exam::getEndTime, LocalDateTime.now().minusMinutes(1)));
        // 刷新 version（update 不改 version）
        before = examMapper.selectById(examId);

        runTwoThreads(() -> {
            stateMachineService.autoAdvance();
            return null;
        });

        Exam after = examMapper.selectById(examId);
        assertEquals(Exam.STATUS_ENDED, after.getStatus());
        assertEquals(versionBefore + 1, after.getVersion(), "version 应只 +1（另一路 CAS 0 行）");
    }

    // ==================== 造数 / 工具 ====================

    private void runTwoThreads(Callable<Void> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    task.call();
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "两线程应就绪");
            go.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private long addPaperQuestion(String token, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
        return questionId;
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

    private long preparedInProgressExam(String teacher) throws Exception {
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId, null,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        return examId;
    }

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

    private long inProgressExam(String teacher, Long classId) throws Exception {
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId, classId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        assertEquals(Exam.STATUS_IN_PROGRESS, examMapper.selectById(examId).getStatus());
        return examId;
    }

    private JsonNode enter(String student, long examId, int expectedStatus) throws Exception {
        return perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", student, null),
                expectedStatus).get("data");
    }

    private List<Long> questionIds(JsonNode enterData) {
        List<Long> ids = new ArrayList<>();
        enterData.get("questions").forEach(q -> ids.add(q.get("questionId").asLong()));
        return ids;
    }

    /** 读 exam.sweep.duplicate_detected{task=sweep} 当前值；尚未注册时视为 0。 */
    private double sweepDuplicateDetectedCount() {
        Counter counter = meterRegistry.find("exam.sweep.duplicate_detected")
                .tag("task", "sweep")
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
    private long studentIdOf(String token) {
        return jwtUtil.parseAccessToken(token).getId();
    }
}
