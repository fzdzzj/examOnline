package com.exam.reliability.reconciliation.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 差异票人工关闭请求：reason 为处置说明（可空），有值时随关闭审计一并落 audit_log。
 */
@Data
public class ReconciliationDiffCloseRequest {

    @Size(max = 200, message = "处置说明不得超过 200 字")
    private String reason;
}
