package com.exam.submission.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 死信消息留档：重投前先落库，使 receive(autoAck) 取出后仍可审计。
 *
 * <p>status = REPLAYED（已重投回主队列）或 PARKED（超过 x-replay-count 上限，待人工）。
 * 实体与建表双向一致：createdTime ↔ created_time。
 */
@Data
@TableName("exam_dlq_messages")
public class ExamDlqMessage {

    public static final String STATUS_REPLAYED = "REPLAYED";
    public static final String STATUS_PARKED = "PARKED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源队列名（当前固定为 exam.submit.dead.queue） */
    private String queue;

    /** 原始消息体 */
    private String payload;

    /** 原始消息头 JSON */
    private String headersJson;

    /** 死信消息上的 x-retry-count（队列内重试层） */
    private Integer retryCount;

    /** 死信消息上的 x-replay-count（跨重投轮次层，落档时取重投前值） */
    private Integer replayCount;

    /** REPLAYED / PARKED */
    private String status;

    /** 可选错误信息（如 headers 序列化失败说明） */
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
