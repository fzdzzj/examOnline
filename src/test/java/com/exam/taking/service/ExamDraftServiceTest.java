package com.exam.taking.service;

import com.exam.anticheat.service.BehaviorEventCollectService;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.monitoring.service.OnlinePresenceService;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.taking.dto.AutoSaveRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 草稿保存边界单测（change harden-draft-and-grading-boundaries，Mockito 隔离、无 Spring 上下文）：
 *
 * <ul>
 *   <li>answers 序列化 UTF-8 字节 &gt; 64KB（65,536 字节）→ BAD_REQUEST 拒绝，
 *       且超大 payload 不写入 Redis 键空间；</li>
 *   <li>恰好 64KB 属边界内 → 正常受理（钳制是「大于即拒」，不是大于等于）；</li>
 *   <li>小体积正常受理：版本冲突合并、心跳、TTL 既有语义零改动。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ExamDraftServiceTest {

    private static final Long EXAM_ID = 10L;
    private static final Long STUDENT_ID = 101L;

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private BehaviorEventCollectService eventCollectService;
    @Mock
    private ExamSubmissionMapper submissionMapper;
    @Mock
    private OnlinePresenceService presenceService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExamDraftService service;

    @BeforeEach
    void setUp() {
        service = new ExamDraftService(redisTemplate, objectMapper, eventCollectService,
                submissionMapper, presenceService);
        ExamSubmission submission = new ExamSubmission();
        submission.setExamId(EXAM_ID);
        submission.setStudentId(STUDENT_ID);
        submission.setStatus(ExamSubmission.STATUS_IN_PROGRESS);
        submission.setDeadlineTime(LocalDateTime.now().plusHours(1));
        when(submissionMapper.selectByExamStudent(EXAM_ID, STUDENT_ID)).thenReturn(submission);
    }

    private AutoSaveRequest request(JsonNode answers) {
        AutoSaveRequest request = new AutoSaveRequest();
        request.setVersion(1);
        request.setAnswers(answers);
        return request;
    }

    @Test
    @DisplayName("超大草稿被拒：序列化超 64KB 抛 BAD_REQUEST 且不写入 Redis")
    void oversizedDraftRejectedWithoutWritingRedis() throws Exception {
        // {"a":"xxxx…x"} 序列化 = 8 + N 字节：N=65529 → 65537 字节（> 65536，越界 1 字节）
        JsonNode answers = objectMapper.createObjectNode().put("a", "x".repeat(65529));
        assertEquals(65537, objectMapper.writeValueAsBytes(answers).length,
                "构造的 payload 须恰好越界 1 字节，否则本用例失去判别力");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(ExamDraftService.KEY_PREFIX + EXAM_ID + ":" + STUDENT_ID))
                .thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.save(EXAM_ID, STUDENT_ID, request(answers)));

        assertEquals(ResponseCode.BAD_REQUEST.getCode(), ex.getCode());
        assertEquals("草稿内容超过大小上限 (64KB)", ex.getMessage());
        // spec AND 子句：超大草稿 SHALL NOT 写入 Redis（读取/冲突判定不受限）
        verify(valueOperations, never()).set(anyString(), anyString(), any(java.time.Duration.class));
        verifyNoInteractions(presenceService);
    }

    @Test
    @DisplayName("恰好 64KB 属边界内：正常受理并写入 Redis")
    void draftOfExactly64kbStillAccepted() throws Exception {
        // {"a":"xxxx…x"} 序列化 = 8 + N 字节：N=65528 → 恰好 65536 字节（不大于上限，须受理）
        JsonNode answers = objectMapper.createObjectNode().put("a", "x".repeat(65528));
        assertEquals(65536, objectMapper.writeValueAsBytes(answers).length,
                "构造的 payload 须恰好等于上限，否则本用例失去判别力");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(ExamDraftService.KEY_PREFIX + EXAM_ID + ":" + STUDENT_ID))
                .thenReturn(null);

        var response = service.save(EXAM_ID, STUDENT_ID, request(answers));

        assertTrue(response.isAccepted(), "恰好 64KB 的草稿不得被拒");
        assertEquals(1, response.getVersion());
        verify(valueOperations).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test
    @DisplayName("小体积正常受理：既有保存/心跳语义零改动")
    void smallDraftStillAcceptedWithExistingSemantics() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(ExamDraftService.KEY_PREFIX + EXAM_ID + ":" + STUDENT_ID))
                .thenReturn(null);

        var response = service.save(EXAM_ID, STUDENT_ID, request(
                objectMapper.createObjectNode().put("q1", "A")));

        assertTrue(response.isAccepted());
        verify(valueOperations).set(anyString(), anyString(), any(java.time.Duration.class));
        verify(presenceService).touch(EXAM_ID, STUDENT_ID);
        verifyNoInteractions(eventCollectService);
    }
}
