package com.exam.exam.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 考试列表项响应（分页用）：不含描述/防作弊配置等重字段，
 * 详情走 GET /api/exams/{id}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExamResponse {

    private Long id;

    private String title;

    private Long paperId;

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

    /** 考试快照 ID（发布后回填） */
    private Long snapshotId;

    private Long createdBy;

    private LocalDateTime createdTime;
}
