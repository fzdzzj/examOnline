package com.exam.grading.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.grading.dto.GradingProgressResponse;
import com.exam.grading.dto.GradingRunResponse;
import com.exam.grading.service.ExamGradingService;
import com.exam.grading.service.GradingQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 教师判分接口（add-grading-score，W7）：
 * <ul>
 *   <li>整场判分——按考试快照标准答案自动判客观题（单选/多选/判断），
 *       简答建批改行（关键词初判提示分）；失败答卷就地标记不中断整场；</li>
 *   <li>判分进度——客观判分与主观批改两个维度的完成度总览。</li>
 * </ul>
 * 类级 {@code @RequirePermission("exam:manage")}（TEACHER/ADMIN）；教师只能操作本人考试
 * （Service 内 OwnershipGuard 校验考试归属）。
 */
@RestController
@RequestMapping("/api/exams/{examId}/grading")
@RequirePermission("exam:manage")
public class GradingController {

    private final ExamGradingService examGradingService;
    private final GradingQueryService gradingQueryService;

    public GradingController(ExamGradingService examGradingService, GradingQueryService gradingQueryService) {
        this.examGradingService = examGradingService;
        this.gradingQueryService = gradingQueryService;
    }

    /**
     * 触发整场判分（幂等：重复调用即整场重判；客观分覆盖写，教师已批主观分不动）。
     * 返回失败答卷清单——判分失败只标记不阻断（spec「判分失败处理」场景）。
     */
    @PostMapping("/run")
    public ApiResponse<GradingRunResponse> run(@PathVariable Long examId) {
        ExamGradingService.RunStats stats = examGradingService.runExamGrading(examId);
        List<GradingRunResponse.FailureItem> failures = stats.failures().stream()
                .map(outcome -> new GradingRunResponse.FailureItem(
                        outcome.submissionId(), outcome.studentId(), outcome.error()))
                .toList();
        return ApiResponse.success(new GradingRunResponse(stats.total(), stats.success(), stats.failed(), failures));
    }

    /** 判分进度总览：客观判分/主观批改完成度。 */
    @GetMapping("/progress")
    public ApiResponse<GradingProgressResponse> progress(@PathVariable Long examId) {
        return ApiResponse.success(gradingQueryService.progress(examId));
    }
}
