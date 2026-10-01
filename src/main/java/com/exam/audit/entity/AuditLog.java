package com.exam.audit.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 安全审计日志：登录成功/失败、账户锁定、权限变更等安全事件落库。
 *
 * <p>实体与建表双向一致（createdTime ↔ created_time），traceId ↔ trace_id。
 * traceId 取当前 OTel Span 的 traceId，无 span（如 MQ 线程）时回落 requestId。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILURE = "FAILURE";
    public static final String STATUS_WARNING = "WARNING";

    public static final String ACTION_LOGIN = "LOGIN";
    public static final String ACTION_ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String ACTION_PERMISSION_CHANGE = "PERMISSION_CHANGE";
    public static final String ACTION_JWT_VALIDATION = "JWT_VALIDATION";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 链路标识，用于把审计记录与 Jaeger 上的 trace 对齐 */
    private String traceId;

    /** 操作人用户 ID；系统事件或账号不存在时为 null */
    private Long userId;

    /** 操作人用户名（冗余存储，避免用户删除后审计不可读） */
    private String username;

    /** LOGIN / ACCOUNT_LOCKED / PERMISSION_CHANGE / JWT_VALIDATION */
    private String action;

    /** 客户端 IP，兼容 IPv6 长度 */
    private String ipAddress;

    /** SUCCESS / FAILURE / WARNING */
    private String status;

    /** 人类可读摘要（失败原因、变更明细等） */
    private String details;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
