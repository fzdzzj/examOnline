package com.exam.question.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 收藏题目详情响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentQuestionFavoriteResponse {

    private Long id;

    private Long studentId;

    private Long questionId;

    private LocalDateTime createdTime;

    /** 1单选 2多选 3判断 4简答 5填空 */
    private Integer type;

    /** 题型中文名 */
    private String typeName;

    private String content;

    private String imageUrl;

    private List<String> choices;

    private String correctAnswer;

    private BigDecimal score;

    private Integer difficulty;

    private String analysis;
}
