package com.exam.taking.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 切屏/失焦等行为事件上报（spec「切屏仅记录」场景）：
 * 服务端只落 exam_behavior_logs，不强制交卷；完整防作弊在阶段 7 消费。
 */
@Data
public class BehaviorReportRequest {

    /** 事件类型：SWITCH_SCREEN / WINDOW_BLUR / VISIBILITY_HIDDEN 等 */
    @NotBlank(message = "事件类型不能为空")
    @Size(max = 32, message = "事件类型最长 32 字符")
    private String eventType;

    /** 事件明细 JSON（离开时长/客户端时间等，可选） */
    private JsonNode eventData;

    /** 严重级别：1提示 2警告 3严重（缺省 1） */
    private Integer severity;

    /** 事件发生时间（客户端时钟，可选；缺省用服务端当前时间） */
    private LocalDateTime occurredTime;
}
