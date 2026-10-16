package com.exam.score.support;

import com.exam.config.CacheProperties;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.question.entity.QuestionType;
import com.exam.submission.entity.ExamSubmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 错题判定轻量缓存单测（optimize-wrong-question-derivation-cost，纯 Mockito + 裸 ObjectMapper）：
 *
 * <ul>
 *   <li>命中路径：缓存有值时不 invoke 回填器（免装配），直接返回缓存三元组；</li>
 *   <li>未命中回填：缓存无值时按现算口径回填并落键；</li>
 *   <li>版本戳失效：判分事实源（答卷/主观批改）信号变化后 key 变化，旧缓存不被采信、重新现算；</li>
 *   <li>故障退化：Redis 异常时退化为现算（loader 兜底），不阻断消费端（可用性优先）；</li>
 *   <li>无已汇总答卷：按 loader 现算返回，不写缓存。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WrongQuestionJudgementCacheTest {

    private static final long EXAM_ID = 1001L;
    private static final long STUDENT_ID = 42L;
    private static final long QUESTION_ID = 201L;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private SubjectiveGradeMapper subjectiveGradeMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CacheProperties props = new CacheProperties();
    private WrongQuestionJudgementCache cache;

    private final Map<Long, WrongQuestionJudgementCache.ScoredQuestion> scored = Map.of(
            QUESTION_ID, new WrongQuestionJudgementCache.ScoredQuestion(
                    new BigDecimal("3.0"), new BigDecimal("5.0"), QuestionType.SINGLE));

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        cache = new WrongQuestionJudgementCache(redis, objectMapper, gradingSubmissionMapper,
                subjectiveGradeMapper, props);
    }

    @Test
    @DisplayName("命中：缓存有值不 invoke 回填器（免装配），返回缓存三元组")
    void hit_doesNotInvokeLoader() throws Exception {
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(submission(ts("2026-01-01T10:00:00")));
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of());
        when(valueOps.get(anyString())).thenReturn(objectMapper.writeValueAsString(scored));

        AtomicInteger loaderCalls = new AtomicInteger();
        Map<Long, WrongQuestionJudgementCache.ScoredQuestion> result = cache.get(EXAM_ID, STUDENT_ID, () -> {
            loaderCalls.incrementAndGet();
            return scored;
        });

        assertEquals(scored, result);
        assertEquals(0, loaderCalls.get(), "缓存命中不得触发装配回填");
    }

    @Test
    @DisplayName("未命中：按现算口径回填并落键缓存")
    void miss_backfillsAndStores() {
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(submission(ts("2026-01-01T10:00:00")));
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of());
        when(valueOps.get(anyString())).thenReturn(null);

        Map<Long, WrongQuestionJudgementCache.ScoredQuestion> result =
                cache.get(EXAM_ID, STUDENT_ID, () -> scored);

        assertEquals(scored, result);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<java.time.Duration> ttlCap = ArgumentCaptor.forClass(java.time.Duration.class);
        verify(valueOps).set(keyCap.capture(), valCap.capture(), ttlCap.capture());
        assertTrue(keyCap.getValue().startsWith(WrongQuestionJudgementCache.keyPrefix()),
                "缓存 key 必须带前缀");
        assertTrue(keyCap.getValue().contains(String.valueOf(EXAM_ID))
                && keyCap.getValue().contains(String.valueOf(STUDENT_ID)), "缓存 key 必须含 exam/student");
        assertTrue(keyCap.getValue().contains("v"), "缓存 key 必须携带版本信号");
        assertEquals(props.getWrongQuestionJudgementTtl(), ttlCap.getValue());
    }

    @Test
    @DisplayName("版本戳失效：判分事实源信号变化后 key 变化，旧缓存不被采信并重新现算")
    void versionStampChange_invalidatesCache() throws Exception {
        // 第一次读取：答卷 updated_time = t1，回填 mapA 到 keyK1
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(submission(ts("2026-01-01T10:00:00")));
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of());
        when(valueOps.get(anyString())).thenReturn(null);
        cache.get(EXAM_ID, STUDENT_ID, () -> scored);

        ArgumentCaptor<String> key1Cap = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(key1Cap.capture(), anyString(), any(java.time.Duration.class));
        String keyK1 = key1Cap.getValue();

        // 第二次读取：答卷退无可退——模拟教师改分 touch submission，updated_time 变为 t2
        // （批改写点 refreshSubmissionSubjectiveScore 会刷新答卷 updated_time），缓存应失效。
        reset(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);   // 新 key 无缓存
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(submission(ts("2026-01-01T10:00:05")));

        // loader 副作用计数：确认失效后重新现算
        AtomicInteger reloads = new AtomicInteger();
        Map<Long, WrongQuestionJudgementCache.ScoredQuestion> result =
                cache.get(EXAM_ID, STUDENT_ID, () -> {
                    reloads.incrementAndGet();
                    return scored;
                });

        assertEquals(scored, result);
        assertEquals(1, reloads.get(), "版本信号变化后必须重新现算回填");
        ArgumentCaptor<String> key2Cap = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(key2Cap.capture(), anyString(), any(java.time.Duration.class));
        assertNotEquals(keyK1, key2Cap.getValue(), "版本信号变化后缓存 key 必须变化（旧缓存自然失效）");
    }

    @Test
    @DisplayName("故障退化：Redis 异常时退化为现算（loader 兜底），不阻断消费端")
    void redisFailure_degradesToRecompute() {
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(submission(ts("2026-01-01T10:00:00")));
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of());
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));

        Map<Long, WrongQuestionJudgementCache.ScoredQuestion> result =
                cache.get(EXAM_ID, STUDENT_ID, () -> scored);

        assertEquals(scored, result, "Redis 故障仍应返回现算结果（可用性优先，不得抛给消费端）");
    }

    @Test
    @DisplayName("无已汇总答卷：走 loader 现算，不写缓存（无版本信号可判）")
    void noGradedSubmission_returnsLoaderResultWithoutCache() {
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(null);

        Map<Long, WrongQuestionJudgementCache.ScoredQuestion> result =
                cache.get(EXAM_ID, STUDENT_ID, () -> scored);

        assertEquals(scored, result);
        verify(valueOps, never()).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    private GradingSubmission submission(LocalDateTime updatedTime) {
        GradingSubmission s = new GradingSubmission();
        s.setId(9000L);
        s.setExamId(EXAM_ID);
        s.setStudentId(STUDENT_ID);
        s.setStatus(ExamSubmission.STATUS_GRADED);
        s.setTotalScore(new BigDecimal("60"));
        s.setUpdatedTime(updatedTime);
        return s;
    }

    private static LocalDateTime ts(String iso) {
        return LocalDateTime.parse(iso);
    }
}