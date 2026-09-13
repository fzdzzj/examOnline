package com.exam.score.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.score.dto.ReviewHandleRequest;
import com.exam.score.dto.ScoreReviewApplyRequest;
import com.exam.score.entity.ScoreReview;
import com.exam.score.service.ScoreReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成绩复核接口（spec score-review §5.4/§10.7）：
 * <ul>
 *   <li>申请——学生提交（成绩发布后 7 天内限 1 次，复核中隐藏成绩）；</li>
 *   <li>清单——教师按考试查复核申请（含状态/处理结果）；</li>
 *   <li>处理——教师同意（可调分）或驳回（恢复显示），处理后结束隐藏期。</li>
 * </ul>
 */
@RestController
@RequirePermission("score:view")
public class ScoreReviewController {

    private final ScoreReviewService scoreReviewService;

    public ScoreReviewController(ScoreReviewService scoreReviewService) {
        this.scoreReviewService = scoreReviewService;
    }

    /** 学生提交复核申请：每场限 1 次（幂等）、成绩发布后 7 天内。 */
    @PostMapping("/api/exams/{examId}/score-reviews")
    public ApiResponse<ScoreReview> apply(@PathVariable Long examId,
                                          @Valid @RequestBody ScoreReviewApplyRequest request) {
        return ApiResponse.success(scoreReviewService.apply(examId, request.getReason()));
    }

    /** 教师按考试查复核清单（含状态与处理结果）。 */
    @GetMapping("/api/exams/{examId}/score-reviews")
    @RequirePermission("exam:manage")
    public ApiResponse<List<ScoreReview>> listByExam(@RequestParam Long examId) {
        return ApiResponse.success(scoreReviewService.listByExam(examId));
    }

    /** 教师处理复核：AGREE 同意（可调分）/ REJECT 驳回（恢复显示）。 */
    @PostMapping("/api/score-reviews/{reviewId}/handle")
    @RequirePermission("exam:manage")
    public ApiResponse<Void> handle(@PathVariable Long reviewId,
                                    @Valid @RequestBody ReviewHandleRequest request) {
        scoreReviewService.handle(reviewId, request);
        return ApiResponse.success();
    }
}