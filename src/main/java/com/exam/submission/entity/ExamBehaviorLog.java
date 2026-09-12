package com.exam.submission.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考试行为日志：切屏/失焦/草稿冲突等答题行为的采集记录。
 *
 * <p>本阶段只"记录不处置"（spec「切屏仅记录」场景：检测到切屏不强制交卷），
 * 完整防作弊分析在阶段 7 消费本表数据。
 */
@Data
@TableName("exam_behavior_logs")
public class ExamBehaviorLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long examId;

    private Long studentId;

    /** 事件类型：SWITCH_SCREEN / WINDOW_BLUR / DRAFT_CONFLICT 等 */
    private String eventType;

    /** 事件明细 JSON（离开时长/客户端时间/冲突版本等） */
    private String eventData;

    /** 严重级别：1提示 2警告 3严重 */
    private Integer severity;

    /** 事件发生时间（客户端上报或服务端记录） */
    private LocalDateTime eventTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
