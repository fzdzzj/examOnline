# examOnline —— 工程骨架（W1, add-project-skeleton）

在线考试系统单体工程骨架：Spring Boot 3.5.5 + Java 17 + MySQL 8.0（主从）+ Redis 7 + RabbitMQ（Compose 编排）。

## 快速开始

```bash
# 1. 一键起中间件（MySQL 主从 + Redis + RabbitMQ，含健康检查与依赖顺序）
docker compose up -d

# 2. 构建（依赖版本由父 POM dependencyManagement 集中锁定）
mvn clean install

# 3. 本地启动（dev 默认连 127.0.0.1:3306/6379，可用环境变量覆盖）
mvn spring-boot:run
# 或
java -jar target/exam-online.jar
```

- 健康检查：`GET /actuator/health`（返回 `UP`）
- dev 启动自动执行 `classpath:schema.sql` 幂等建表（RBAC 五表），并预置 ADMIN/TEACHER/STUDENT 角色与权限点。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | dev | dev / prod |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 本机 root/root | MySQL 连接 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | 127.0.0.1:6379 | Redis 连接 |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` | 空 | SMTP（dev 空则邮件走日志兜底） |
| `SERVER_PORT` | 8080 | HTTP 端口 |

## 工程结构

```
src/main/java/com/exam/
├── ExamOnlineApplication.java      # 入口
├── common/                         # 统一响应 / 错误码 / 全局异常 / RequestId 过滤器
└── user/                           # RBAC 五表实体 + Mapper
    ├── entity/                     # users/roles/permissions/user_roles/role_permissions
    └── mapper/
src/main/resources/
├── application.yml                 # 通用配置（含 logging pattern [%X{requestId}]）
├── application-dev.yml / -prod.yml # 多环境
└── schema.sql                      # 幂等建表脚本（MySQL 8 / H2 兼容）
docker-compose.yml                  # MySQL 主从(GTID) + Redis 7 + RabbitMQ
docker/mysql/{master,slave}/init/   # 复制账号 / 从库复制初始化
```

## 设计要点（对应规范）

- **统一响应**：`ApiResponse{code, message, data}`，成功 `code=0, message="ok"`；失败返回结构化错误码，全局异常处理器不泄漏堆栈。
- **可观测**：`RequestIdFilter` 生成/透传 `X-Request-Id` 写入 MDC，日志带 `[%X{requestId}]`，HTTP 基础日志（方法/路径/耗时/状态码）。
- **RBAC 五表**：`users.username` 唯一（学生=学号、教师=工号），`user_roles`/`role_permissions` 外键关联，建表幂等（`IF NOT EXISTS`）。
- **中间件编排**：Compose 定义 MySQL 主从（GTID 复制）等，`depends_on: condition: service_healthy` 保证依赖顺序。
- **软删除**：实体统一 `is_deleted`（MyBatis-Plus 逻辑删除，0/1）。

## 里程碑衔接

下一变更 `add-authentication` 将在此骨架上接入认证与鉴权（JWT 双 Token / Redis 黑名单 / 角色 AOP / 登录锁定 / 管理员初始化）。
