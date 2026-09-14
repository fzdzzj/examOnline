package com.exam.score.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.auth.security.RequireRole;
import com.exam.auth.security.RoleHierarchy;
import com.exam.common.ApiResponse;
import com.exam.score.dto.BatchScoreRequest;
import com.exam.score.dto.MyScoreResponse;
import com.exam.score.dto.ScoreActionItem;
import com.exam.score.dto.ScorePreviewResponse;
import com.exam.score.service.ScoreExportService;
import com.exam.score.service.ScoreService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 成绩汇总/发布/撤回/导出/查询接口（add-grading-score，W7）：
 * <ul>
 *   <li>汇总——教师触发，客观+主观=总分（未批简答按 0 分并标记部分批改）；</li>
 *   <li>预览——发布前核对各学生成绩与排名（并列同名次 1,2,2,4）；</li>
 *   <li>发布——已批改→已发布，支持批量，学生端立即可见；</li>
 *   <li>撤回——仅管理员（§5.3），原因必填 + 审计留痕，学生端立即隐藏；</li>
 *   <li>导出——全班成绩单/逐题明细/题目统计 Excel（SXSSF 流式）+ 个人成绩单 Excel/PDF；</li>
 *   <li>学生查询——仅本人成绩（§9.10），未发布统一"成绩待发布"。</li>
 * </ul>
 */
@RestController
@RequirePermission("score:view")
public class ScoreController {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ScoreService scoreService;
    private final ScoreExportService scoreExportService;

    public ScoreController(ScoreService scoreService, ScoreExportService scoreExportService) {
        this.scoreService = scoreService;
        this.scoreExportService = scoreExportService;
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

    // ==================== 成绩导出（spec「成绩导出」需求） ====================

    /** 全班成绩单 Excel（SXSSF 流式写，防 OOM）。 */
    @GetMapping("/api/exams/{examId}/scores/export/class-sheet")
    @RequirePermission("exam:manage")
    public ResponseEntity<byte[]> exportClassSheet(@PathVariable Long examId) {
        return attachment(examId, "全班成绩单", ".xlsx", XLSX_MEDIA_TYPE,
                scoreExportService.exportClassSheet(examId));
    }

    /** 逐题得分明细 Excel：每生一行 × 逐题得分列。 */
    @GetMapping("/api/exams/{examId}/scores/export/detail")
    @RequirePermission("exam:manage")
    public ResponseEntity<byte[]> exportQuestionDetail(@PathVariable Long examId) {
        return attachment(examId, "逐题得分明细", ".xlsx", XLSX_MEDIA_TYPE,
                scoreExportService.exportQuestionDetail(examId));
    }

    /** 题目统计表 Excel：答对率/难度/区分度（§8.4）。 */
    @GetMapping("/api/exams/{examId}/scores/export/question-stats")
    @RequirePermission("exam:manage")
    public ResponseEntity<byte[]> exportQuestionStats(@PathVariable Long examId) {
        return attachment(examId, "题目统计", ".xlsx", XLSX_MEDIA_TYPE,
                scoreExportService.exportQuestionStats(examId));
    }

    /**
     * 个人成绩单导出：format=xlsx（默认）为 Excel，format=pdf 为打印版（题头/签名栏，§12.10）。
     */
    @GetMapping("/api/exams/{examId}/scores/export/personal/{studentId}")
    @RequirePermission("exam:manage")
    public ResponseEntity<byte[]> exportPersonal(@PathVariable Long examId,
                                                 @PathVariable Long studentId,
                                                 @RequestParam(defaultValue = "xlsx") String format) {
        if ("pdf".equalsIgnoreCase(format)) {
            return attachment(examId, "个人成绩单", ".pdf", MediaType.APPLICATION_PDF_VALUE,
                    scoreExportService.exportPersonalPdf(examId, studentId));
        }
        return attachment(examId, "个人成绩单", ".xlsx", XLSX_MEDIA_TYPE,
                scoreExportService.exportPersonalSheet(examId, studentId));
    }

    /** 下载响应：Content-Disposition 文件名带考试 ID 与时间戳（RFC 5987 UTF-8 编码）。 */
    private ResponseEntity<byte[]> attachment(Long examId, String name, String ext,
                                              String mediaType, byte[] body) {
        String filename = URLEncoder.encode(
                name + "-" + examId + "-" + System.currentTimeMillis() + ext, StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(mediaType))
                .body(body);
    }
}
