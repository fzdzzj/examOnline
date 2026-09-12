package com.exam.taking.service;

import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.entity.ExamSubmitDedup;
import com.exam.submission.mapper.ExamSubmitDedupMapper;
import com.exam.submission.mq.ExamSubmitSender;
import com.exam.submission.service.ExamSubmissionService;
import com.exam.taking.dto.SubmitRequest;
import com.exam.taking.dto.SubmitResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交卷服务单元测试（spec「交卷幂等」需求）：Mockito 纯桩验证幂等分层逻辑——
 * 快速路径/锁竞争/防重表/CAS 裁决/MQ 失败兜底，不启动 Spring 上下文。
 */
@ExtendWith(MockitoExtension.class)
class ExamSubmitServiceTest {

    private static final long EXAM_ID = 1L;
    private static final long STUDENT_ID = 100L;
    private static final long SUBMISSION_ID = 500L;

    @Mock
    private ExamSubmissionService submissionService;
    @Mock
    private ExamSubmitDedupMapper dedupMapper;
    @Mock
    private ExamDraftService draftService;
    @Mock
    private ExamSubmitSender sender;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExamSubmitService submitService;
    private ExamSubmission inProgress;
    private ExamSubmission submitted;

    @BeforeEach
    void setUp() {
        submitService = new ExamSubmitService(submissionService, dedupMapper, draftService,
                sender, redisTemplate, objectMapper);
        ReflectionTestUtils.setField(submitService, "lockTtlSeconds", 30);

        // submit 走 SecurityUtil 取当前学生（ThreadLocal），单测中手工注入
        LoginUser student = new LoginUser();
        student.setId(STUDENT_ID);
        student.setRoleLevel(1);
        SecurityUtil.set(student);

        inProgress = new ExamSubmission();
        inProgress.setId(SUBMISSION_ID);
        inProgress.setExamId(EXAM_ID);
        inProgress.setStudentId(STUDENT_ID);
        inProgress.setStatus(ExamSubmission.STATUS_IN_PROGRESS);

        submitted = new ExamSubmission();
        submitted.setId(SUBMISSION_ID);
        submitted.setExamId(EXAM_ID);
        submitted.setStudentId(STUDENT_ID);
        submitted.setStatus(ExamSubmission.STATUS_SUBMITTED);
        submitted.setSubmitTime(LocalDateTime.now().minusSeconds(5));
        submitted.setSubmitType(ExamSubmission.SUBMIT_TYPE_MANUAL);
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    /** 首次交卷成功：锁 → 防重 → CAS → MQ 消息携带答案，返回已交卷。 */
    @Test
    void firstSubmitWinsAndSendsMessage() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(submissionService.getByExamStudent(EXAM_ID, STUDENT_ID)).thenReturn(inProgress);
        when(submissionService.casSubmitToSubmitted(eq(SUBMISSION_ID), any(LocalDateTime.class), anyInt()))
                .thenReturn(true);

        SubmitRequest request = new SubmitRequest();
        request.setAnswers(objectMapper.createObjectNode().put("11", "A"));
        SubmitResponse response = submitService.submit(EXAM_ID, request);

        assertEquals(ExamSubmission.STATUS_SUBMITTED, response.getStatus());
        assertEquals(ExamSubmission.SUBMIT_TYPE_MANUAL, response.getSubmitType());

        ArgumentCaptor<SubmitMessage> captor = ArgumentCaptor.forClass(SubmitMessage.class);
        verify(sender).send(captor.capture());
        assertEquals(SUBMISSION_ID, captor.getValue().getSubmissionId());
        assertTrue(captor.getValue().getAnswers().contains("11"), "消息应携带客户端答案");
        verify(dedupMapper).insert(any(ExamSubmitDedup.class));
    }

    /** 重复交卷幂等：已交卷走快速路径返回首次结果，不抢锁、不发消息。 */
    @Test
    void duplicateSubmitReturnsFirstResult() {
        when(submissionService.getByExamStudent(EXAM_ID, STUDENT_ID)).thenReturn(submitted);

        SubmitResponse response = submitService.submit(EXAM_ID, new SubmitRequest());

        assertEquals(submitted.getSubmitTime(), response.getSubmitTime());
        verify(sender, never()).send(any());
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    /** 锁竞争：并发路径持锁时短轮询等其完成，幂等返回首次结果而非报错。 */
    @Test
    void lockContentionWaitsThenReturnsIdempotentResult() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        // 第一次：幂等快速路径读到进行中；第二次：等锁轮询读到已交卷
        when(submissionService.getByExamStudent(EXAM_ID, STUDENT_ID)).thenReturn(inProgress, submitted);

        SubmitResponse response = submitService.submit(EXAM_ID, new SubmitRequest());

        assertEquals(submitted.getSubmitTime(), response.getSubmitTime());
        verify(sender, never()).send(any());
    }

    /** CAS 影响 0 行（并发赢家已迁移）：重读后幂等返回首次结果。 */
    @Test
    void casLoserReturnsFirstResult() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        // 快速路径 → 锁内二次确认 → CAS 失败后重读
        when(submissionService.getByExamStudent(EXAM_ID, STUDENT_ID)).thenReturn(inProgress, inProgress, submitted);
        when(submissionService.casSubmitToSubmitted(eq(SUBMISSION_ID), any(LocalDateTime.class), anyInt()))
                .thenReturn(false);

        SubmitResponse response = submitService.submit(EXAM_ID, new SubmitRequest());

        assertEquals(submitted.getSubmitTime(), response.getSubmitTime());
        verify(sender, never()).send(any());
    }

    /** MQ 发送失败：答案暂存草稿（对账补发的数据源），向学生抛 500 提示勿重复提交。 */
    @Test
    void sendFailureFallsBackToDraft() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(submissionService.getByExamStudent(EXAM_ID, STUDENT_ID)).thenReturn(inProgress);
        when(submissionService.casSubmitToSubmitted(eq(SUBMISSION_ID), any(LocalDateTime.class), anyInt()))
                .thenReturn(true);
        org.mockito.Mockito.doThrow(new AmqpException("broker down")).when(sender).send(any());

        SubmitRequest request = new SubmitRequest();
        request.setAnswers(objectMapper.createObjectNode().put("11", "A"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> submitService.submit(EXAM_ID, request));
        assertEquals(ResponseCode.INTERNAL_ERROR.getCode(), e.getCode());
        verify(draftService).overwriteAnswers(eq(EXAM_ID), eq(STUDENT_ID), anyString());
    }

    /** 前端倒计时归零：submitType=COUNTDOWN_ZERO 映射为来源 2。 */
    @Test
    void countdownZeroMapsToSubmitType2() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(submissionService.getByExamStudent(EXAM_ID, STUDENT_ID)).thenReturn(inProgress);
        when(submissionService.casSubmitToSubmitted(eq(SUBMISSION_ID), any(LocalDateTime.class), anyInt()))
                .thenReturn(true);

        SubmitRequest request = new SubmitRequest();
        request.setAnswers(objectMapper.createObjectNode());
        request.setSubmitType(SubmitRequest.TYPE_COUNTDOWN_ZERO);
        SubmitResponse response = submitService.submit(EXAM_ID, request);

        assertEquals(ExamSubmission.SUBMIT_TYPE_COUNTDOWN_ZERO, response.getSubmitType());
        ArgumentCaptor<SubmitMessage> captor = ArgumentCaptor.forClass(SubmitMessage.class);
        verify(sender).send(captor.capture());
        assertEquals(ExamSubmission.SUBMIT_TYPE_COUNTDOWN_ZERO, captor.getValue().getSubmitType());
    }
}
