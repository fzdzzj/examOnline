package com.exam.grading.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 批改工作台-待批题目条目：某道简答题的整体批改进度。
 */
@Data
public class SubjectiveQuestionItem {

    private Long questionId;

    /** 考试快照内题号 */
    private int number;

    /** 题干（工作台左侧题目列表展示） */
    private String content;

    /** 本题满分 */
    private BigDecimal score;

    /** 需批改人数（有批改行即需批，判分运行后 = 交卷人数） */
    private int totalStudents;

    /** 已批人数（有终分） */
    private int gradedStudents;
}
