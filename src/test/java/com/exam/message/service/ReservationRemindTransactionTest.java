package com.exam.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exam.auth.service.JwtUtil;
import com.exam.exam.entity.ExamReservation;
import com.exam.exam.mapper.ExamReservationMapper;
import com.exam.message.entity.UserMessage;
import com.exam.message.mapper.UserMessageMapper;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 开考提醒投递事务边界在真实数据库上的验证（fix-reservation-remind-transaction）。
 *
 * <p><b>缺陷机理</b>：{@code deliverIfAbsent} 的 {@code @Transactional} 是自调用死注解——
 * 唯一调用点是本类 {@code remind()} 的 {@code this.deliverIfAbsent(...)}（remind 由
 * {@code @Scheduled}/{@code @EventListener} 无事务直入），注解在生产路径静默失效：
 * 认领回填（markRemindedIfNull）与消息插入各自 auto-commit。消息插入瞬时失败时认领已提交，
 * {@code reminded_time} 非空使后续扫描（{@code WHERE reminded_time IS NULL}）永不再扫到该行，
 * 且消息未落库——提醒永久丢失、不可自愈。
 *
 * <p><b>先红证明</b>：修复前本类确定性变红——注入消息插入故障后，认领回填残留
 * （reminded_time 已置位而消息未写出），断言"回填不残留"失败即缺陷存在性证明。
 * 修复后（认领 + 写消息同一编程式事务）：插入失败当轮回滚、reminded_time 回到 NULL，
 * 下一轮扫表幂等重试自愈；撤除故障后重试恰投一条，再扫描不重发（行级幂等语义零变化）。
 * 断言对象是真实数据库行（exam_reservations.reminded_time、user_messages 行数），
 * 不是打桩交互。幂等口径（user_messages 无唯一键，幂等完全由认领闸承担）已在
 * 提案「幂等口径先行确认」论证：认领与消息同生共死，重试不产生重复投递。
 *
 * <p><b>夹具刻意做轻</b>：只需「需预约 + 已发布 + 未开始 + 窗内」一场考试与一条预约，
 * 学生经真实预约端点预约（与生产同形）；消息插入故障经 {@code @MockitoSpyBean} 注入——
 * spy 未打桩的方法（含 selectCount）照常走真实实现。测试环境的定时扫描间隔已由
 * application-test.yml 拉到 1h，用例显式调用 {@code remind()}，无后台 tick 交错。
 */
@SpringBootTest
class ReservationRemindTransactionTest extends IntegrationTestBase {

    /** spy 而非 mock：未打桩时照常走真实实现（selectCount/insertBatch 都是真库事实）。 */
    @MockitoSpyBean
    private UserMessageMapper messageMapper;

    @Autowired
    private ReservationRemindService remindService;

    @Autowired
    private ExamReservationMapper reservationMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void insertFailureRollsBackClaimThenRetryHeals() throws Exception {
        long[] ids = fixtureStudentExamReservation();
        long studentId = ids[0];
        long examId = ids[1];

        List<ExamReservation> rows = reservationMapper.selectByExam(examId);
        assertEquals(1, rows.size(), "前置条件：该场考试应恰有一条预约");
        Long reservationId = rows.get(0).getId();
        assertNull(rows.get(0).getRemindedTime(), "前置条件：预约尚未投递");

        // 注入消息插入故障（认领 UPDATE 不受影响，仍走真实实现）
        doThrow(new RuntimeException("消息插入瞬时故障（用例注入）"))
                .when(messageMapper).insert(any(UserMessage.class));

        RuntimeException escaped = runRemindSavingFailure();
        assertNotNull(escaped,
                "前置条件：注入故障的一轮必须失败逃出（定时入口由 @Scheduled 记日志，靠下一轮重试自愈）");

        // 核心断言：插入失败时认领回填不得残留——残留即"提醒永久丢失不可自愈"
        ExamReservation after = reservationMapper.selectById(reservationId);
        assertNull(after.getRemindedTime(),
                "事务边界：消息插入失败时认领回填必须随事务回滚——回填一旦残留，该预约将被后续扫描"
                        + "（reminded_time IS NULL 过滤）永久跳过且消息未写出，提醒永久丢失不可自愈");

        // 撤除故障注入：下一轮扫表幂等重试自愈
        Mockito.reset(messageMapper);
        assertEquals(1, remindService.remind(), "重试补偿：故障撤除后下一轮扫描应重新认领并投递成功");
        assertEquals(1, messageCountFor(studentId, examId),
                "重试恰好投递一条消息（认领与消息同生共死，不产生重复投递）");

        // 行级幂等回归：已投递行再次扫描不重发
        assertEquals(0, remindService.remind(), "已回填提醒的预约不得重复投递");
        assertEquals(1, messageCountFor(studentId, examId), "重复扫描不得产生第二条消息");
    }

    /**
     * 回归护栏（修复前后都绿）：正常一轮投递恰好一条消息、重复扫描行级幂等不重发——
     * 防止"为回滚而回滚"把正常投递链路一并改坏。
     */
    @Test
    void successfulRoundDeliversExactlyOnce() throws Exception {
        long[] ids = fixtureStudentExamReservation();
        long studentId = ids[0];
        long examId = ids[1];

        assertEquals(1, remindService.remind(), "正常一轮应投递一条开考提醒消息");
        assertEquals(1, messageCountFor(studentId, examId), "正常一轮后消息应恰好一条");
        assertEquals(0, remindService.remind(), "第二次扫描行级幂等不重发");
        assertEquals(1, messageCountFor(studentId, examId), "重复扫描不得产生第二条消息");
    }

    // ==================== 造数 / 工具 ====================

    /** 造「学生已预约的窗内需预约考试」，返回 {studentId, examId}。 */
    private long[] fixtureStudentExamReservation() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long studentId = jwtUtil.parseAccessToken(student).getId();
        long classId = createClassForExam(teacher, student);

        long questionId = createQuestion(teacher, 1, unique("单选：事务题"), "A",
                List.of("A", "B", "C", "D"), null);
        long paperId = createPaper(teacher, unique("提醒事务卷"), 10.0);
        ObjectNode addQuestion = objectMapper.createObjectNode();
        addQuestion.put("questionId", questionId);
        addQuestion.put("score", 10);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                objectMapper.writeValueAsString(addQuestion)), 200);

        ObjectNode examBody = objectMapper.createObjectNode();
        examBody.put("title", unique("提醒事务考试"));
        examBody.put("paperId", paperId);
        examBody.put("classId", classId);
        examBody.put("startTime", LocalDateTime.now().plusMinutes(10).toString());
        examBody.put("endTime", LocalDateTime.now().plusHours(2).toString());
        examBody.put("durationMinutes", 30);
        examBody.put("reservationRequired", true);
        long examId = perform(jsonPost("/api/exams", teacher, objectMapper.writeValueAsString(examBody)), 200)
                .get("data").get("id").asLong();
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        perform(jsonPost("/api/exam-reservations/" + examId, student, null), 200);
        return new long[]{studentId, examId};
    }

    /** 跑一轮投递，返回逃出的异常（未逃出返回 null）——不把"异常类型"写死成断言。 */
    private RuntimeException runRemindSavingFailure() {
        try {
            remindService.remind();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** 统计该学生对某考试的「开考提醒」消息数（考试发布通知是独立类型，不计入）。 */
    private long messageCountFor(long userId, long examId) {
        return messageMapper.selectCount(new LambdaQueryWrapper<UserMessage>()
                .eq(UserMessage::getUserId, userId)
                .eq(UserMessage::getExamId, examId)
                .eq(UserMessage::getTitle, "开考提醒"));
    }
}
