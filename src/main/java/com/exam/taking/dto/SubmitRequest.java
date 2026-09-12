package com.exam.taking.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 交卷请求：手动交卷与前端倒计时归零强制提交共用（spec「三路竞态仅一次」场景中的学生侧两路）。
 */
@Data
public class SubmitRequest {

    /** 手动交卷（默认） */
    public static final String TYPE_MANUAL = "MANUAL";

    /** 前端倒计时归零自动提交 */
    public static final String TYPE_COUNTDOWN_ZERO = "COUNTDOWN_ZERO";

    /** 答案映射 questionId → 答案内容（客户端最终作答状态） */
    private JsonNode answers;

    /** 提交来源：MANUAL / COUNTDOWN_ZERO，非法值按手动处理 */
    @Size(max = 32, message = "提交来源标识非法")
    private String submitType;
}
