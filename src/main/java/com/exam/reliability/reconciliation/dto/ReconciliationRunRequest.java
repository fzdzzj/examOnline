package com.exam.reliability.reconciliation.dto;

import lombok.Data;

/**
 * 手动触发对账请求：examId 可空——空表示按范围扫描全部已判分考试（有界，见
 * {@code exam.reconciliation.max-exams-per-run}），与定时兜底同一候选口径。
 */
@Data
public class ReconciliationRunRequest {

    private Long examId;
}
