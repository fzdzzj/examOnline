package com.exam.score.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成绩发布/撤回审计日志（spec「成绩撤回」场景：记录谁/何时/做了什么/原因）：
 * 只写不改（append-only），撤回必须附原因，发布/撤回动作各落一条。
 */
@Data
@TableName("score_audit_logs")
public class ScoreAuditLog {

    /** 发布 */
    public static final String ACTION_PUBLISH = "PUBLISH";

    /** 撤回（需管理员权限 + 原因必填） */
    public static final String ACTION_REVOKE = "REVOKE";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long examId;

    /** 动作：PUBLISH / REVOKE */
    private String action;

    /** 操作人 */
    private Long operatorId;

    /** 撤回原因（撤回必填，发布为空） */
    private String reason;

    /** 摘要（如发布人数、跳过的考试及原因） */
    private String detail;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
