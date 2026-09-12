package com.exam.taking.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 学生考试列表项（spec：考试列表按 待考/进行中/已完成 分组）。
 *
 * <p>分组口径以"学生下一步动作"为准：
 * <ul>
 *   <li>UPCOMING 待考——考试未开始，或进行中但学生尚未进入（可点击进入并开始）；</li>
 *   <li>ONGOING 进行中——学生已进入且未交卷（断线可续答）；</li>
 *   <li>FINISHED 已完成——学生已交卷，或考试已结束/已批改。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExamListItem {

    /** 分组：待考（未开始，或进行中未进入——可点击进入并开始） */
    public static final String GROUP_UPCOMING = "UPCOMING";

    /** 分组：进行中（已进入且未交卷，断线可续答） */
    public static final String GROUP_ONGOING = "ONGOING";

    /** 分组：已完成（已交卷，或考试已结束/已批改） */
    public static final String GROUP_FINISHED = "FINISHED";

    private Long examId;

    private String title;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer durationMinutes;

    /** 考试状态机：0未开始 1进行中 2已结束 3已批改 4已发布 */
    private Integer examStatus;

    /** 分组：UPCOMING / ONGOING / FINISHED */
    private String group;

    /** 是否允许进入（进行中且本人答卷仍处于进行中或未创建） */
    private boolean canEnter;

    /** 个人答卷状态：1进行中 2已交卷；未进入为 null */
    private Integer submissionStatus;

    /** 答题中的剩余秒数（服务端时间为准），其余场景为 null */
    private Long remainingSeconds;
}
