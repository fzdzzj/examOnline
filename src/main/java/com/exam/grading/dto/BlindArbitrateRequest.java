package com.exam.grading.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 仲裁终审提交（add-subjective-double-blind-grading）：
 * 仲裁员（考试归属人/管理员）对待仲裁题目提交终审分数，
 * 终分以仲裁分为准回填 subjective_grades.score 并结案。
 */
public class BlindArbitrateRequest {

    @NotNull(message = "答卷不能为空")
    private Long submissionId;

    @NotNull(message = "题目不能为空")
    private Long questionId;

    @NotNull(message = "分数不能为空")
    @DecimalMin(value = "0", message = "分数不能为负")
    @Digits(integer = 3, fraction = 1, message = "分数最多 1 位小数")
    private BigDecimal score;

    /** 仲裁理由（可空）：长度对齐库列 VARCHAR(1024) */
    @Size(max = 1024, message = "仲裁评语不能超过 1024 个字符")
    private String comment;

    public Long getSubmissionId() {
        return submissionId;
    }

    public void setSubmissionId(Long submissionId) {
        this.submissionId = submissionId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
