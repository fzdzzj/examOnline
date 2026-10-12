package com.exam.exam;

import com.exam.auth.service.JwtUtil;
import com.exam.exam.entity.ExamAbsence;
import com.exam.exam.mapper.ExamAbsenceMapper;
import com.exam.exam.mapper.ExamReservationMapper;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.message.entity.UserMessage;
import com.exam.message.mapper.UserMessageMapper;
import com.exam.message.service.ReservationRemindService;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开考提醒投递 + 学生消息中心集成测试（add-exam-reservation）：
 * <ul>
 *   <li>行级幂等构造性实证——同一考试两次扫描仅投递一条消息、reminded_time 只回填一次；</li>
 *   <li>提前量窗口边界——窗口外（距开考 > 提前量）不投、窗口内必投；</li>
 *   <li>消息端点按 user_id 隔离、未读数联动、单条标记已读；</li>
 *   <li>缺席名单分流——预约制考试按预约名单标记缺考，普通考试按班级名单。</li>
 * </ul>
 */
class ExamReservationRemindIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ExamStateMachineService stateMachineService;
    @Autowired
    private ExamReservationMapper reservationMapper;
    @Autowired
    private UserMessageMapper messageMapper;
    @Autowired
    private ExamAbsenceMapper absenceMapper;
    @Autowired
    private ReservationRemindService remindService;
    @Autowired
    private JdbcTemplate jdbc;
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

    private long createReservationExam(String teacher, long paperId, Long classId,
                                       LocalDateTime start, LocalDateTime end) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        if (classId != null) {
            body.put("classId", classId);
        }
        body.put("startTime", start.toString());
        body.put("endTime", end.toString());
        body.put("durationMinutes", 30);
        body.put("reservationRequired", true);
        return perform(jsonPost("/api/exams", teacher, objectMapper.writeValueAsString(body)), 200)
                .get("data").get("id").asLong();
    }

    private long reserve(String teacher, String student, long classId,
                         LocalDateTime start, LocalDateTime end) throws Exception {
        long paperId = preparePaper(teacher);
        long examId = createReservationExam(teacher, paperId, classId, start, end);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        perform(jsonPost("/api/exam-reservations/" + examId, student, null), 200);
        return examId;
    }

    private long messageCountFor(long userId, Long examId) {
        return messageMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .eq(examId != null, UserMessage::getExamId, examId));
    }

    // ==================== 提前量与行级幂等 ====================

    @Test
    void remindDeliversExactlyOncePerReservation() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long studentId = jwtUtil.parseAccessToken(student).getId();
        long classId = createClassForExam(teacher, student);
        // 开考在即（<= 提前量 30 分钟）：窗内必投
        long examId = reserve(teacher, student, classId,
                LocalDateTime.now().plusMinutes(10), LocalDateTime.now().plusHours(2));

        remindService.remind();
        // 两条预约正好？本场仅 1 位学生预约 → 投 1 条开考提醒
        assertEquals(1, messageCountFor(studentId, examId));

        // 第二次扫描：reminded_time 已回填，不再投递（行级幂等）
        remindService.remind();
        assertEquals(1, messageCountFor(studentId, examId), "重复扫描不得重复投递");

        // 消息中心可见此条提醒
        JsonNode list = perform(jsonGet("/api/messages", student), 200).get("data");
        long found = 0;
        for (JsonNode m : list) {
            if (m.get("examId").asLong() == examId) {
                found++;
                assertEquals("开考提醒", m.get("title").asText());
                assertEquals(0, m.get("readFlag").asInt());
            }
        }
        assertEquals(1, found, "消息中心应能读到开考提醒");
    }

    @Test
    void remindNotDeliveredOutsideWindow() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long studentId = jwtUtil.parseAccessToken(student).getId();
        long classId = createClassForExam(teacher, student);
        // 开考还很远（> 提前量 30 分钟）：窗内不投
        long examId = reserve(teacher, student, classId,
                LocalDateTime.now().plusHours(3), LocalDateTime.now().plusHours(5));

        remindService.remind();
        assertEquals(0, messageCountFor(studentId, examId), "窗口外（距开考 > 提前量）不应投递");
    }

    // ==================== 消息端点隔离与已读 ====================

    @Test
    void messageEndpointsAreUserIsolatedAndReadFlagWorks() throws Exception {
        String teacher = registerTeacher();
        String studentA = registerStudent();
        String studentB = registerStudent();
        long aId = jwtUtil.parseAccessToken(studentA).getId();
        long bId = jwtUtil.parseAccessToken(studentB).getId();
        long classId = createClassForExam(teacher, studentA, studentB);
        LocalDateTime soon = LocalDateTime.now().plusMinutes(10);

        long examA = reserve(teacher, studentA, classId, soon, soon.plusHours(2));
        long examB = reserve(teacher, studentB, classId, soon, soon.plusHours(3));
        remindService.remind();

        // A 只看到自己的 1 条（来自 examA），B 只看到自己的 1 条（来自 examB）
        JsonNode aList = perform(jsonGet("/api/messages", studentA), 200).get("data");
        JsonNode bList = perform(jsonGet("/api/messages", studentB), 200).get("data");
        long aExamA = countExamInList(aList, examA);
        long aExamB = countExamInList(aList, examB);
        assertEquals(1, aExamA);
        assertEquals(0, aExamB, "A 不得看到 B 的消息");
        assertEquals(1, countExamInList(bList, examB));

        // 未读数：双方各 1
        assertEquals(1, perform(jsonGet("/api/messages/unread-count", studentA), 200).get("data").asLong());
        assertEquals(1, perform(jsonGet("/api/messages/unread-count", studentB), 200).get("data").asLong());

        // A 标记自己那条已读 → 未读数归 0；B 未读数不受影响
        Long aMsgId = aList.get(0).get("id").asLong();
        perform(jsonPut("/api/messages/" + aMsgId + "/read", studentA, null), 200);
        assertEquals(0, perform(jsonGet("/api/messages/unread-count", studentA), 200).get("data").asLong());
        assertEquals(1, perform(jsonGet("/api/messages/unread-count", studentB), 200).get("data").asLong());

        // 未登录访问消息端点 → 401
        perform(jsonGet("/api/messages", null), 401);
    }

    // ==================== 缺席名单分流 ====================

    @Test
    void absenceMarkedFromReservationRoster() throws Exception {
        String teacher = registerTeacher();
        String reserver = registerStudent();     // 预约且不答卷 → 缺考
        String guest = registerStudent();        // 班内但未预约 → 不计入应考名单
        long reserverId = jwtUtil.parseAccessToken(reserver).getId();
        long guestId = jwtUtil.parseAccessToken(guest).getId();
        long classId = createClassForExam(teacher, reserver, guest);
        long paperId = preparePaper(teacher);
        long examId = createReservationExam(teacher, paperId, classId,
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusMinutes(40));
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        perform(jsonPost("/api/exam-reservations/" + examId, reserver, null), 200);
        stateMachineService.autoAdvance();   // 开考

        // 拨快结束时间并推进状态机 → 触发缺考标记（锚定"进行中→已结束"迁移）
        jdbc.update("UPDATE exams SET end_time = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), examId);
        stateMachineService.autoAdvance();

        // 应考名单 = 预约名单（仅 reserver），reserver 无答卷 → 缺考仅 reserver；
        // guest 虽在班级但未预约，不计入应考名单 → 不产生缺考行
        assertEquals(1, absenceCount(examId, reserverId), "预约且未答卷者应被标记缺考");
        assertEquals(0, absenceCount(examId, guestId), "未预约的班内学生不进入应考名单");
    }

    @Test
    void absenceForNormalExamUsesClassRosterStill() throws Exception {
        // 普通考试（未开预约）应考名单仍为班级名单：班内未答卷者被标记缺考
        String teacher = registerTeacher();
        String student = registerStudent();
        long studentId = jwtUtil.parseAccessToken(student).getId();
        long classId = createClassForExam(teacher, student);
        long paperId = preparePaper(teacher);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        body.put("classId", classId);
        body.put("startTime", LocalDateTime.now().minusMinutes(5).toString());
        body.put("endTime", LocalDateTime.now().plusMinutes(40).toString());
        body.put("durationMinutes", 30);
        // 不传 reservationRequired（默认 false）
        long examId = perform(jsonPost("/api/exams", teacher, objectMapper.writeValueAsString(body)), 200)
                .get("data").get("id").asLong();
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        jdbc.update("UPDATE exams SET end_time = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), examId);
        stateMachineService.autoAdvance();

        assertEquals(1, absenceCount(examId, studentId), "普通考试未答卷班内学生被标记缺考（班级名单口径）");
    }

    private long absenceCount(long examId, long studentId) {
        return absenceMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExamAbsence>()
                .eq(ExamAbsence::getExamId, examId)
                .eq(ExamAbsence::getStudentId, studentId));
    }

    private long countExamInList(JsonNode list, long examId) {
        long count = 0;
        for (JsonNode m : list) {
            if (m.has("examId") && !m.get("examId").isNull() && m.get("examId").asLong() == examId) {
                count++;
            }
        }
        return count;
    }
}