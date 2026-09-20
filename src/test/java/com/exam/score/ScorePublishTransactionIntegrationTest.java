package com.exam.score;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.score.entity.ScoreAuditLog;
import com.exam.score.mapper.ScoreAuditLogMapper;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 批量发布的事务边界在真实数据库上的验证（补 ScoreServiceTest 的空白）。
 *
 * <p>ScoreServiceTest 是纯 Mockito 单测，examMapper 全是打桩对象，因此它只证明了
 * "循环会中止"，从未证明"异常逃出时整批回滚"这一数据库事实——而这恰恰是
 * {@code ScoreService.publish()} 的 Javadoc 所依赖的核心声明。本用例走真实 H2 补齐。
 *
 * <p>夹具刻意做轻：{@code doPublishOne} 只要求考试处于「已批改」，判分行数 0 也能走完
 * （审计 detail 记"发布成绩 0 人"），因此不需要学生/答卷/主观分那一整套链路。
 */
class ScorePublishTransactionIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ScoreAuditLogMapper scoreAuditLogMapper;

    /** spy 而非 mock：未打桩的方法照常走真实 mapper 与真实 SQL。 */
    @MockitoSpyBean
    private ExamMapper examMapper;

    /** 建一场考试并直接推进到「已批改」。 */
    private long gradedExam(String teacher, String title) throws Exception {
        long paperId = createPaper(teacher, title + "-卷", 10.0);
        JsonNode created = perform(post("/api/exams")
                .header("Authorization", bearer(teacher))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"paperId\":" + paperId
                        + ",\"startTime\":\"2026-01-05T09:00:00\",\"endTime\":\"2026-01-05T10:00:00\","
                        + "\"durationMinutes\":60}"), 200);
        long examId = created.get("data").get("id").asLong();
        setExamStatus(examId, Exam.STATUS_GRADED);
        return examId;
    }

    private void setExamStatus(long examId, int status) {
        examMapper.update(null, Wrappers.<Exam>lambdaUpdate()
                .eq(Exam::getId, examId)
                .set(Exam::getStatus, status));
    }

    private JsonNode publish(String teacher, int expectedStatus, long... examIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (long id : examIds) {
            ids.append(ids.isEmpty() ? "" : ",").append(id);
        }
        return perform(post("/api/scores/publish")
                .header("Authorization", bearer(teacher))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"examIds\":[" + ids + "]}"), expectedStatus);
    }

    /**
     * 业务性失败只让那一场失败，兄弟场次的写入照常提交——"部分成功"语义在真库上成立。
     */
    @Test
    void businessFailureOnOneExamDoesNotRollBackItsSibling() throws Exception {
        String teacher = registerTeacher();
        long ok = gradedExam(teacher, "事务A");
        long notSummarized = gradedExam(teacher, "事务B");
        setExamStatus(notSummarized, Exam.STATUS_ENDED); // 未汇总 → 业务性失败

        JsonNode response = publish(teacher, 200, ok, notSummarized);

        assertEquals(2, response.get("data").size());
        assertEquals(Exam.STATUS_PUBLISHED, examMapper.selectById(ok).getStatus(),
                "另一场的业务性失败不该连带回滚本场");
        assertEquals(Exam.STATUS_ENDED, examMapper.selectById(notSummarized).getStatus());
        assertEquals(1L, auditCount(ok), "成功场次应有 PUBLISH 审计");
        assertEquals(0L, auditCount(notSummarized), "失败场次不该留下审计");
    }

    /**
     * 基础设施异常逃出 publish() 时整批回滚：先前的 CAS 更新与审计都不留痕。
     * 这正是 publish() 共用一个事务的理由，此前从未被任何测试证明过。
     */
    @Test
    void infrastructureFailureRollsBackTheWholeBatch() throws Exception {
        String teacher = registerTeacher();
        long first = gradedExam(teacher, "回滚A");
        long second = gradedExam(teacher, "回滚B");

        // 第 2 场读取时炸库：非 BusinessException，会一路逃出 publish()
        doThrow(new IllegalStateException("db down")).when(examMapper).selectById(second);

        publish(teacher, 500, first, second);

        assertEquals(Exam.STATUS_GRADED, examMapper.selectById(first).getStatus(),
                "整批共用一个事务：第 2 场故障时第 1 场的 CAS 更新必须一起回滚，不能留下半批已发布");
        assertEquals(0L, auditCount(first), "回滚后第 1 场的 PUBLISH 审计也应一并消失");
    }

    private long auditCount(long examId) {
        return scoreAuditLogMapper.selectCount(Wrappers.<ScoreAuditLog>lambdaQuery()
                .eq(ScoreAuditLog::getExamId, examId));
    }
}
