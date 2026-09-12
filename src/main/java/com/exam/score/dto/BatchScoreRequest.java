package com.exam.score.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量发布/撤回请求：examIds 必填；撤回时 reason 必填（审计要求）。
 */
public class BatchScoreRequest {

    @NotEmpty(message = "考试 ID 列表不能为空")
    private List<Long> examIds;

    /** 撤回原因（撤回必填，审计留痕；发布时忽略） */
    private String reason;

    public List<Long> getExamIds() {
        return examIds;
    }

    public void setExamIds(List<Long> examIds) {
        this.examIds = examIds;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
