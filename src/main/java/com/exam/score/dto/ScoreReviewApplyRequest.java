package com.exam.score.dto;

import lombok.Data;

/**
 * 成绩复核申请请求（学生提交）：reason 为申请理由（不强制必填，鼓励填写便于教师处理）。
 */
@Data
public class ScoreReviewApplyRequest {

    /** 申请理由 */
    private String reason;
}