package com.exam.score.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.auth.security.RequireRole;
import com.exam.auth.security.RoleHierarchy;
import com.exam.common.ApiResponse;
import com.exam.score.dto.BatchScoreRequest;
import com.exam.score.dto.MyScoreResponse;
import com.exam.score.dto.ScoreActionItem;
import com.exam.score.dto.ScorePreviewResponse;
import com.exam.score.service.ScoreService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成绩汇总/发布/撤回/查询接口（add-grading-score，W7）：
 * <ul>
 *   <li>汇总——教师触发，客观+主观=总分（未批简答按 0 分并标记部分批改）；</li>
 *   <li>预览——发布前核对各学生成绩与排名（并列同名次 1,2,2,4）；</li>
 *   <li>发布——已批改→已发布，支持批量，学生端立即可见；</li>
 *   <li>撤回——仅管理员（§5.3），原因必填 + 审计留痕，学生端立即隐藏；</li>
 *   <li>学生查询——仅本人成绩（§9.10），未发布统一"成绩待发布"。</li>
 * </ul>
 */
@RestController
@RequirePermission("score:view")
public class ScoreController {

    private final ScoreService scoreService;

    public ScoreController(ScoreService scoreService) {
        this.scoreService = scoreService;
    }

    /** 成绩汇总（幂等：重判/补批后可重复执行刷新总分与部分批改标记）。 */
    @PostMapping("/api/exams/{examId}/scores/summarize")
    @RequirePermission("exam:manage")
    public ApiResponse<ScoreService.SummarizeStats> summarize(@PathVariable Long examId) {
        return ApiResponse.success(scoreService.summarize(examId));
    }

    /** 发布前预览：各学生成绩明细 + 排名（已发布状态下可复核当前榜单）。 */
    @GetMapping("/api/exams/{examId}/scores/publish-preview")
    @RequirePermission("exam:manage")
    public ApiResponse<ScorePreviewResponse> publishPreview(@PathVariable Long examId) {
        return ApiResponse.success(scoreService.publishPreview(examId));
    }

    /** 批量发布：单场失败不影响其余（部分成功语义），逐场返回结果。 */
    @PostMapping("/api/scores/publish")
    @RequirePermission("exam:manage")
    public ApiResponse<List<ScoreActionItem>> publish(@Valid @RequestBody BatchScoreRequest request) {
        return ApiResponse.success(scoreService.publish(request.getExamIds()));
    }

    /**
     * 批量撤回：仅管理员（§5.3），原因必填（审计要求），撤回后学生端立即隐藏成绩。
     */
    @PostMapping("/api/scores/revoke")
    @RequireRole(RoleHierarchy.ADMIN)
    public ApiResponse<List<ScoreActionItem>> revoke(@Valid @RequestBody BatchScoreRequest request) {
        return ApiResponse.success(scoreService.revoke(request.getExamIds(), request.getReason()));
    }

    /** 学生查自己成绩：成绩未发布时统一返回"成绩待发布"。 */
    @GetMapping("/api/scores/my")
    public ApiResponse<MyScoreResponse> myScore(@RequestParam Long examId) {
        return ApiResponse.success(scoreService.myScore(examId));
    }
}
