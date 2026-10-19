package com.exam.reliability.reconciliation.dto;

import com.exam.reliability.reconciliation.entity.ReconciliationDiff;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 差异票响应（GET /api/admin/reconciliation/diffs）：
 * 差异类型/状态一律直出实体原串，前端不维护第二套语义；
 * expected/actual/deviation 仅维度二（涉分矛盾）有值。
 */
@Data
public class ReconciliationDiffResponse {

    private Long id;
    private Long runId;
    private Long examId;
    private Long submissionId;
    private Long questionId;
    private String diffType;
    private String detail;
    private BigDecimal expectedValue;
    private BigDecimal actualValue;
    private BigDecimal deviation;
    private String status;
    private Long closedBy;
    private LocalDateTime closedTime;
    private LocalDateTime createdTime;

    public static ReconciliationDiffResponse from(ReconciliationDiff diff) {
        ReconciliationDiffResponse response = new ReconciliationDiffResponse();
        response.setId(diff.getId());
        response.setRunId(diff.getRunId());
        response.setExamId(diff.getExamId());
        response.setSubmissionId(diff.getSubmissionId());
        response.setQuestionId(diff.getQuestionId());
        response.setDiffType(diff.getDiffType());
        response.setDetail(diff.getDetail());
        response.setExpectedValue(diff.getExpectedValue());
        response.setActualValue(diff.getActualValue());
        response.setDeviation(diff.getDeviation());
        response.setStatus(diff.getStatus());
        response.setClosedBy(diff.getClosedBy());
        response.setClosedTime(diff.getClosedTime());
        response.setCreatedTime(diff.getCreatedTime());
        return response;
    }
}
