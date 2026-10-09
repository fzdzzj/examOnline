package com.exam.score.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 单场考试逐题回顾响应模型。
 */
@Data
public class ExamReviewResponse {

    private Long examId;

    private String examTitle;

    private LocalDateTime examTime;

    private String studentName;

    /** 客观题总分 */
    private BigDecimal objectiveScore;

    /** 主观题总分 */
    private BigDecimal subjectiveScore;

    /** 试卷总分 */
    private BigDecimal totalScore;

    /** 全班排名 */
    private Integer rank;

    /** 是否部分批改（true=存在未批简答题） */
    private Boolean partialGraded;

    /** 卷面逐题明细列表 */
    private List<ExamReviewQuestionItem> questions;
}
