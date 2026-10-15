package com.exam.question.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 题目查重命中项（add-question-duplicate-detection）：返回相似题干题目摘要与相似度。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuestionDuplicateItemResponse {

    private Long questionId;

    /** 题型：1单选 2多选 3判断 4简答 5填空 */
    private Integer type;

    private String content;

    private BigDecimal score;

    /** 与待检测题干（stem）的相似度，0.0~1.0（1.0 表示归一化后完全一致） */
    private Double similarity;

    private Long createdBy;

    private LocalDateTime createdTime;
}