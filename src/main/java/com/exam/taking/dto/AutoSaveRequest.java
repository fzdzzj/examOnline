package com.exam.taking.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 30s 自动保存请求（前端定时触发）：答案 + 标记题 + 客户端持有版本号。
 * 多端/多版本冲突以版本号与时间戳最新者为准（spec「自动保存与断线恢复」）。
 */
@Data
public class AutoSaveRequest {

    /** 客户端持有的草稿版本：首次保存传 1，之后取上次响应返回的版本 */
    @NotNull(message = "草稿版本不能为空")
    private Integer version;

    /** 答案映射 questionId → 答案内容 */
    private JsonNode answers;

    /** 标记题 ID 列表（导航面板"标记"色） */
    private List<Long> marked;
}
