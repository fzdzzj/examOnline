package com.exam.score.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 成绩复核处理请求（教师提交）：
 * action=AGREE 同意（可带 adjustedTotalScore 调分，调分后更新学生端显示）；
 * action=REJECT 驳回（不调分，仅结束进行中复核，恢复原成绩显示）。
 */
@Data
public class ReviewHandleRequest {

    /** AGREE 同意 / REJECT 驳回 */
    private String action;

    /** 同意时可选的新总分（不传则沿用原分，仅结束隐藏） */
    private BigDecimal adjustedTotalScore;

    /** 处理意见/结果说明 */
    private String reason;
}