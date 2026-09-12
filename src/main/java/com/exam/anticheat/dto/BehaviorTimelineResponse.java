package com.exam.anticheat.dto;

import lombok.Data;

import java.util.List;

/**
 * 单个学生的行为时间线（spec「教师查看时间线」场景——按时间顺序展示该学生行为轨迹；
 * 监考大屏点击异常学生即打开本视图）。
 */
@Data
public class BehaviorTimelineResponse {

    private Long examId;

    private Long studentId;

    /** 行为事件总数 */
    private long total;

    /** 低严重度（1）事件数 */
    private long lowCount;

    /** 中严重度（2）事件数 */
    private long mediumCount;

    /** 高严重度（3）事件数 */
    private long highCount;

    /** 时间线：按 event_time 升序的完整行为轨迹 */
    private List<BehaviorLogItem> items;
}
