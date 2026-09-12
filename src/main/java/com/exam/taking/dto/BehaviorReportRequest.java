package com.exam.taking.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 切屏/失焦/刷新等行为事件上报（spec add-anti-cheat「行为事件采集」需求）：
 * 服务端经统一采集核心（策略模式）判定严重度后落 exam_behavior_logs；
 * 切屏只警告 + 记录，绝不强制交卷；事件类型未注册时照常落库（兜底策略）。
 */
@Data
public class BehaviorReportRequest {

    /** 事件类型：SWITCH_SCREEN / WINDOW_BLUR / PAGE_REFRESH 等（见 BehaviorEventTypes） */
    @NotBlank(message = "事件类型不能为空")
    @Size(max = 32, message = "事件类型最长 32 字符")
    private String eventType;

    /** 事件明细 JSON（离开时长/客户端时间等，可选；策略补充字段会与其合并） */
    private JsonNode eventData;

    /** 事件发生时间（客户端时钟，可选；缺省用服务端当前时间） */
    private LocalDateTime occurredTime;
}
