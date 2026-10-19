package com.exam.reliability.reconciliation.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.ApiResponse;
import com.exam.reliability.reconciliation.dto.ReconciliationDiffCloseRequest;
import com.exam.reliability.reconciliation.dto.ReconciliationDiffResponse;
import com.exam.reliability.reconciliation.dto.ReconciliationRunRequest;
import com.exam.reliability.reconciliation.dto.ReconciliationRunResponse;
import com.exam.reliability.reconciliation.service.ReconciliationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成绩链对账管理接口（add-score-chain-reconciliation）：
 *
 * <ul>
 *   <li>POST /api/admin/reconciliation/run——手动触发（可带 examId 范围），返回本次运行记录；</li>
 *   <li>GET  /api/admin/reconciliation/runs——运行历史分页（可按 examId 筛选）；</li>
 *   <li>GET  /api/admin/reconciliation/diffs——差异票分页（按 run/类型/状态筛选）；</li>
 *   <li>POST /api/admin/reconciliation/diffs/{id}/close——人工关闭（记审计）。</li>
 * </ul>
 *
 * <p>类级 {@code @RequirePermission("exam:manage")}：权限点仅 TEACHER/ADMIN 持有，STUDENT 调任一
 * 端点一律 403（spec「差异票闭环与权限隔离」场景）。对账是全场视角的运维动作，不按考试归属
 * 收窄——教师的考试也由管理员对账，越权面由权限点而非 owner 属性兜底。
 */
@RestController
@RequirePermission("exam:manage")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /** 手动触发对账：examId 为空表示按范围扫描已判分考试（有界）。 */
    @PostMapping("/api/admin/reconciliation/run")
    public ApiResponse<ReconciliationRunResponse> runReconciliation(
            @RequestBody(required = false) ReconciliationRunRequest request) {
        Long examId = request == null ? null : request.getExamId();
        return ApiResponse.success(ReconciliationRunResponse.from(
                reconciliationService.runManual(examId, SecurityUtil.getUserId())));
    }

    /** 运行历史分页（id 倒序=最近优先）。 */
    @GetMapping("/api/admin/reconciliation/runs")
    public ApiResponse<List<ReconciliationRunResponse>> reconciliationRuns(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) Long examId) {
        return ApiResponse.success(reconciliationService.pageRuns(page, size, examId));
    }

    /** 差异票分页：按 run / 类型 / 状态筛选。 */
    @GetMapping("/api/admin/reconciliation/diffs")
    public ApiResponse<List<ReconciliationDiffResponse>> reconciliationDiffs(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) String diffType,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(reconciliationService.pageDiffs(page, size, runId, diffType, status));
    }

    /** 人工关闭差异票（仅 OPEN）：关闭动作记 audit_log。 */
    @PostMapping("/api/admin/reconciliation/diffs/{id}/close")
    public ApiResponse<ReconciliationDiffResponse> closeReconciliationDiff(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid ReconciliationDiffCloseRequest request) {
        return ApiResponse.success(reconciliationService.closeDiff(
                id, request == null ? null : request.getReason()));
    }
}
