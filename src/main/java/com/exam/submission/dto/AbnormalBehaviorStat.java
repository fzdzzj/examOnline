package com.exam.submission.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异常行为聚合统计（exam_behavior_logs 按 severity 阈值过滤后的按学生聚合投影，
 * 监考大屏异常高亮的数据源）。
 */
@Data
public class AbnormalBehaviorStat {

    private Long studentId;

    /** 达到严重度阈值的行为事件数 */
    private long eventCount;

    /** 最高严重度（1低 2中 3高） */
    private Integer maxSeverity;

    /** 最近一次异常事件时间 */
    private LocalDateTime lastEventTime;
}
