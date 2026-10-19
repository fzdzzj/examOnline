package com.exam.adaptive.dto;

import lombok.Data;

import java.math.BigDecimal;

/** CAT 报告逐题轨迹项：题目内容 + 学生作答 + 判定 + 作答后的 θ/SE。 */
@Data
public class AdaptiveQuizReportItem {

    private Integer order;

    private Long questionId;

    private String questionType;

    private String questionContent;

    private String answer;

    private Boolean correct;

    private String correctAnswer;

    private BigDecimal thetaAfter;

    private BigDecimal seAfter;
}
