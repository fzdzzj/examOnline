package com.exam.score.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成绩复核申请（spec score-review §5.4/§10.7）：
 * 学生质疑已发布成绩时提交，每场考试限申请 1 次（uk_review_exam_student 唯一索引兜底幂等）
 * 且须在成绩发布后 7 天内；复核申请期间学生端隐藏成绩显示"复核中"（防"看了分数再申请"）。
 *
 * <p>状态与成绩发布状态机（exam.status）<b>正交</b>：复核只读"是否有进行中复核申请"
 * 来决定是否隐藏成绩，绝不改动成绩发布状态机（发布/撤回仍走 STATE 转移 CAS）。
 */
@Data
@TableName("score_review")
public class ScoreReview {

    /** 待处理：申请刚提交，教师尚未处理（计入"进行中"，触发隐藏成绩） */
    public static final int STATUS_PENDING = 0;

    /** 处理中：教师已受理但未给出结论（计入"进行中"，触发隐藏成绩；当前保留态） */
    public static final int STATUS_PROCESSING = 1;

    /** 已同意：教师认可并可调整分数，处理后学生端显示（新）成绩 */
    public static final int STATUS_AGREED = 2;

    /** 已驳回：教师不同意，处理后恢复原成绩显示 */
    public static final int STATUS_REJECTED = 3;

    /** 复核申请窗口：成绩发布后 N 天内可申请（§10.7，防成绩长期处于可争议状态） */
    public static final int WINDOW_DAYS = 7;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属考试 */
    private Long examId;

    /** 申请复核的学生（user id） */
    private Long studentId;

    /** 复核状态：见 STATUS_* 常量（0待处理/1处理中/2已同意/3已驳回） */
    private Integer status;

    /** 学生申请理由 */
    private String reason;

    /** 教师处理意见/结果说明（同意调分或驳回的说明） */
    private String result;

    /** 申请时间（7 天窗口起点：与成绩发布时间比对） */
    private LocalDateTime applyTime;

    /** 处理时间 */
    private LocalDateTime handleTime;

    /** 处理教师（user id） */
    private Long handlerId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
