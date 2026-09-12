package com.exam.anticheat.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行为日志条目（教师端时间线/分页列表的展示形态）：
 * eventData 已反序列化为 JSON 对象（落库为 TEXT，含切屏次数/升级标记等策略补充字段）。
 */
@Data
public class BehaviorLogItem {

    private Long id;

    private Long examId;

    private Long studentId;

    /** 事件类型：SWITCH_SCREEN / WINDOW_BLUR / PAGE_REFRESH / SUBMIT_ANOMALY / DRAFT_CONFLICT 等 */
    private String eventType;

    /** 严重度存储值：1低 2中 3高（与 exam_behavior_logs.severity 口径一致） */
    private Integer severity;

    /** 严重度语义名（低/中/高），教师端直接展示 */
    private String severityName;

    /** 事件明细 JSON（客户端明细 + 策略补充字段合并后的结果） */
    private JsonNode eventData;

    /** 事件发生时间（时间线排序依据） */
    private LocalDateTime eventTime;

    /** 服务端落库时间 */
    private LocalDateTime createdTime;
}
