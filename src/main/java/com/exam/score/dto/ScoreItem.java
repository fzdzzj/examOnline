package com.exam.score.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 成绩预览/榜单条目：单个学生的成绩明细 + 排名。
 */
@Data
public class ScoreItem {

    private Long studentId;

    /** 学生姓名（联查 users） */
    private String studentName;

    private BigDecimal objectiveScore;

    private BigDecimal subjectiveScore;

    private BigDecimal totalScore;

    /** 竞赛排名：并列同名次（1,2,2,4）；未汇总答卷为 0 */
    private int rank;

    /** 1=部分批改（存在未批简答，未批按 0 分计入，§7.5），学生端可见该标记 */
    private Integer partialGraded;
}
