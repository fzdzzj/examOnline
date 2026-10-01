package com.exam.exam.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建补考请求（§8.2/§12.5）：教师按主考成绩筛选（缺考/未交卷/低于分数线）后勾选指定学生，
 * 生成独立补考考试（独立时间窗/时长）并写入 exam_candidates 名单。
 * maker_end_score_rule 未指定时默认 takeHighest（见 Exam.MAKEUP_DEFAULT_RULE）。
 */
@Data
public class MakeupCreateRequest {

    /** 补考标题（可选，缺省为"主考标题-补考"） */
    private String title;

    /** 独立补考时间窗（§12.5：补考互不影响主考） */
    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    /** 补考个人时长（分钟） */
    @NotNull(message = "个人时长不能为空")
    private Integer durationMinutes;

    /** 补考允许迟到分钟数（可选，默认 0） */
    private Integer allowLateMinutes;

    /** 补考成绩规则（takeHighest/takeLatest/takeAverage，可选，默认 takeHighest） */
    private String makeupScoreRule;

    /** 被指定补考的学生（勾选名单，§8.2） */
    @NotEmpty(message = "补考名单不能为空")
    private List<Long> studentIds;
}