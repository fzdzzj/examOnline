package com.exam.score.controller;

import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.RequirePermission;
import com.exam.auth.security.RequireRole;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.ApiResponse;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.MakeupScoreService;
import com.exam.score.dto.BatchScoreRequest;
import com.exam.score.dto.ExamAnalysisReportResponse;
import com.exam.score.dto.ExamReviewResponse;
import com.exam.score.dto.MakeupFinalScoreResponse;
import com.exam.score.dto.MyScoreResponse;
import com.exam.score.dto.ScoreActionItem;
import com.exam.score.dto.ScoreLeaderboardResponse;
import com.exam.score.dto.ScorePreviewResponse;
import com.exam.score.dto.WrongQuestionPageResponse;
import com.exam.score.service.ExamAnalysisReportService;
import com.exam.score.service.ScoreExportService;
import com.exam.score.service.ScoreLeaderboardService;
import com.exam.score.service.ScoreReviewService;
import com.exam.score.service.ScoreService;
import com.exam.score.service.StudentWrongQuestionService;
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

import java.math.BigDecimal;
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
 *   <li>学生查询——仅本人成绩（§9.10），未发布统一"成绩待发布"；</li>
 *   <li>补考最终成绩——教师按考试查任一生、学生查本人（§5.1 合并规则经接口触达，收口遗留 #5）。</li>
 * </ul>
 */
@RestController
@RequirePermission("score:view")
public class ScoreController {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ScoreService scoreService;
    private final ScoreExportService scoreExportService;
    private final ExamAnalysisReportService examAnalysisReportService;
    private final MakeupScoreService makeupScoreService;
    private final ExamMapper examMapper;
    private final ScoreReviewService scoreReviewService;
    private final StudentWrongQuestionService studentWrongQuestionService;
    private final ScoreLeaderboardService scoreLeaderboardService;

    public ScoreController(ScoreService scoreService, ScoreExportService scoreExportService,
                           ExamAnalysisReportService examAnalysisReportService,
                           MakeupScoreService makeupScoreService, ExamMapper examMapper,
                           ScoreReviewService scoreReviewService,
                           StudentWrongQuestionService studentWrongQuestionService,
                           ScoreLeaderboardService scoreLeaderboardService) {
        this.scoreService = scoreService;
        this.scoreExportService = scoreExportService;
        this.examAnalysisReportService = examAnalysisReportService;
        this.makeupScoreService = makeupScoreService;
        this.examMapper = examMapper;
        this.scoreReviewService = scoreReviewService;
        this.studentWrongQuestionService = studentWrongQuestionService;
        this.scoreLeaderboardService = scoreLeaderboardService;
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

    /**
     * 学生查班级匿名榜单（add-class-leaderboard）：已发布考试匿名前 10 + 本人位置。
     */
    @GetMapping("/api/exams/{examId}/scores/leaderboard")
    public ApiResponse<ScoreLeaderboardResponse> leaderboard(@PathVariable Long examId) {
        Long studentId = SecurityUtil.getUserId();
        if (studentId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return ApiResponse.success(scoreLeaderboardService.leaderboard(examId, studentId));
    }

    /**
     * 学生查错题本列表（以已发布考试为粒度分页，每页最多 10 场考试组）。
     */
    @GetMapping("/api/scores/my/wrong-questions")
    public ApiResponse<WrongQuestionPageResponse> myWrongQuestions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long studentId = SecurityUtil.getUserId();
        if (studentId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return ApiResponse.success(studentWrongQuestionService.listWrongQuestions(studentId, page, size));
    }

    /**
     * 学生查单场考试逐题回顾（卷面全部题目，包括作答、正确答案与题目解析）。
     */
    @GetMapping("/api/scores/my/exams/{examId}/review")
    public ApiResponse<ExamReviewResponse> myExamReview(@PathVariable Long examId) {
        Long studentId = SecurityUtil.getUserId();
        if (studentId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return ApiResponse.success(studentWrongQuestionService.reviewExam(examId, studentId));
    }

    // ==================== 补考最终成绩（§5.1 合并规则经接口触达，收口遗留 #5） ====================

    /**
     * 教师按考试查某学生补考最终成绩（spec「合并规则有真实调用者」场景）：
     * 沿主考家族（主考 + 各次补考）按考试配置规则合并，历史各次成绩保留不覆盖
     * （MakeupScoreService 只读计算，从不 update 答卷）。
     *
     * <p>权限：教师侧 {@code exam:manage}（TEACHER/ADMIN），水平越权由归属校验兜底
     * （仅考试创建教师可查，ADMIN 放行）；学生无此权限被拦截。
     */
    @GetMapping("/api/exams/{examId}/scores/makeup-final/{studentId}")
    @RequirePermission("exam:manage")
    public ApiResponse<MakeupFinalScoreResponse> makeupFinalScore(@PathVariable Long examId,
                                                                  @PathVariable Long studentId) {
        requireOwnedExam(examId);
        BigDecimal finalScore = makeupScoreService.finalScore(examId, studentId);
        MakeupFinalScoreResponse response = new MakeupFinalScoreResponse();
        response.setExamId(examId);
        response.setStudentId(studentId);
        response.setFinalScore(finalScore);
        response.setReviewing(false);
        return ApiResponse.success(response);
    }

    /**
     * 学生查本人补考最终成绩（口径同 {@link #myScore}）：未发布统一"成绩待发布"、
     * 进行中复核隐藏分数；不泄露他人信息（只查本人）。
     */
    @GetMapping("/api/scores/makeup-final")
    public ApiResponse<MakeupFinalScoreResponse> myMakeupFinalScore(@RequestParam Long examId) {
        Long studentId = SecurityUtil.getUserId();
        if (studentId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        Exam root = requirePublishedRoot(examId);
        BigDecimal finalScore = makeupScoreService.finalScore(examId, studentId);
        MakeupFinalScoreResponse response = new MakeupFinalScoreResponse();
        response.setExamId(examId);
        response.setStudentId(studentId);
        // 复核中隐藏（§5.4）：主考家族存在进行中复核则隐藏分数，防"看了分数再申请"
        if (scoreReviewService.hasPendingReview(root.getId(), studentId)) {
            response.setFinalScore(null);
            response.setReviewing(true);
            return ApiResponse.success(response);
        }
        response.setFinalScore(finalScore);
        response.setReviewing(false);
        return ApiResponse.success(response);
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

    /**
     * 考试数据分析报告（创新点4 ⭐⭐⭐，Phase 2）：班级/逐题/知识点/学生关注名单四维只读聚合。
     * 权限 {@code exam:manage} + {@link #requireOwnedExam} 越权校验，口径同既有导出端点；
     * 逐题指标与题目统计导出共用同一聚合来源（{@code ScoreExportService.aggregateQuestionStats}）。
     */
    @GetMapping("/api/exams/{examId}/scores/analysis-report")
    @RequirePermission("exam:manage")
    public ApiResponse<ExamAnalysisReportResponse> analysisReport(@PathVariable Long examId) {
        requireOwnedExam(examId);
        return ApiResponse.success(examAnalysisReportService.buildReport(examId));
    }

    // ==================== 私有工具 ====================

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

    /** 教师侧越权校验：存在/软删 404，非归属教师 403（ADMIN 放行），口径同成绩预览/导出。 */
    private Exam requireOwnedExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");
        return exam;
    }

    /** 学生侧可见性：沿 parent_exam_id 找回主考，主考须已发布才放行（口径同 myScore §9.10）。 */
    private Exam requirePublishedRoot(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        Exam root = exam;
        while (root.getParentExamId() != null) {
            Exam parent = examMapper.selectById(root.getParentExamId());
            if (parent == null) {
                break;
            }
            root = parent;
        }
        if (root.getStatus() != Exam.STATUS_PUBLISHED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "成绩待发布");
        }
        return root;
    }
}