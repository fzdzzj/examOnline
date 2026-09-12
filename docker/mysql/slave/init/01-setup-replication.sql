-- MySQL 从库初始化：指向主库并启动 GTID 复制
-- 依赖主库健康（docker-compose depends_on condition: service_healthy）
CHANGE MASTER TO
  MASTER_HOST='mysql-master',
  MASTER_PORT=3306,
  MASTER_USER='repl',
  MASTER_PASSWORD='repl123',
  MASTER_AUTO_POSITION=1;

START SLAVE;
