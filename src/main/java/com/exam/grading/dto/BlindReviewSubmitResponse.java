package com.exam.grading.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 双评盲评提交响应（add-subjective-double-blind-grading）：
 * roundType=本次落库轮次（FIRST/SECOND）；status=裁决状态
 * （PENDING_SECOND=待二评 / RESOLVED=容差内已裁决 / ARBITRATION_PENDING=超标待仲裁）；
 * finalScore 仅 RESOLVED 回传均分，其余为 null（二评提交前绝不透出一评分数）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlindReviewSubmitResponse {

    /** 本次落库轮次：FIRST / SECOND */
    private String roundType;

    /** 裁决状态：PENDING_SECOND / RESOLVED / ARBITRATION_PENDING */
    private String status;

    /** 裁决终分（仅 RESOLVED 有值：round((S1+S2)/2, 1)） */
    private BigDecimal finalScore;
}
