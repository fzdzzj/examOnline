package com.exam.exam;

import com.exam.auth.service.JwtUtil;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.mapper.ExamReservationMapper;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 考试预约集成测试（add-exam-reservation）：
 * <ul>
 *   <li>预约/取消窗口边界——开考前可约可取消，开考后（状态机→进行中）拒约拒取消；</li>
 *   <li>未预约不可进入——预约制考试在进行中，未预约学生进入被 403 拦截、已预约正常进入；</li>
 *   <li>预约资格与准入口径同源——外班学生拒约；</li>
 *   <li>重约幂等——取消后重约可再次成功（唯一索引）；</li>
 *   <li>列表字段下发与 canEnter 联动——reservationRequired/reserved 下发，未预约进行中也 cannot-enter；</li>
 *   <li>预约开关 status=0 守卫——开考后修改 reservationRequired 被 400 拒绝。</li>
 * </ul>
 */
class ExamReservationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private ExamReservationMapper reservationMapper;
    @Autowired
    private ExamMapper examMapper;
    @Autowired
    private JwtUtil jwtUtil;

    // ==================== 造数 ====================

    private long addPaperQuestion(String token, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
        return questionId;
    }

    /** 组一卷三题（15 分），返回试卷 ID。 */
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

    /** 建一场测试考试；reservationRequired 控制预约开关。 */
    private long createExam(String teacher, long paperId, Long classId,
                            LocalDateTime start, LocalDateTime end, int duration,
                            Boolean reservationRequired) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        if (classId != null) {
            body.put("classId", classId);
        }
        body.put("startTime", start.toString());
        body.put("endTime", end.toString());
        body.put("durationMinutes", duration);
        if (reservationRequired != null) {
            body.put("reservationRequired", reservationRequired);
        }
        return perform(jsonPost("/api/exams", teacher, objectMapper.writeValueAsString(body)), 200)
                .get("data").get("id").asLong();
    }

    /** 推进到"进行中"（发布后 autoAdvance 到点自动开考）；跨测试共享 H2 下避免时序竞态，拉到进行中为止。 */
    private void toInProgress(String teacher, long examId) throws Exception {
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        ensureInProgress(examId);
    }

    /** 反复 autoAdvance 直到考试进入"进行中"（防 @Scheduled 与测试显式推进的时序竞态）。 */
    private void ensureInProgress(long examId) {
        int guard = 0;
        while (guard++ < 5) {
            stateMachineService.autoAdvance();
            Exam exam = examMapper.selectById(examId);
            if (exam != null && exam.getStatus() != null
                    && exam.getStatus() == Exam.STATUS_IN_PROGRESS) {
                return;
            }
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("考试未能推进到进行中: examId=" + examId
                + " status=" + (examMapper.selectById(examId) == null ? "null"
                : examMapper.selectById(examId).getStatus()));
    }

    /** 建一场"进行中"的预约制考试，学生先预约。 */
    private long reserveableInProgressExam(String teacher, String student, long classId,
                                           LocalDateTime start, LocalDateTime end) throws Exception {
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId, classId, start, end, 30, true);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        // 开考前预约
        perform(jsonPost("/api/exam-reservations/" + examId, student, null), 200);
        stateMachineService.autoAdvance();
        return examId;
    }

    private int reservationCount(long examId, Long studentId) {
        return Math.toIntExact(reservationMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.exam.exam.entity.ExamReservation>()
                        .eq(com.exam.exam.entity.ExamReservation::getExamId, examId)
                        .eq(com.exam.exam.entity.ExamReservation::getStudentId, studentId)));
    }

    // ==================== 预约窗口与取消 ====================

    @Test
    void reserveWindowOpenBeforeStartAndCancelAllowed() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long studentId = jwtUtil.parseAccessToken(student).getId();
        long classId = createClassForExam(teacher, student);
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId, classId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(3), 30, true);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);

        // 开考前可预约
        perform(jsonPost("/api/exam-reservations/" + examId, student, null), 200);
        assertEquals(1, reservationCount(examId, studentId));

        // 取消预约（物理删除）
        perform(jsonDelete("/api/exam-reservations/" + examId, student), 200);
        assertEquals(0, reservationCount(examId, studentId));

        // 重约：取消后唯一索引不产生残留，再次预约成功
        perform(jsonPost("/api/exam-reservations/" + examId, student, null), 200);
        assertEquals(1, reservationCount(examId, studentId));
    }

    @Test
    void reservationLockedAfterExamStarted() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId, classId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30, true);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        ensureInProgress(examId);

        // 开考后不可预约、不可取消
        perform(jsonPost("/api/exam-reservations/" + examId, student, null), 400);
        perform(jsonDelete("/api/exam-reservations/" + examId, student), 400);
    }

    // ==================== 未预约不可进入 ====================

    @Test
    void unreservedStudentForbiddenToEnterReservedExam() throws Exception {
        String teacher = registerTeacher();
        String reserved = registerStudent();
        String unReserved = registerStudent();
        long classId = createClassForExam(teacher, reserved, unReserved);
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId, classId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30, true);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        // 仅 reserved 学生预约（开考前）
        perform(jsonPost("/api/exam-reservations/" + examId, reserved, null), 200);
        // 推进到"进行中"：开考锁定预约名单
        ensureInProgress(examId);

        // 已预约正常进入
        perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", reserved, null), 200);
        // 未预约被 403 拦截
        JsonNode rejected = perform(jsonPost("/api/exam-taking/exams/" + examId + "/enter", unReserved, null), 403);
        assertEquals(403, rejected.get("code").asInt());
        assertTrue(rejected.get("message").asText().contains("尚未预约"), "未预约进入应给出明确提示");
    }

    // ==================== 资格同源（外班拒约） ====================

    @Test
    void outsiderForbiddenToReserve() throws Exception {
        String teacher = registerTeacher();
        String member = registerStudent();
        String outsider = registerStudent();
        long classId = createClassForExam(teacher, member);
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId, classId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(3), 30, true);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);

        // 外班学生拒约（预约资格与进入准入同源）
        JsonNode rejected = perform(jsonPost("/api/exam-reservations/" + examId, outsider, null), 403);
        assertEquals(403, rejected.get("code").asInt());
        long outsiderId = jwtUtil.parseAccessToken(outsider).getId();
        assertNull(reservationMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.exam.exam.entity.ExamReservation>()
                        .eq(com.exam.exam.entity.ExamReservation::getExamId, examId)
                        .eq(com.exam.exam.entity.ExamReservation::getStudentId, outsiderId)),
                "外班学生不得留下预约行");

        // 班内学生可预约
        perform(jsonPost("/api/exam-reservations/" + examId, member, null), 200);
    }

    // ==================== 列表字段下发与 canEnter 联动 ====================

    @Test
    void examListCarriesReservationFieldsAndCanEnterLinkage() throws Exception {
        String teacher = registerTeacher();
        String reserved = registerStudent();
        String unReserved = registerStudent();
        long classId = createClassForExam(teacher, reserved, unReserved);
        long paperId = preparePaper(teacher);
        long reservedExam = createExam(teacher, paperId, classId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30, true);
        perform(jsonPost("/api/exams/" + reservedExam + "/publish", teacher, null), 200);
        perform(jsonPost("/api/exam-reservations/" + reservedExam, reserved, null), 200);
        ensureInProgress(reservedExam);

        // 已预约学生在进行中的预约制考试：canEnter=true，reserved=true，reservationRequired=true
        JsonNode reservedList = perform(jsonGet("/api/exam-taking/exams", reserved), 200).get("data");
        JsonNode reservedItem = itemOf(reservedList, reservedExam);
        assertTrue(reservedItem.get("reservationRequired").asBoolean());
        assertTrue(reservedItem.get("reserved").asBoolean());
        assertTrue(reservedItem.get("canEnter").asBoolean());

        // 未预约学生在进行中的预约制考试：reserved=false 且 canEnter=false（即便进行中）
        JsonNode unReservedList = perform(jsonGet("/api/exam-taking/exams", unReserved), 200).get("data");
        JsonNode unReservedItem = itemOf(unReservedList, reservedExam);
        assertTrue(unReservedItem.get("reservationRequired").asBoolean());
        assertFalse(unReservedItem.get("reserved").asBoolean());
        assertFalse(unReservedItem.get("canEnter").asBoolean());
    }

    @Test
    void nonReservationExamCarriesFalseReservationFlags() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);
        long paperId = preparePaper(teacher);
        long normalExam = createExam(teacher, paperId, classId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30, false);
        perform(jsonPost("/api/exams/" + normalExam + "/publish", teacher, null), 200);
        ensureInProgress(normalExam);

        JsonNode list = perform(jsonGet("/api/exam-taking/exams", student), 200).get("data");
        JsonNode item = itemOf(list, normalExam);
        assertFalse(item.get("reservationRequired").asBoolean());
        assertFalse(item.get("reserved").asBoolean());
        assertTrue(item.get("canEnter").asBoolean(), "普通考试进行中未预约不拦，canEnter 维持 true");
    }

    // ==================== 预约开关 status=0 守卫 ====================

    @Test
    void reservationSwitchImmutableAfterStart() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId, classId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(2), 30, false);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        ensureInProgress(examId);   // 开考后（进行中）

        // 开考后改开关被拒
        ObjectNode body = objectMapper.createObjectNode();
        body.put("reservationRequired", true);
        JsonNode rejected = perform(jsonPut("/api/exams/" + examId, teacher,
                objectMapper.writeValueAsString(body)), 400);
        assertTrue(rejected.get("message").asText().contains("预约开关"), "开考后改开关应被拒绝");
    }

    @Test
    void reservationSwitchEditableBeforeStart() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId, classId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(3), 30, false);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);

        // 未开始（已发布但 status=0）：仅改开关允许
        ObjectNode body = objectMapper.createObjectNode();
        body.put("reservationRequired", true);
        JsonNode updated = perform(jsonPut("/api/exams/" + examId, teacher,
                objectMapper.writeValueAsString(body)), 200).get("data");
        assertEquals(1, updated.get("reservationRequired").asInt());
    }

    // ==================== 非预约制考试前置门禁（harden-exam-paper-and-practice-boundaries） ====================

    @Test
    void reserveAndCancelRejectNonReservationExam() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long classId = createClassForExam(teacher, student);
        long paperId = preparePaper(teacher);
        // reservationRequired=false 的普通考试
        long normalExam = createExam(teacher, paperId, classId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(3), 30, false);
        perform(jsonPost("/api/exams/" + normalExam + "/publish", teacher, null), 200);

        // 预约被 400 拦截，文案明确"非预约制"
        JsonNode reserveRejected = perform(jsonPost("/api/exam-reservations/" + normalExam, student, null), 400);
        assertTrue(reserveRejected.get("message").asText().contains("非预约制"),
                "非预约制考试预约应被拒，实际: " + reserveRejected.get("message").asText());

        // 取消预约同样被 400 拦截，文案明确"非预约制"
        JsonNode cancelRejected = perform(jsonDelete("/api/exam-reservations/" + normalExam, student), 400);
        assertTrue(cancelRejected.get("message").asText().contains("非预约制"),
                "非预约制考试取消预约应被拒，实际: " + cancelRejected.get("message").asText());
    }

    // ==================== 辅助 ====================

    private JsonNode itemOf(JsonNode list, long examId) {
        for (JsonNode item : list) {
            if (item.get("examId").asLong() == examId) {
                return item;
            }
        }
        throw new AssertionError("列表缺少考试 " + examId);
    }
}