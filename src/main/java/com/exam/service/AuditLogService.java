package com.exam.service;

import com.exam.auth.security.LoginUser;
import com.exam.common.ResponseCode;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 安全审计日志服务：异步记录关键安全事件
 */
@Slf4j
@Service
public class AuditLogService {

    @Autowired(required = false)
    private UserMapper userMapper;

    /**
     * 异步记录登录事件（成功/失败）
     */
    @Async
    @Transactional(rollbackFor = Exception.class)
    public void logLoginEvent(String username, boolean success, String ip, String reason) {
        try {
            Long userId = null;
            if (success) {
                User user = userMapper.selectOne(null, username);
                if (user != null) {
                    userId = user.getId();
                }
            }
            
            // TODO: 实际实现需要持久化到 audit_log 表
            logAuditEvent(userId, username, "LOGIN", ip, success ? "SUCCESS" : "FAILURE", reason);
        } catch (Exception e) {
            log.error("记录登录审计日志失败", e);
        }
    }

    /**
     * 异步记录权限变更事件
     */
    @Async
    @Transactional(rollbackFor = Exception.class)
    public void logPermissionChange(Long operatorId, String operatorUsername, 
                                     Long targetUserId, String targetUsername,
                                     String action, String details, String ip) {
        try {
            logAuditEvent(operatorId, operatorUsername, action, ip, "SUCCESS", details);
        } catch (Exception e) {
            log.error("记录权限变更审计日志失败", e);
        }
    }

    /**
     * 异步记录账户锁定事件
     */
    @Async
    @Transactional(rollbackFor = Exception.class)
    public void logAccountLock(String username, String ip, String lockReason) {
        try {
            logAuditEvent(null, username, "ACCOUNT_LOCKED", ip, "WARNING", lockReason);
        } catch (Exception e) {
            log.error("记录账户锁定审计日志失败", e);
        }
    }

    /**
     * 异步记录密钥校验事件
     */
    @Async
    @Transactional(rollbackFor = Exception.class)
    public void logJwtValidation(boolean success, String reason) {
        try {
            logAuditEvent(null, "SYSTEM", "JWT_VALIDATION", "N/A", 
                success ? "SUCCESS" : "FAILURE", reason);
        } catch (Exception e) {
            log.error("记录 JWT 校验审计日志失败", e);
        }
    }

    /**
     * 构建审计日志条目
     */
    private void logAuditEvent(Long userId, String username, String action, 
                               String ip, String status, String details) {
        LoginUser currentUser = null;
        try {
            // 尝试获取当前登录用户（可能为系统调用）
            Class<?> securityUtilClass = Class.forName("com.exam.auth.security.SecurityUtil");
            java.lang.reflect.Method getCurrentUserMethod = securityUtilClass.getMethod("getCurrentUser");
            Object result = getCurrentUserMethod.invoke(null);
            if (result != null) {
                currentUser = (LoginUser) result;
            }
        } catch (Exception e) {
            // 忽略，使用默认值
        }

        String traceId = UUID.randomUUID().toString().replace("-", "");
        
        // 实际实现应该写入数据库，这里仅记录日志
        log.info("[AUDIT] traceId={}, userId={}, username={}, action={}, ip={}, status={}, details={}",
            traceId, userId, username, action, ip, status, details);
    }
}
