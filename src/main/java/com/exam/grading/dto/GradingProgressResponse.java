package com.exam.grading.dto;

import lombok.Data;

/**
 * 判分进度总览（教师判分工作台首屏）：
 * 客观判分进度按答卷 grading_status 统计，主观批改进度按 subjective_grades 统计。
 */
@Data
public class GradingProgressResponse {

    /** 已交卷答卷总数（判分对象全集） */
    private int submittedCount;

    /** 客观判分成功份数（grading_status=1） */
    private int gradedCount;

    /** 判分失败份数（grading_status=2，待重判/手动给分） */
    private int failedCount;

    /** 待判分份数（已交卷但 grading_status=0） */
    private int pendingCount;

    /** 简答批改总行数（判分运行后才有；0=尚未运行判分） */
    private int subjectiveTotal;

    /** 已批（有终分）简答行数 */
    private int subjectiveGraded;

    /** 部分批改答卷数：存在未批简答的答卷（发布时将标记"部分批改"，§7.5） */
    private int partialGradedCount;
}
