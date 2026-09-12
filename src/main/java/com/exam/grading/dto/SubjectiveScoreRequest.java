package com.exam.grading.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 教师批改提交（spec「教师批改留痕」场景）：
 * 逐题打分 + 评语；expectedVersion 为教师打开工作台时读到的版本号，
 * 并发批改冲突（他人已先保存）时服务端按乐观锁拒绝（409），前端刷新重载。
 */
public class SubjectiveScoreRequest {

    @NotNull(message = "答卷不能为空")
    private Long submissionId;

    @NotNull(message = "题目不能为空")
    private Long questionId;

    @NotNull(message = "分数不能为空")
    @DecimalMin(value = "0", message = "分数不能为负")
    @Digits(integer = 3, fraction = 1, message = "分数最多 1 位小数")
    private BigDecimal score;

    /** 评语（可空） */
    private String comment;

    /** 乐观锁版本号：与读取时一致才允许写入 */
    @NotNull(message = "版本号不能为空")
    private Integer expectedVersion;

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

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Integer expectedVersion) {
        this.expectedVersion = expectedVersion;
    }
}
