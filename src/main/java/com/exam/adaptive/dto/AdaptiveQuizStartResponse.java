package com.exam.adaptive.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * CAT 自适应测验开局响应：会话信息 + 首题（b 最接近 0 的中等难度题）。
 */
@Data
public class AdaptiveQuizStartResponse {

    private Long sessionId;

    private String status;

    /** 初始能力估计（先验均值 0） */
    private BigDecimal theta;

    /** 初始标准误（先验标准差 1） */
    private BigDecimal se;

    private Integer answeredCount;

    private Integer maxItems;

    private BigDecimal seThreshold;

    /** 首题（作答前不含正确答案与解析） */
    private AdaptiveQuizQuestionItem currentQuestion;
}
