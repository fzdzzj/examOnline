package com.exam.reliability.reconciliation.dto;

import com.exam.reliability.reconciliation.entity.ReconciliationRun;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对账运行记录响应（GET /api/admin/reconciliation/runs、POST /run 共用）。
 * 字段按实体直出、不推算：三类差异数就是运行记录里回填的检出数，前端不做二次统计。
 */
@Data
public class ReconciliationRunResponse {

    private Long id;
    private Long examId;
    private String triggerSource;
    private String status;
    private Integer scannedCount;
    private Integer ungradedCount;
    private Integer mismatchCount;
    private Integer autoFixedCount;
    private String errorMessage;
    private Long operatorId;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;

    public static ReconciliationRunResponse from(ReconciliationRun run) {
        ReconciliationRunResponse response = new ReconciliationRunResponse();
        response.setId(run.getId());
        response.setExamId(run.getExamId());
        response.setTriggerSource(run.getTriggerSource());
        response.setStatus(run.getStatus());
        response.setScannedCount(run.getScannedCount());
        response.setUngradedCount(run.getUngradedCount());
        response.setMismatchCount(run.getMismatchCount());
        response.setAutoFixedCount(run.getAutoFixedCount());
        response.setErrorMessage(run.getErrorMessage());
        response.setOperatorId(run.getOperatorId());
        response.setStartedTime(run.getStartedTime());
        response.setFinishedTime(run.getFinishedTime());
        return response;
    }
}
