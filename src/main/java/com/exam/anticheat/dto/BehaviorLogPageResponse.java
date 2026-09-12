package com.exam.anticheat.dto;

import lombok.Data;

import java.util.List;

/**
 * 行为日志分页响应（spec「行为日志时间线」需求——按考试/学生/事件类型/严重度筛选）。
 */
@Data
public class BehaviorLogPageResponse {

    /** 筛选条件下的总条数 */
    private long total;

    private long page;

    private long size;

    /** 行为日志条目，按 event_time 升序（时间线口径） */
    private List<BehaviorLogItem> items;
}
