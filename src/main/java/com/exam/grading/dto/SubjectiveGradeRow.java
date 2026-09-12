package com.exam.grading.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 批改工作台单行：某学生的某道简答（批改记录 + 学生姓名的扁平视图）。
 * 前端工作台按题列出全部学生，逐行打分评语。
 */
@Data
public class SubjectiveGradeRow {

    private Long id;

    private Long submissionId;

    private Long examId;

    private Long studentId;

    /** 学生姓名（联查 users 表） */
    private String studentName;

    private Long questionId;

    /** 考试快照内题号 */
    private Integer questionNumber;

    /** 学生答案原文 */
    private String studentAnswer;

    /** 关键词初判提示分（参考，不自动定分） */
    private BigDecimal suggestedScore;

    /** 初判依据（命中关键词 x/y） */
    private String suggestedDetail;

    /** 教师终分：null=未批 */
    private BigDecimal score;

    private String comment;

    /** 批改人 */
    private Long graderId;

    /** 批改时间：null=未批 */
    private LocalDateTime gradedTime;

    /** 乐观锁版本号：提交批改时回传，冲突时服务端 409 */
    private Integer version;

    /** 是否已批（score 非 null），前端分组展示待批/已批 */
    public boolean isGraded() {
        return score != null;
    }
}
