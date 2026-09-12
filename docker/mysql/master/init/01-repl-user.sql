-- MySQL 主库初始化：创建复制专用账号（骨架阶段，仅复制账号）
-- 注：必须用 mysql_native_password，caching_sha2_password 在无 TLS 的复制连接上会报
--     "Authentication requires secure connection"
CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED WITH mysql_native_password BY 'repl123';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
FLUSH PRIVILEGES;
