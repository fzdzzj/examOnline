package com.exam.exam;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.auth.service.JwtUtil;
import com.exam.common.BusinessException;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamService;
import com.exam.exam.service.ExamStateMachineService;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 考试管理集成测试（spec add-exam-management）：
 * <ul>
 *   <li>创建校验——时间窗非法/时长非法被拒、试卷归属与数据隔离；</li>
 *   <li>发布——生成考试快照（题目/答案/分值/顺序固化），重复发布被拒；</li>
 *   <li>状态机——定时开考（到点 未开始→进行中）、自然结束（时间窗全过 一轮推进到已结束）、
 *       未发布不自动开考、教师提前结束（force_end 标记）；</li>
 *   <li>试卷锁定——进行中考试的试卷/题目禁止修改，未开始与已结束不受影响（§4.1）；</li>
 *   <li>并发 CAS——两线程同时提前结束同一考试，仅一个成功（spec「并发流转仅一次」）。</li>
 * </ul>
 */
class ExamIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ExamStateMachineService stateMachineService;

    @Autowired
    private ExamMapper examMapper;

    @Autowired
    private ExamService examService;

    @Autowired
    private JwtUtil jwtUtil;

    // ==================== 造数 ====================

    private String examJson(long paperId, LocalDateTime start, LocalDateTime end, int duration)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", paperId);
        body.put("startTime", start.toString());
        body.put("endTime", end.toString());
        body.put("durationMinutes", duration);
        // 防作弊配置随创建落库，快照中原样固化
        body.putObject("antiCheatConfig").put("switchScreen", true);
        return objectMapper.writeValueAsString(body);
    }

    private long createExam(String token, long paperId, LocalDateTime start,
                            LocalDateTime end, int duration) throws Exception {
        JsonNode data = perform(jsonPost("/api/exams", token,
                examJson(paperId, start, end, duration)), 200).get("data");
        return data.get("id").asLong();
    }

    private long addPaperQuestion(String token, long paperId, long questionId, double score)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
        return questionId;
    }

    /** 组一卷两题（6+4=10），返回试卷 ID。 */
    private long preparePaperWithTwoQuestions(String token) throws Exception {
        long q1 = createQuestion(token, 1, "单选题干：1+1=?", "A", List.of("A", "B", "C", "D"), null);
        long q2 = createQuestion(token, 4, "简答题干：描述进程与线程区别", "进程有独立地址空间", null, null);
        long paperId = createPaper(token, unique("试卷"), 10);
        addPaperQuestion(token, paperId, q1, 6);
        addPaperQuestion(token, paperId, q2, 4);
        return paperId;
    }

    private JsonNode examDetail(String token, long examId) throws Exception {
        return perform(jsonGet("/api/exams/" + examId, token), 200).get("data");
    }

    private JsonNode examSnapshot(String token, long examId) throws Exception {
        return perform(jsonGet("/api/exams/" + examId + "/snapshot", token), 200).get("data");
    }

    /** 测试内手动把考试开始时间拨到过去（调度由 autoAdvance 手动触发，保证确定性）。 */
    private void shiftStartTimeToPast(long examId, int minusMinutes) {
        examMapper.update(null, Wrappers.<Exam>lambdaUpdate()
                .eq(Exam::getId, examId)
                .set(Exam::getStartTime, LocalDateTime.now().minusMinutes(minusMinutes)));
    }

    private void shiftEndTimeToPast(long examId, int minusMinutes) {
        examMapper.update(null, Wrappers.<Exam>lambdaUpdate()
                .eq(Exam::getId, examId)
                .set(Exam::getEndTime, LocalDateTime.now().minusMinutes(minusMinutes)));
    }

    // ==================== 创建校验 ====================

    @Test
    void createExamValidationsAndIsolation() throws Exception {
        String teacher = registerTeacher();
        long paperId = createPaper(teacher, unique("试卷"), 10);

        // 合法创建：初始未开始、未发布
        JsonNode created = perform(jsonPost("/api/exams", teacher,
                examJson(paperId, LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2), 60)), 200)
                .get("data");
        assertEquals(0, created.get("status").asInt());
        assertEquals(0, created.get("published").asInt());
        assertEquals(60, created.get("durationMinutes").asInt());

        // 时间窗非法：结束早于开始
        perform(jsonPost("/api/exams", teacher, examJson(paperId,
                LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(1), 60)), 400);
        // 时间窗非法：结束等于开始
        LocalDateTime same = LocalDateTime.now().plusHours(1);
        perform(jsonPost("/api/exams", teacher, examJson(paperId, same, same, 60)), 400);

        // 时长非法：个人时长 = 0
        perform(jsonPost("/api/exams", teacher, examJson(paperId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2), 0)), 400);

        // 绑定试卷不存在
        perform(jsonPost("/api/exams", teacher, examJson(999999,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2), 60)), 404);

        // 越权：拿其他教师的试卷开考 → 403
        String otherTeacher = registerTeacher();
        perform(jsonPost("/api/exams", otherTeacher, examJson(paperId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2), 60)), 403);

        // 未登录 → 401
        perform(jsonPost("/api/exams", null, examJson(paperId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2), 60)), 401);

        // 数据隔离：其他教师的列表看不到本场考试，详情 403
        long examId = created.get("id").asLong();
        JsonNode otherList = perform(jsonGet("/api/exams?page=1&size=50", otherTeacher), 200).get("data");
        boolean found = false;
        for (JsonNode item : otherList) {
            if (item.get("id").asLong() == examId) {
                found = true;
            }
        }
        assertTrue(!found, "其他教师的考试列表不应包含本场考试");
        perform(jsonGet("/api/exams/" + examId, otherTeacher), 403);
    }

    // ==================== 发布/快照/锁定/状态流转（端到端） ====================

    @Test
    void publishSnapshotLockAndLifecycleEndToEnd() throws Exception {
        String teacher = registerTeacher();
        long paperId = preparePaperWithTwoQuestions(teacher);

        // 无关题目（未入卷）：锁定期间仍可正常增删，验证题目锁只作用于"被进行中试卷引用"的题
        long q3 = createQuestion(teacher, 1, "无关题干", "B", List.of("A", "B", "C", "D"), null);

        // 未来时间窗：避免后台定时任务干扰，到点推进由测试手动触发
        long examId = createExam(teacher, paperId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2), 60);

        // 发布：学生可见 + 快照生成，状态仍为未开始（定时发布场景）
        JsonNode published = perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200)
                .get("data");
        assertEquals(1, published.get("published").asInt());
        assertEquals(0, published.get("status").asInt());
        assertTrue(published.get("snapshotId").asLong() > 0);

        // 快照内容：完整试卷（题目/归一化答案/试卷内分值/题号）+ 考试配置（含防作弊）
        JsonNode snapshot = examSnapshot(teacher, examId);
        JsonNode questions = snapshot.get("paper").get("questions");
        assertEquals(2, questions.size());
        assertEquals(1, questions.get(0).get("number").asInt());
        assertEquals("A", questions.get(0).get("correctAnswer").asText());
        assertEquals(6, questions.get(0).get("score").asInt());
        assertEquals(60, snapshot.get("exam").get("durationMinutes").asInt());
        assertEquals(true, snapshot.get("exam").get("antiCheatConfig").get("switchScreen").asBoolean());

        // 重复发布被拒
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 400);

        // 未开始不受锁定影响：改试卷分值成功，但快照内容不变（试卷变更不影响快照）
        perform(jsonPut("/api/papers/" + paperId + "/questions/" + questions.get(0).get("questionId").asLong()
                + "/score", teacher, "{\"score\": 5}"), 200);
        JsonNode snapshotAfterEdit = examSnapshot(teacher, examId);
        assertEquals(snapshot.get("paper").toString(), snapshotAfterEdit.get("paper").toString(),
                "试卷修改后考试快照必须保持发布时的内容");

        // 模拟到点开考：把开始时间拨到过去 → 定时推进 未开始→进行中
        shiftStartTimeToPast(examId, 1);
        stateMachineService.autoAdvance();
        assertEquals(1, examDetail(teacher, examId).get("status").asInt());

        // ---- 进行中：试卷锁定（§4.1），错误口径与 spec 一致 ----
        String lockMessage = "考试进行中，试卷已锁定";
        long q1 = examSnapshot(teacher, examId).get("paper").get("questions").get(0).get("questionId").asLong();
        long q2 = examSnapshot(teacher, examId).get("paper").get("questions").get(1).get("questionId").asLong();

        JsonNode scoreResp = perform(jsonPut("/api/papers/" + paperId + "/questions/" + q1 + "/score",
                teacher, "{\"score\": 8}"), 400);
        assertTrue(scoreResp.get("message").asText().contains(lockMessage));

        ObjectNode addBody = objectMapper.createObjectNode();
        addBody.put("questionId", q3);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                objectMapper.writeValueAsString(addBody)), 400);

        perform(jsonDelete("/api/papers/" + paperId + "/questions/" + q2, teacher), 400);

        perform(jsonPut("/api/papers/" + paperId + "/questions/order", teacher,
                "{\"questionIds\": [" + q2 + "," + q1 + "]}"), 400);

        perform(jsonPut("/api/papers/" + paperId, teacher, "{\"title\": \"改名\"}"), 400);

        perform(jsonDelete("/api/papers/" + paperId, teacher), 400);

        perform(jsonPost("/api/papers/" + paperId + "/snapshot", teacher, null), 400);

        // 被进行中考试试卷引用的题目禁止修改/软删
        perform(jsonPut("/api/questions/" + q1, teacher,
                questionJson(1, "改题干", "B", List.of("A", "B", "C", "D"), 5.0, 1, null)), 400);
        perform(jsonDelete("/api/questions/" + q1, teacher), 400);

        // 未被引用的题目不受锁定影响：可新建、可软删
        createQuestion(teacher, 1, "无关新题干", "C", List.of("A", "B", "C", "D"), null);
        perform(jsonDelete("/api/questions/" + q3, teacher), 200);

        // 教师提前结束：进行中 → 已结束 + force_end 标记（强制交卷由阶段 5 处理）
        JsonNode ended = perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 200)
                .get("data");
        assertEquals(2, ended.get("status").asInt());
        assertEquals(1, ended.get("forceEnd").asInt());

        // 已结束解锁：试卷恢复可编辑（只影响后续场次）
        perform(jsonPut("/api/papers/" + paperId + "/questions/" + q1 + "/score",
                teacher, "{\"score\": 8}"), 200);

        // 重复提前结束被拒（已结束）
        perform(jsonPost("/api/exams/" + examId + "/force-end", teacher, null), 400);
    }

    // ==================== 定时发布/自然结束 ====================

    @Test
    void autoAdvanceTimedStartNaturalEndAndUnpublished() throws Exception {
        String teacher = registerTeacher();
        long paperId = preparePaperWithTwoQuestions(teacher);

        // 时间窗整体已过：发布后一轮扫表连续推进 未开始→进行中→已结束（自然结束）
        long passedExam = createExam(teacher, paperId,
                LocalDateTime.now().minusMinutes(2), LocalDateTime.now().minusMinutes(1), 30);
        perform(jsonPost("/api/exams/" + passedExam + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        assertEquals(2, examDetail(teacher, passedExam).get("status").asInt());

        // 已开始未到结束：一轮扫表只推进到进行中
        long ongoingExam = createExam(teacher, paperId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1), 30);
        perform(jsonPost("/api/exams/" + ongoingExam + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        assertEquals(1, examDetail(teacher, ongoingExam).get("status").asInt());

        // 未发布的考试不自动开考（学生不可见、教师未确认）
        long unpublishedExam = createExam(teacher, paperId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1), 30);
        stateMachineService.autoAdvance();
        assertEquals(0, examDetail(teacher, unpublishedExam).get("status").asInt());
        assertEquals(0, examDetail(teacher, unpublishedExam).get("published").asInt());

        // 未来开始时间：已发布但未到点，状态保持未开始
        long futureExam = createExam(teacher, paperId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2), 30);
        perform(jsonPost("/api/exams/" + futureExam + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        assertEquals(0, examDetail(teacher, futureExam).get("status").asInt());
    }

    // ==================== 发布/删除/提前结束门槛 ====================

    @Test
    void publishDeleteAndForceEndGuards() throws Exception {
        String teacher = registerTeacher();
        long paperId = preparePaperWithTwoQuestions(teacher);

        // 未发布且未开始：可删除（软删），删除后 404
        long deletable = createExam(teacher, paperId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2), 60);
        perform(jsonDelete("/api/exams/" + deletable, teacher), 200);
        perform(jsonGet("/api/exams/" + deletable, teacher), 404);

        // 已发布：不允许删除/修改（学生已可见，须先撤回——撤回能力后续阶段提供）
        long publishedExam = createExam(teacher, paperId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2), 60);
        perform(jsonPost("/api/exams/" + publishedExam + "/publish", teacher, null), 200);
        perform(jsonDelete("/api/exams/" + publishedExam, teacher), 400);
        perform(jsonPut("/api/exams/" + publishedExam, teacher,
                examJson(paperId, LocalDateTime.now().plusHours(3), LocalDateTime.now().plusHours(4), 60)), 400);

        // 未开始的考试不允许提前结束
        long futureExam = createExam(teacher, paperId,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2), 60);
        perform(jsonPost("/api/exams/" + futureExam + "/force-end", teacher, null), 400);

        // 不存在的考试：详情/提前结束 404
        perform(jsonGet("/api/exams/999999", teacher), 404);
        perform(jsonPost("/api/exams/999999/force-end", teacher, null), 404);
    }

    // ==================== 并发 CAS：提前结束仅一次成功 ====================

    @Test
    void concurrentForceEndOnlyOneWins() throws Exception {
        String teacher = registerTeacher();
        long paperId = preparePaperWithTwoQuestions(teacher);
        long examId = createExam(teacher, paperId,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1), 60);
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        stateMachineService.autoAdvance();
        assertEquals(1, examDetail(teacher, examId).get("status").asInt());

        // MockMvc 请求是串行的，直接在两个线程中以 Service 调用制造真实竞态：
        // 两个请求以同一 version 执行 CAS（进行中→已结束），DB 层保证仅一个影响行数为 1
        LoginUser teacherUser = jwtUtil.parseAccessToken(teacher);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Integer> forceEndTask = () -> {
            SecurityUtil.set(teacherUser);
            ready.countDown();
            go.await();
            try {
                examService.forceEnd(examId);
                return 200;
            } catch (BusinessException e) {
                // 另一线程抢先成功：本线程要么 CAS 影响 0 行(409)，要么快路径发现已结束(400)
                return e.getHttpStatus();
            } finally {
                SecurityUtil.clear();
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(forceEndTask);
            Future<Integer> second = pool.submit(forceEndTask);
            assertTrue(ready.await(5, TimeUnit.SECONDS), "两个并发请求应就绪");
            go.countDown();
            int firstStatus = first.get(10, TimeUnit.SECONDS);
            int secondStatus = second.get(10, TimeUnit.SECONDS);

            AtomicInteger wins = new AtomicInteger();
            for (int status : new int[]{firstStatus, secondStatus}) {
                if (status == 200) {
                    wins.incrementAndGet();
                } else {
                    assertTrue(status == 400 || status == 409,
                            "失败方应返回 400（已结束）或 409（状态冲突），实际 " + status);
                }
            }
            assertEquals(1, wins.get(), "并发提前结束必须恰好一个成功");
        } finally {
            pool.shutdownNow();
        }

        // 终态：已结束 + 提前结束标记，无状态脏写
        JsonNode detail = examDetail(teacher, examId);
        assertEquals(2, detail.get("status").asInt());
        assertEquals(1, detail.get("forceEnd").asInt());
    }
}
