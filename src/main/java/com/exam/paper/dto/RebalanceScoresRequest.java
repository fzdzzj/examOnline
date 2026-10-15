package com.exam.paper.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 试卷分值智能配平请求（add-batch-tagging-and-score-balancing）：
 * {@code EVEN}（离散最大余数一键均分）或 {@code BY_TYPE}（按题型智能配比）。
 *
 * <p>{@code targetScore} 为空时以上报 DECLARED 的试卷总分（papers.total_score）为目标；
 * {@code step} 为分值步长（0.5 / 1.0 …），最大余数算法按 0.5 分步长整数化保证保和。
 * {@code fixedScores} 为 key=题型 code、value=该题型单题固定分值（BY_TYPE 模式必填，
 * 未列出的题型按自动配平均分剩余目标）。
 */
@Data
public class RebalanceScoresRequest {

    public static final String MODE_EVEN = "EVEN";
    public static final String MODE_BY_TYPE = "BY_TYPE";

    @NotBlank(message = "配平模式不能为空")
    private String mode;

    /** 目标总分（可空，缺省用试卷申报总分 papers.total_score） */
    @DecimalMin(value = "0", message = "目标总分不能为负")
    @DecimalMax(value = "99999", message = "目标总分过大")
    private BigDecimal targetScore;

    /** 分值步长（默认 0.5），须为正数 */
    @NotNull(message = "分值步长不能为空")
    @DecimalMin(value = "0", message = "分值步长须大于 0")
    @DecimalMax(value = "10", message = "分值步长过大")
    private BigDecimal step;

    /** 按题型单题固定分值（BY_TYPE 用；EVEN 忽略）。键为题型 code，值为该题型单题分值 */
    private Map<Integer, BigDecimal> fixedScores;
}