package com.exam.paper.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** 手动加题入卷请求：score 缺省时使用题目默认分（分值覆盖场景显式传入）。 */
@Data
public class AddPaperQuestionRequest {

    @NotNull(message = "题目 ID 不能为空")
    private Long questionId;

    /** 试卷内分值（可覆盖题目默认分；不传则用默认分） */
    @DecimalMin(value = "0.5", message = "分值最小 0.5")
    @DecimalMax(value = "999.9", message = "分值最大 999.9")
    private BigDecimal score;
}
