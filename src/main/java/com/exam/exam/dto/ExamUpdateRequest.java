package com.exam.exam.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 更新考试请求（部分更新）：仅"未发布且未开始"的考试允许修改；
 * 字段为 null 时保留原值，时间窗/时长按合并后的最终值整体校验。
 */
@Data
public class ExamUpdateRequest {

    @Size(max = 128, message = "标题最长 128 字")
    private String title;

    @Size(max = 512, message = "描述最长 512 字")
    private String description;

    /** 换绑试卷 ID（须存在且属于当前教师） */
    private Long paperId;

    private Long courseId;

    private Long classId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer durationMinutes;

    private Integer allowLateMinutes;

    /** 防作弊配置 JSON（null=保留原值） */
    private JsonNode antiCheatConfig;
}
