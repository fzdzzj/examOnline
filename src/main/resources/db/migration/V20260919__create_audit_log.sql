-- 安全审计日志表
-- 记录关键安全事件：登录、权限变更、账户锁定等

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    trace_id VARCHAR(64) NOT NULL COMMENT '追踪 ID（用于关联同一请求的多个日志）',
    user_id BIGINT DEFAULT NULL COMMENT '用户 ID（系统操作为 NULL）',
    username VARCHAR(64) NOT NULL COMMENT '操作用户名',
    action VARCHAR(50) NOT NULL COMMENT '操作类型（LOGIN, LOGOUT, PERMISSION_CHANGE, ACCOUNT_LOCKED 等）',
    ip_address VARCHAR(45) NOT NULL COMMENT '操作 IP 地址（支持 IPv6）',
    status VARCHAR(20) NOT NULL COMMENT '状态（SUCCESS, FAILURE, WARNING）',
    details TEXT DEFAULT NULL COMMENT '详细信息/原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    INDEX idx_trace_id (trace_id),
    INDEX idx_user_id (user_id),
    INDEX idx_username (username),
    INDEX idx_action (action),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at),
    INDEX idx_ip_address (ip_address)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='安全审计日志表';

-- 示例数据（可选）
-- INSERT INTO audit_log (trace_id, user_id, username, action, ip_address, status, details, created_at)
-- VALUES ('abc123', 1, 'admin', 'LOGIN', '192.168.1.100', 'SUCCESS', '用户登录成功', NOW());
