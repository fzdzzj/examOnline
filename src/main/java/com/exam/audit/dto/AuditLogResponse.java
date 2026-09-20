package com.exam.audit.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 安全审计日志视图（管理员查询返回）。
 *
 * <p>只读投影，不外泄实体本身；traceId 暴露给前端是为了让人从一条审计直接跳查链路。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {

    private Long id;
    /** 链路标识，可跳 Jaeger 查对应 trace */
    private String traceId;
    /** 操作人 ID；系统事件或账号不存在时为 null */
    private Long userId;
    private String username;
    /** LOGIN / ACCOUNT_LOCKED ... */
    private String action;
    private String ipAddress;
    /** SUCCESS / FAILURE / WARNING */
    private String status;
    private String details;
    private LocalDateTime createdTime;
}
