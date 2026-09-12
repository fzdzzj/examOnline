package com.exam.exam.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建考试请求：绑定试卷、课程班级，设定时间窗与个人时长。
 * 时间窗先后与时长正负的业务校验在 Service 层完成（返回规范口径的"时间窗非法/时长非法"错误）。
 */
@Data
public class ExamCreateRequest {

    @NotBlank(message = "考试标题不能为空")
    @Size(max = 128, message = "标题最长 128 字")
    private String title;

    @Size(max = 512, message = "描述最长 512 字")
    private String description;

    /** 绑定试卷 ID（必须存在且属于当前教师） */
    @NotNull(message = "绑定试卷不能为空")
    private Long paperId;

    /** 课程 ID（可选，课程实体后续阶段提供） */
    private Long courseId;

    /** 班级 ID（可选，同上） */
    private Long classId;

    /** 时间窗起点（定时发布的触发点） */
    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    /** 时间窗终点（到达即自然结束） */
    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    /** 个人答题时长（分钟）：学生点击开始后倒计时 */
    @NotNull(message = "个人时长不能为空")
    private Integer durationMinutes;

    /** 允许迟到分钟数（可选，默认 0） */
    private Integer allowLateMinutes;

    /** 防作弊配置 JSON（可选，如 {"switchScreen":true,"forbidCopy":false}） */
    private JsonNode antiCheatConfig;
}
