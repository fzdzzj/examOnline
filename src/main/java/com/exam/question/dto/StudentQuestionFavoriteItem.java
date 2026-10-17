package com.exam.question.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收藏夹联表查询行映射对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentQuestionFavoriteItem {

    private Long id;

    private Long studentId;

    private Long questionId;

    private LocalDateTime createdTime;

    private Integer type;

    private String content;

    private String imageUrl;

    private String choices;

    private String correctAnswer;

    private BigDecimal score;

    private Integer difficulty;

    private String analysis;
}
