package com.exam.adaptive.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * CAT 单题作答响应：即时判定 + 更新后的 θ/SE + 终止信号 + 下一题。
 * 终止时 {@code terminated=true}，{@code nextQuestion} 为 null，能力报告走 report 端点。
 */
@Data
public class AdaptiveQuizAnswerResponse {

    private Long sessionId;

    private Long questionId;

    private Boolean correct;

    /** 标准答案（作答后可回显，用于即时反馈） */
    private String correctAnswer;

    private String analysis;

    /** 作答后能力值 θ */
    private BigDecimal theta;

    /** 作答后标准误 SE */
    private BigDecimal se;

    private Integer answeredCount;

    private Boolean terminated;

    /** 终止原因：SE_CONVERGED / MAX_ITEMS / POOL_EXHAUSTED；未终止为 null */
    private String terminationReason;

    /** 下一题；{@code terminated=true} 时为 null */
    private AdaptiveQuizQuestionItem nextQuestion;
}
