package com.exam.exam.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 考试详情响应：列表项全字段 + 描述/防作弊配置/绑定试卷标题。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExamDetailResponse {

    private Long id;

    private String title;

    private String description;

    private Long paperId;

    /** 绑定试卷标题（便于教师核对，快照之外的只读展示） */
    private String paperTitle;

    private Long courseId;

    private Long classId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer durationMinutes;

    private Integer allowLateMinutes;

    /** 状态机：0未开始 1进行中 2已结束 3已批改 4已发布 */
    private Integer status;

    /** 0=未发布 1=已发布（学生可见） */
    private Integer published;

    /** 1=教师提前结束（强制交卷标记） */
    private Integer forceEnd;

    /** 防作弊配置（解析后的 JSON，未配置为 null） */
    private JsonNode antiCheatConfig;

    /** 考试快照 ID（发布后回填） */
    private Long snapshotId;

    private Long createdBy;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
