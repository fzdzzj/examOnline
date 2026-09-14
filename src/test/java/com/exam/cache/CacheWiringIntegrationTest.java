package com.exam.cache;

import com.exam.exam.mapper.ExamSnapshotMapper;
import com.exam.paper.mapper.PaperSnapshotMapper;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 缓存接入链路集成测试（add-performance-deepening 阶段 8 task2）：
 * 验证 @Cacheable/@CacheEvict 接线后的业务行为与缓存落键——
 * <ul>
 *   <li>考试快照：未发布探测 404 写空标记 → 发布清除标记 → 拉卷 200 且重复读取命中缓存不回源（开考拉卷保护伞）；</li>
 *   <li>试卷快照：跨教师读取 403 不缓存、归属教师重复读取命中缓存；</li>
 *   <li>考试详情：缓存写入 → 更新显式失效（allEntries）→ 再读取返回新值。</li>
 * </ul>
 */
class CacheWiringIntegrationTest extends IntegrationTestBase {

    @Autowired
    private StringRedisTemplate redis;

    @MockitoSpyBean
    private ExamSnapshotMapper examSnapshotMapper;

    @MockitoSpyBean
    private PaperSnapshotMapper paperSnapshotMapper;

    // ==================== 考试快照：空标记清除 + 命中缓存不回源 ====================

    @Test
    void examSnapshotMarkerClearedOnPublishAndReadsServedFromCache() throws Exception {
        String teacher = registerTeacher();
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId,
                LocalDateTime.now().plusMinutes(1), LocalDateTime.now().plusHours(2), 30);

        // 未发布拉卷：404 且写入防穿透空标记
        perform(jsonGet("/api/exams/" + examId + "/snapshot", teacher), 404);
        String marker = "exam:cache:examSnapshot:empty:" + examId;
        assertNotNull(redis.opsForValue().get(marker), "未发布探测应写入 404 空标记");
        verify(examSnapshotMapper, times(1)).selectOne(any());

        // 发布：清空标记 → 立即拉卷成功（若无清除逻辑，短 TTL 窗口内会被旧标记误挡为 404）
        perform(jsonPost("/api/exams/" + examId + "/publish", teacher, null), 200);
        perform(jsonGet("/api/exams/" + examId + "/snapshot", teacher), 200);
        // 发布后首次拉卷回源（第 2 次 selectOne），随后缓存已有值、空标记已清除
        verify(examSnapshotMapper, times(2)).selectOne(any());

        // 重复读取（模拟开考 5000 人拉卷）：命中缓存，不再回源 DB
        JsonNode first = perform(jsonGet("/api/exams/" + examId + "/snapshot", teacher), 200).get("data");
        JsonNode second = perform(jsonGet("/api/exams/" + examId + "/snapshot", teacher), 200).get("data");
        assertEquals(first.get("id").asLong(), second.get("id").asLong());
        assertEquals(examId, first.get("examId").asLong());
        verify(examSnapshotMapper, times(2)).selectOne(any());
        // 快照值已缓存（长 TTL 档位：只读不可变数据与 DB 天然一致）
        Long ttl = redis.getExpire("exam:cache:examSnapshot:" + examId);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "快照缓存 key 应存在且带 TTL");
    }

    // ==================== 试卷快照：越权不缓存 + 归属者命中缓存 ====================

    @Test
    void paperSnapshotRejectsCrossTeacherAndServesOwnerFromCache() throws Exception {
        String teacherA = registerTeacher();
        String teacherB = registerTeacher();
        long q1 = createQuestion(teacherA, 1, "单选题", "A", List.of("A", "B"), null);
        long paperId = createPaper(teacherA, unique("试卷"), 5.0);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacherA,
                "{\"questionId\":" + q1 + "}"), 200);
        perform(jsonPost("/api/papers/" + paperId + "/snapshot", teacherA, null), 200);

        // 非归属教师读取：403 越权拦截（异常路径不写缓存/空标记），不给缓存绕过的机会
        perform(jsonGet("/api/papers/" + paperId + "/snapshot", teacherB), 403);
        verify(paperSnapshotMapper, times(0)).selectById(any());

        // 归属教师首次读取回源，第二次命中缓存（key 带请求者 ID，各用户缓存隔离）
        JsonNode first = perform(jsonGet("/api/papers/" + paperId + "/snapshot", teacherA), 200).get("data");
        JsonNode second = perform(jsonGet("/api/papers/" + paperId + "/snapshot", teacherA), 200).get("data");
        assertEquals(first.get("id").asLong(), second.get("id").asLong());
        verify(paperSnapshotMapper, times(1)).selectById(any());
        Set<String> keys = redis.keys("exam:cache:paperSnapshot:" + paperId + ":*");
        assertFalse(keys.isEmpty(), "试卷快照值应已缓存（key 含请求者 ID 段）");
    }

    // ==================== 考试详情：短 TTL 缓存 + 写路径显式失效 ====================

    @Test
    void examDetailCachedAndEvictedOnUpdate() throws Exception {
        String teacher = registerTeacher();
        long paperId = preparePaper(teacher);
        long examId = createExam(teacher, paperId,
                LocalDateTime.now().plusMinutes(1), LocalDateTime.now().plusHours(2), 30);

        // 详情读取 → 缓存写入（key 带请求者 ID）
        perform(jsonGet("/api/exams/" + examId, teacher), 200);
        assertFalse(redis.keys("exam:cache:examDetail:" + examId + ":*").isEmpty(), "详情应已缓存");

        // 更新 → allEntries 显式失效 → 再读取返回新值（若失效失效，会读到旧标题）
        perform(jsonPut("/api/exams/" + examId, teacher, "{\"title\":\"更新后的标题\"}"), 200);
        assertTrue(redis.keys("exam:cache:examDetail:*").isEmpty(), "更新后应清空 examDetail 缓存");
        JsonNode detail = perform(jsonGet("/api/exams/" + examId, teacher), 200).get("data");
        assertEquals("更新后的标题", detail.get("title").asText());
        // 失效后再读取回源并重新缓存
        assertFalse(redis.keys("exam:cache:examDetail:" + examId + ":*").isEmpty());
    }

    // ==================== 造数（与 taking 集成测试同款） ====================

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

    private void addPaperQuestion(String token, long paperId, long questionId, double score) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", token,
                objectMapper.writeValueAsString(body)), 200);
    }
}
