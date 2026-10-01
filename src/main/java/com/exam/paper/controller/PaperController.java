package com.exam.paper.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.common.ratelimit.RateLimit;
import com.exam.paper.dto.AddPaperQuestionRequest;
import com.exam.paper.dto.PaperCreateRequest;
import com.exam.paper.dto.PaperDetailResponse;
import com.exam.paper.dto.PaperOrderRequest;
import com.exam.paper.dto.PaperQuestionItemResponse;
import com.exam.paper.dto.PaperResponse;
import com.exam.paper.dto.PaperSnapshotResponse;
import com.exam.paper.dto.PaperUpdateRequest;
import com.exam.paper.dto.RandomDrawPreviewResponse;
import com.exam.paper.dto.RandomDrawRequest;
import com.exam.paper.dto.UpdatePaperQuestionScoreRequest;
import com.exam.paper.entity.Paper;
import com.exam.paper.service.PaperService;
import com.exam.paper.service.PaperSnapshotService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 试卷与组卷接口：
 * <ul>
 *   <li>试卷 CRUD（papers）；</li>
 *   <li>手动组卷——加题 / 调序 / 试卷内分值覆盖（paper_questions）；</li>
 *   <li>标签随机抽题——预览（不落库）与确认入卷；</li>
 *   <li>抽题锁定——生成快照后试卷只读，后续读取以快照为准。</li>
 * </ul>
 * 类级 {@code @RequirePermission("paper:manage")}；教师间水平越权由 Service 层 owner 校验兜底。
 */
@RestController
@RequestMapping("/api/papers")
@RequirePermission("paper:manage")
public class PaperController {

    private final PaperService paperService;
    private final PaperSnapshotService paperSnapshotService;

    public PaperController(PaperService paperService, PaperSnapshotService paperSnapshotService) {
        this.paperService = paperService;
        this.paperSnapshotService = paperSnapshotService;
    }

    /** 创建试卷（草稿） */
    @PostMapping
    public ApiResponse<PaperResponse> create(@Valid @RequestBody PaperCreateRequest request) {
        return ApiResponse.success(toResponse(paperService.create(request)));
    }

    /** 试卷分页（教师仅见自己的试卷） */
    @GetMapping
    public ApiResponse<List<PaperResponse>> page(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "10") long size) {
        Page<Paper> result = paperService.page(page, size);
        return ApiResponse.success(result.getRecords().stream().map(this::toResponse).toList());
    }

    /** 试卷详情（题目项按题号排序，含分值覆盖信息） */
    @GetMapping("/{id}")
    public ApiResponse<PaperDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(paperService.detail(id));
    }

    /** 更新试卷元信息：已有题目时改总分须满足"各题分值之和 = 总分" */
    @PutMapping("/{id}")
    public ApiResponse<PaperDetailResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody PaperUpdateRequest request) {
        return ApiResponse.success(paperService.updateMeta(id, request));
    }

    /** 删除试卷（仅草稿；已锁定试卷保护历史不可删） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        paperService.delete(id);
        return ApiResponse.success();
    }

    // ==================== 手动组卷 ====================

    /** 加题入卷：score 不传用题目默认分；传入则覆盖（题目默认分不受影响） */
    @PostMapping("/{id}/questions")
    public ApiResponse<PaperQuestionItemResponse> addQuestion(@PathVariable Long id,
                                                              @Valid @RequestBody AddPaperQuestionRequest request) {
        return ApiResponse.success(paperService.addQuestion(id, request.getQuestionId(), request.getScore()));
    }

    /** 移出题目（剩余题目自动重排为 1..n） */
    @DeleteMapping("/{id}/questions/{questionId}")
    public ApiResponse<Void> removeQuestion(@PathVariable Long id, @PathVariable Long questionId) {
        paperService.removeQuestion(id, questionId);
        return ApiResponse.success();
    }

    /** 调整试卷内单题分值 */
    @PutMapping("/{id}/questions/{questionId}/score")
    public ApiResponse<Void> updateQuestionScore(@PathVariable Long id, @PathVariable Long questionId,
                                                 @Valid @RequestBody UpdatePaperQuestionScoreRequest request) {
        paperService.updateQuestionScore(id, questionId, request.getScore());
        return ApiResponse.success();
    }

    /** 调整题目顺序：按传入 ID 顺序重排题号 1..n */
    @PutMapping("/{id}/questions/order")
    public ApiResponse<PaperDetailResponse> updateOrder(@PathVariable Long id,
                                                        @Valid @RequestBody PaperOrderRequest request) {
        return ApiResponse.success(paperService.updateOrder(id, request.getQuestionIds()));
    }

    // ==================== 标签随机抽题 ====================

    /** 随机抽题预览：不落库，教师可确认入卷或重抽；题量不足时返回 400 提示调整 */
    @PostMapping("/random-draw/preview")
    public ApiResponse<RandomDrawPreviewResponse> previewDraw(@Valid @RequestBody RandomDrawRequest request) {
        return ApiResponse.success(paperService.previewDraw(request));
    }

    /** 随机抽题确认入卷：按规则抽取并追加到试卷（使用题目默认分）。
     *  抽题限流（capacity=200, qps=50）——教师操作低频，仅需防异常/误操作刷爆，阈值取低即可。 */
    @RateLimit(qps = 50, capacity = 200, key = "random-draw")
    @PostMapping("/{id}/questions/random")
    public ApiResponse<PaperDetailResponse> commitRandomDraw(@PathVariable Long id,
                                                             @Valid @RequestBody RandomDrawRequest request) {
        return ApiResponse.success(paperService.commitRandomDraw(id, request));
    }

    // ==================== 抽题锁定与快照 ====================

    /** 生成快照并锁定试卷：校验题目完整性与总分校验后序列化落库，此后试卷只读 */
    @PostMapping("/{id}/snapshot")
    public ApiResponse<PaperSnapshotResponse> generateSnapshot(@PathVariable Long id) {
        return ApiResponse.success(paperSnapshotService.generate(id));
    }

    /** 读取当前快照（刷新始终返回同一份内容；考试/判分/回看统一读这里） */
    @GetMapping("/{id}/snapshot")
    public ApiResponse<PaperSnapshotResponse> getSnapshot(@PathVariable Long id) {
        return ApiResponse.success(paperSnapshotService.getCurrent(id));
    }

    private PaperResponse toResponse(Paper paper) {
        return new PaperResponse(paper.getId(), paper.getTitle(), paper.getDescription(),
                paper.getTotalScore(), paper.getQuestionCount(), paper.getStatus(),
                paper.getSnapshotId(), paper.getCreatedBy(), paper.getCreatedTime());
    }
}
