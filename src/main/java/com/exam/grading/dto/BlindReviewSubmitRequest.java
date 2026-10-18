package com.exam.grading.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 双评盲评提交（add-subjective-double-blind-grading）：
 * 一评/二评由服务端按既有盲评记录自动分流（无 FIRST 即一评，有即二评并校验同教师互斥）；
 * 提交方可以是考试归属人以外的教师（背靠背双评），归属校验仅仲裁端点保留。
 */
public class BlindReviewSubmitRequest {

    @NotNull(message = "答卷不能为空")
    private Long submissionId;

    @NotNull(message = "题目不能为空")
    private Long questionId;

    @NotNull(message = "分数不能为空")
    @DecimalMin(value = "0", message = "分数不能为负")
    @Digits(integer = 3, fraction = 1, message = "分数最多 1 位小数")
    private BigDecimal score;

    /** 评语（可空）：长度对齐库列 VARCHAR(1024)，超长在校验层拦截而非落库时 500 */
    @Size(max = 1024, message = "盲评评语不能超过 1024 个字符")
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
