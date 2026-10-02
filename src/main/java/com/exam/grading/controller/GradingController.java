package com.exam.grading.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.grading.dto.GradingProgressResponse;
import com.exam.grading.dto.GradingRunResponse;
import com.exam.grading.dto.ManualScoreRequest;
import com.exam.grading.dto.SubjectiveGradePageResponse;
import com.exam.grading.dto.SubjectiveGradeRow;
import com.exam.grading.dto.SubjectiveQuestionItem;
import com.exam.grading.dto.SubjectiveScoreRequest;
import com.exam.grading.service.ExamGradingService;
import com.exam.grading.service.GradingQueryService;
import com.exam.grading.service.SubjectiveGradingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 教师判分接口（add-grading-score，W7）：
 * <ul>
 *   <li>整场判分——按考试快照标准答案自动判客观题（单选/多选/判断），
 *       简答建批改行（关键词初判提示分）；失败答卷就地标记不中断整场；</li>
 *   <li>判分进度——客观判分与主观批改两个维度的完成度总览；</li>
 *   <li>失败处理——单份重判（引擎重算）与手动给分（教师裁定兜底），
 *       对应 spec「标记失败可重判」场景；</li>
 *   <li>简答批改工作台——按题列出全部学生逐题批改，乐观锁防并发覆盖。</li>
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
    private final SubjectiveGradingService subjectiveGradingService;

    public GradingController(ExamGradingService examGradingService,
                             GradingQueryService gradingQueryService,
                             SubjectiveGradingService subjectiveGradingService) {
        this.examGradingService = examGradingService;
        this.gradingQueryService = gradingQueryService;
        this.subjectiveGradingService = subjectiveGradingService;
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

    /**
     * 单份重判：对判分失败或需要重算的答卷重跑判分引擎（幂等，主观批改结果不动）。
     * 重判仍失败时返回失败原因（grading_status=2 已落库）。
     */
    @PostMapping("/submissions/{submissionId}/rejudge")
    public ApiResponse<GradingRunResponse.FailureItem> rejudge(@PathVariable Long examId,
                                                               @PathVariable Long submissionId) {
        var outcome = examGradingService.rejudge(examId, submissionId);
        return ApiResponse.success(new GradingRunResponse.FailureItem(
                outcome.submissionId(), outcome.studentId(), outcome.error()));
    }

    /**
     * 手动给分：判分引擎反复失败的答卷，教师直接裁定客观题总分（不得超过客观题满分）。
     */
    @PostMapping("/submissions/{submissionId}/manual-score")
    public ApiResponse<Void> manualScore(@PathVariable Long examId,
                                         @PathVariable Long submissionId,
                                         @Valid @RequestBody ManualScoreRequest request) {
        examGradingService.manualScore(examId, submissionId, request.getObjectiveScore());
        return ApiResponse.success();
    }

    // ==================== 简答批改工作台（spec「简答批改」需求） ====================

    /** 待批题目清单：快照简答题 + 各题批改进度（已批/需批人数）。 */
    @GetMapping("/subjective/questions")
    public ApiResponse<List<SubjectiveQuestionItem>> subjectiveQuestions(@PathVariable Long examId) {
        return ApiResponse.success(subjectiveGradingService.listQuestions(examId));
    }

    /**
     * 批改工作台：同题列出学生（答案/初判提示分/终分/评语/版本号），响应恒为分页信封
     * {rows, total, graded}（add-subjective-grading-pagination）。
     * page/size/onlyUngraded/name/submissionId 全部可选：page/size 缺省返回全量；
     * onlyUngraded/name 筛选下沉服务端；submissionId 单行取数（至多 1 行）专供冲突回填。
     */
    @GetMapping("/subjective")
    public ApiResponse<SubjectiveGradePageResponse> subjectiveRows(@PathVariable Long examId,
                                                                   @RequestParam Long questionId,
                                                                   @RequestParam(required = false) Integer page,
                                                                   @RequestParam(required = false) Integer size,
                                                                   @RequestParam(required = false) Boolean onlyUngraded,
                                                                   @RequestParam(required = false) String name,
                                                                   @RequestParam(required = false) Long submissionId) {
        return ApiResponse.success(subjectiveGradingService.listRows(
                examId, questionId, page, size, onlyUngraded, name, submissionId));
    }

    /**
     * 提交批改（终分 + 评语，逐题；expectedVersion 乐观锁防并发覆盖）。
     * 已批题目再次提交即"打回重批"：批改人/时间随之更新，留痕可追溯。
     */
    @PostMapping("/subjective/save")
    public ApiResponse<SubjectiveGradeRow> saveSubjectiveScore(@PathVariable Long examId,
                                                               @Valid @RequestBody SubjectiveScoreRequest request) {
        return ApiResponse.success(subjectiveGradingService.saveScore(examId, request));
    }
}
