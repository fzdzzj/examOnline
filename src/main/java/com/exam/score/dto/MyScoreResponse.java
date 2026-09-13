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

    /**
     * 复核中标记（§5.4）：true = 存在进行中的成绩复核申请，此时分数/排名被隐藏（见各 score 字段为 null、
     * rank 为 0），前端据此显示"复核中"，防止"看了分数再申请"。
     */
    private Boolean reviewing;
}
