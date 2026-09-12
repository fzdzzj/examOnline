package com.exam.score.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 学生查自己的成绩（spec「学生仅见自己成绩」§9.10；成绩未发布时不返回分数）。
 */
@Data
public class MyScoreResponse {

    private Long examId;

    private String examTitle;

    private BigDecimal objectiveScore;

    private BigDecimal subjectiveScore;

    private BigDecimal totalScore;

    /** 全班排名（并列同名次） */
    private int rank;

    /** 1=部分批改（存在未批简答按 0 分计），学生可据此走成绩复核 */
    private Integer partialGraded;
}
