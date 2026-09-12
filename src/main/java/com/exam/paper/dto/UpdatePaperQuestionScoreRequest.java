package com.exam.paper.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** 调整试卷内单题分值请求（覆盖题目默认分，不影响题库）。 */
@Data
public class UpdatePaperQuestionScoreRequest {

    @NotNull(message = "分值不能为空")
    @DecimalMin(value = "0.5", message = "分值最小 0.5")
    @DecimalMax(value = "999.9", message = "分值最大 999.9")
    private BigDecimal score;
}
