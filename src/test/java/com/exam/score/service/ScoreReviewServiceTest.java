package com.exam.score.service;

import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.score.dto.ReviewHandleRequest;
import com.exam.score.entity.ScoreAuditLog;
import com.exam.score.entity.ScoreReview;
import com.exam.score.mapper.ScoreAuditLogMapper;
import com.exam.score.mapper.ScoreReviewMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成绩复核单测（spec score-review §5.4/§10.7）：
 * 覆盖限 1 次（唯一索引幂等 + 预查友好提示）、7 天超期拒绝、复核中隐藏、同意调分/驳回恢复显示。
 * 限次/限时的 DB 唯一约束封印由存量为真（uk_review_exam_student），此处验证服务端逻辑正确。
 */
@ExtendWith(MockitoExtension.class)
class ScoreReviewServiceTest {

    @Mock
    private ScoreReviewMapper reviewMapper;
    @Mock
    private ExamMapper examMapper;
    @Mock
    private GradingSubmissionMapper gradingMapper;
    @Mock
    private ScoreAuditLogMapper auditLogMapper;

    @InjectMocks
    private ScoreReviewService service;

    @AfterEach
    void clearSecurity() {
        SecurityUtil.clear();
    }

    private void setUser(Long id, int roleLevel) {
        LoginUser user = new LoginUser();
        user.setId(id);
        user.setRoleLevel(roleLevel);
        SecurityUtil.set(user);
    }

    private Exam publishedExam() {
        Exam exam = new Exam();
        exam.setId(10L);
        exam.setTitle("考试");
        exam.setStatus(Exam.STATUS_PUBLISHED);
        exam.setCreatedBy(5L);
        return exam;
    }

    private ScoreAuditLog publishAudit(LocalDateTime createdTime) {
        ScoreAuditLog audit = new ScoreAuditLog();
        audit.setExamId(10L);
        audit.setAction(ScoreAuditLog.ACTION_PUBLISH);
        audit.setCreatedTime(createdTime);
        return audit;
    }

    // ==================== 复核申请：限次 + 限时（§10.7） ====================

    @Test
    void applyWithinWindowSucceeds() {
        setUser(1L, 1);
        when(examMapper.selectById(10L)).thenReturn(publishedExam());
        when(auditLogMapper.selectList(any())).thenReturn(List.of(publishAudit(LocalDateTime.now().minusDays(1))));
        when(reviewMapper.selectCount(any())).thenReturn(0L);
        when(reviewMapper.insert(any(ScoreReview.class))).thenReturn(1);

        ScoreReview review = service.apply(10L, "分数有疑义");

        verify(reviewMapper).insert(any(ScoreReview.class));
        assertEquals(ScoreReview.STATUS_PENDING, review.getStatus());
        assertEquals(1L, review.getStudentId());
        assertEquals(10L, review.getExamId());
        assertNotNull(review.getApplyTime());
        assertEquals("分数有疑义", review.getReason());
    }

    @Test
    void applyRejectsDuplicateByReasonLimit() {
        setUser(1L, 1);
        when(examMapper.selectById(10L)).thenReturn(publishedExam());
        when(auditLogMapper.selectList(any())).thenReturn(List.of(publishAudit(LocalDateTime.now().minusDays(1))));
        // 已有 1 条复核记录 → 限 1 次（§10.7），走预查友好提示直接拒绝
        when(reviewMapper.selectCount(any())).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class, () -> service.apply(10L, "再次申请"));
        assertEquals(ResponseCode.DATA_ALREADY_EXISTS.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("已提交过"));
        verify(reviewMapper, never()).insert(any(ScoreReview.class));
    }

    @Test
    void applyDuplicateUniqueIndexConflictTranslatedToFriendly() {
        setUser(1L, 1);
        when(examMapper.selectById(10L)).thenReturn(publishedExam());
        when(auditLogMapper.selectList(any())).thenReturn(List.of(publishAudit(LocalDateTime.now().minusDays(1))));
        // 预查返回 0，但并发下另一个线程恰好已插入 → 唯一索引冲突 → 转友好提示（幂等兜底）
        when(reviewMapper.selectCount(any())).thenReturn(0L);
        when(reviewMapper.insert(any(ScoreReview.class))).thenThrow(new DataIntegrityViolationException("Duplicate uk_review_exam_student"));

        BusinessException e = assertThrows(BusinessException.class, () -> service.apply(10L, "并发重复申请"));
        assertEquals(ResponseCode.DATA_ALREADY_EXISTS.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("已提交过"));
    }

    @Test
    void applyRejectedWhenOver7Days() {
        setUser(1L, 1);
        when(examMapper.selectById(10L)).thenReturn(publishedExam());
        // 发布已是 8 天前 → 超期（§10.7：成绩发布后 7 天内才可申请）
        when(auditLogMapper.selectList(any())).thenReturn(List.of(publishAudit(LocalDateTime.now().minusDays(8))));

        BusinessException e = assertThrows(BusinessException.class, () -> service.apply(10L, "超期申请"));
        assertEquals(ResponseCode.BAD_REQUEST.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("7 天"));
        verify(reviewMapper, never()).insert(any(ScoreReview.class));
    }

    @Test
    void applyRejectedWhenScoreNotPublished() {
        setUser(1L, 1);
        Exam exam = publishedExam();
        exam.setStatus(Exam.STATUS_GRADED); // 未发布不可复核
        when(examMapper.selectById(10L)).thenReturn(exam);

        BusinessException e = assertThrows(BusinessException.class, () -> service.apply(10L, "未发布"));
        assertEquals(ResponseCode.BAD_REQUEST.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("待发布"));
        verify(reviewMapper, never()).insert(any(ScoreReview.class));
    }

    // ==================== 复核中隐藏（§5.4） ====================

    @Test
    void hasPendingReviewTrueWhenStatusPendingOrProcessing() {
        when(reviewMapper.selectCount(any())).thenReturn(1L);
        assertTrue(service.hasPendingReview(10L, 1L));
    }

    @Test
    void hasPendingReviewFalseWhenNoActiveReview() {
        when(reviewMapper.selectCount(any())).thenReturn(0L);
        assertFalse(service.hasPendingReview(10L, 1L));
    }

    // ==================== 处理：同意调分 / 驳回恢复显示 ====================

    @Test
    void handleAgreeAdjustsScoreAndUpdatesDisplay() {
        setUser(5L, 2);
        ScoreReview review = new ScoreReview();
        review.setId(1L);
        review.setExamId(10L);
        review.setStudentId(1L);
        review.setStatus(ScoreReview.STATUS_PENDING);
        when(reviewMapper.selectById(1L)).thenReturn(review);
        when(examMapper.selectById(10L)).thenReturn(publishedExam());
        GradingSubmission submission = new GradingSubmission();
        submission.setId(100L);
        when(gradingMapper.selectOne(any())).thenReturn(submission);

        ReviewHandleRequest request = new ReviewHandleRequest();
        request.setAction("AGREE");
        request.setAdjustedTotalScore(new BigDecimal("21.0"));
        request.setReason("复核确认漏分的简答，追加");
        service.handle(1L, request);

        // 调分：更新答卷 total_score → 学生端显示新成绩
        ArgumentCaptor<GradingSubmission> cap = ArgumentCaptor.forClass(GradingSubmission.class);
        verify(gradingMapper).updateById(cap.capture());
        assertEquals(100L, cap.getValue().getId());
        assertEquals(0, cap.getValue().getTotalScore().compareTo(new BigDecimal("21.0")));

        // 复核落结论：已同意 + 处理人/时间，结束隐藏期
        assertEquals(ScoreReview.STATUS_AGREED, review.getStatus());
        assertEquals(5L, review.getHandlerId());
        assertNotNull(review.getHandleTime());
    }

    @Test
    void handleRejectRestoresOriginalDisplayWithoutScoreChange() {
        setUser(5L, 2);
        ScoreReview review = new ScoreReview();
        review.setId(2L);
        review.setExamId(10L);
        review.setStudentId(1L);
        review.setStatus(ScoreReview.STATUS_PROCESSING);
        when(reviewMapper.selectById(2L)).thenReturn(review);
        when(examMapper.selectById(10L)).thenReturn(publishedExam());

        ReviewHandleRequest request = new ReviewHandleRequest();
        request.setAction("REJECT");
        request.setReason("核对无误，维持原成绩");
        service.handle(2L, request);

        // 驳回：不调分、不触碰答卷成绩（仅结束进行中复核 → 隐藏自动解除，恢复原成绩显示）
        verify(gradingMapper, never()).updateById(any(GradingSubmission.class));
        assertEquals(ScoreReview.STATUS_REJECTED, review.getStatus());
        assertEquals("核对无误，维持原成绩", review.getResult());
    }

    @Test
    void handleRejectedWhenAlreadyProcessed() {
        setUser(5L, 2);
        ScoreReview review = new ScoreReview();
        review.setId(3L);
        review.setExamId(10L);
        review.setStatus(ScoreReview.STATUS_REJECTED); // 已处理
        when(reviewMapper.selectById(3L)).thenReturn(review);

        ReviewHandleRequest request = new ReviewHandleRequest();
        request.setAction("AGREE");
        BusinessException e = assertThrows(BusinessException.class, () -> service.handle(3L, request));
        assertEquals(ResponseCode.BAD_REQUEST.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("已处理"));
    }
}