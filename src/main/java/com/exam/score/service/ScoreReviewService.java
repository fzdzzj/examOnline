package com.exam.score.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.OwnershipGuard;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 成绩复核服务（spec score-review §5.4/§10.7；add-class-and-post-exam-closure 阶段 4）：
 * <ul>
 *   <li>申请：成绩发布后限时 7 天、限场 1 次（唯一索引幂等 + DataIntegrityViolationException 转友好提示）；</li>
 *   <li>隐藏：提供 {@link #hasPendingReview} 供学生查成绩处判定——有进行中复核则隐藏分数显示"复核中"
 *       （防"看了分数再申请"，§5.4）；</li>
 *   <li>处理：教师同意（可调分，调分后更新成绩显示）或驳回（恢复原成绩显示）。</li>
 * </ul>
 *
 * <p>复核状态与成绩发布状态机（exam.status）正交：本服务只读写 score_review，不触碰成绩发布/撤回
 * 的状态 CAS；成绩的发布与否仅作为申请的准入前提（须已发布才能申请）。
 */
@Slf4j
@Service
public class ScoreReviewService {

    private final ScoreReviewMapper reviewMapper;
    private final ExamMapper examMapper;
    private final GradingSubmissionMapper gradingMapper;
    private final ScoreAuditLogMapper auditLogMapper;

    public ScoreReviewService(ScoreReviewMapper reviewMapper,
                              ExamMapper examMapper,
                              GradingSubmissionMapper gradingMapper,
                              ScoreAuditLogMapper auditLogMapper) {
        this.reviewMapper = reviewMapper;
        this.examMapper = examMapper;
        this.gradingMapper = gradingMapper;
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 提交复核申请（学生）。
     *
     * <p>Why 限 1 次 + 7 天（§10.7）：每场考试限 1 次并限定成绩发布后 7 天内可申请——
     * 防止学生反复试探、以及成绩长期处于"可争议"状态，兼顾学生申诉窗口与成绩稳定性。
     * 幂等：先查给友好提示，唯一索引 uk_review_exam_student 是并发重复提交的最终兜底，
     * 冲突（DataIntegrityViolationException）转"已申请"友好提示。
     */
    @Transactional(rollbackFor = Exception.class)
    public ScoreReview apply(Long examId, String reason) {
        Long studentId = SecurityUtil.getUserId();
        if (studentId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        if (exam.getStatus() != Exam.STATUS_PUBLISHED) {
            // 复核只针对"已发布"成绩；未发布/已撤回统一"成绩待发布"，不开放复核
            throw new BusinessException(ResponseCode.BAD_REQUEST, "成绩待发布，不能申请复核");
        }
        // 7 天窗口起点 = 最近一次发布成绩的时间（审计记录 created_time）；
        // 撤销后重新发布会追加新的 PUBLISH 审计，窗口随之以最新发布重算，语义正确。
        LocalDateTime publishTime = latestPublishTime(examId);
        if (publishTime == null) {
            // 已发布必有 PUBLISH 审计，理论不可达；兜底防后续计算 NPE
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "查不到成绩发布时间");
        }
        if (publishTime.plusDays(ScoreReview.WINDOW_DAYS).isBefore(LocalDateTime.now())) {
            // 超期拒绝（§10.7）：成绩发布已超过 7 天，不再受理复核
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "已超过成绩发布后 " + ScoreReview.WINDOW_DAYS + " 天，无法申请复核");
        }
        // 限 1 次：先查一次（正常路径即友好提示，不依赖 DB 异常）；唯一索引兜底并发竞态
        Long exists = reviewMapper.selectCount(Wrappers.<ScoreReview>lambdaQuery()
                .eq(ScoreReview::getExamId, examId)
                .eq(ScoreReview::getStudentId, studentId));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResponseCode.DATA_ALREADY_EXISTS, "已提交过成绩复核申请");
        }

        ScoreReview review = new ScoreReview();
        review.setExamId(examId);
        review.setStudentId(studentId);
        review.setStatus(ScoreReview.STATUS_PENDING);
        review.setReason(reason);
        review.setApplyTime(LocalDateTime.now());
        try {
            reviewMapper.insert(review);
        } catch (DataIntegrityViolationException e) {
            // 并发下同时插入触发唯一索引冲突：幂等转友好提示，不向客户端暴露 SQL 细节
            log.warn("成绩复核重复申请被唯一索引拦截: exam={} student={}", examId, studentId);
            throw new BusinessException(ResponseCode.DATA_ALREADY_EXISTS, "已提交过成绩复核申请");
        }
        log.info("成绩复核申请: exam={} student={}", examId, studentId);
        return review;
    }

    /**
     * 判断学生对该考试是否有"进行中"（待处理/处理中）的复核申请。
     * <p>供学生查本人成绩处调用（§5.4：复核中隐藏成绩，防"看了分数再申请"）。
     * 只读 score_review，与成绩发布状态机正交。
     */
    public boolean hasPendingReview(Long examId, Long studentId) {
        Long count = reviewMapper.selectCount(Wrappers.<ScoreReview>lambdaQuery()
                .eq(ScoreReview::getExamId, examId)
                .eq(ScoreReview::getStudentId, studentId)
                .in(ScoreReview::getStatus,
                        ScoreReview.STATUS_PENDING, ScoreReview.STATUS_PROCESSING));
        return count != null && count > 0;
    }

    /**
     * 教师处理复核：同意（可调分，调分后更新成绩显示）或驳回（恢复原成绩显示）。
     * <p>流程：校验归属教师 → 调分（同意时）→ 落结论，结束进行中的隐藏期。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handle(Long reviewId, ReviewHandleRequest request) {
        if (request == null || request.getAction() == null || request.getAction().isBlank()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "处理动作必填（AGREE/REJECT）");
        }
        ScoreReview review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "复核申请不存在");
        }
        if (review.getStatus() != ScoreReview.STATUS_PENDING
                && review.getStatus() != ScoreReview.STATUS_PROCESSING) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "该复核申请已处理");
        }
        Exam exam = examMapper.selectById(review.getExamId());
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        // 归属教师（或 ADMIN）才可处理本场复核，防越权
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");

        String action = request.getAction().trim().toUpperCase();
        if ("AGREE".equals(action)) {
            // 同意：可调整分数，调分后更新成绩（§复核处理「同意调整分数」）
            if (request.getAdjustedTotalScore() != null) {
                GradingSubmission submission = gradingMapper.selectOne(Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, review.getExamId())
                        .eq(GradingSubmission::getStudentId, review.getStudentId()));
                if (submission != null) {
                    GradingSubmission update = new GradingSubmission();
                    update.setId(submission.getId());
                    update.setTotalScore(request.getAdjustedTotalScore());
                    gradingMapper.updateById(update); // 只更新总分 → 学生端显示新成绩
                }
            }
            review.setStatus(ScoreReview.STATUS_AGREED);
        } else if ("REJECT".equals(action)) {
            // 驳回：不调分，仅结束进行中复核 → 隐藏自动解除，恢复原成绩显示
            review.setStatus(ScoreReview.STATUS_REJECTED);
        } else {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "非法的处理动作（仅支持 AGREE/REJECT）");
        }
        review.setResult(request.getReason());
        review.setHandlerId(SecurityUtil.getUserId());
        review.setHandleTime(LocalDateTime.now());
        reviewMapper.updateById(review);
        log.info("成绩复核处理: review={} status={} exam={} student={}",
                reviewId, review.getStatus(), review.getExamId(), review.getStudentId());
    }

    /** 教师按考试查复核清单（含状态与处理结果，支撑处理动作的定位）。 */
    public List<ScoreReview> listByExam(Long examId) {
        // 只取归属校验的副作用：后续按 examId 过滤查询，无需考试实体本身
        requireOwnedExam(examId);
        return reviewMapper.selectList(Wrappers.<ScoreReview>lambdaQuery()
                .eq(ScoreReview::getExamId, examId)
                .orderByAsc(ScoreReview::getApplyTime));
    }

    /** 最近一次成绩发布审计时间 = 7 天窗口起算点（不存在返回 null）。 */
    private LocalDateTime latestPublishTime(Long examId) {
        List<ScoreAuditLog> audits = auditLogMapper.selectList(
                Wrappers.<ScoreAuditLog>lambdaQuery()
                        .eq(ScoreAuditLog::getExamId, examId)
                        .eq(ScoreAuditLog::getAction, ScoreAuditLog.ACTION_PUBLISH)
                        .orderByDesc(ScoreAuditLog::getCreatedTime)
                        .last("LIMIT 1"));
        return audits.isEmpty() ? null : audits.get(0).getCreatedTime();
    }

    private Exam requireOwnedExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");
        return exam;
    }
}