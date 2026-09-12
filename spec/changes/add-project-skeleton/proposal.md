# 提案：工程骨架（阶段 1/2，W1）

## Why

examOnline 需要一个可编译、可一键启动的工程骨架作为一切业务能力的地基。没有骨架，认证、题库、考试等业务都无法落地。本阶段对标 sports 项目的 `add-microservice-skeleton`：先让工程"立起来"，再在下一个变更（认证）里接入业务。

**背景**：
- 单体 Spring Boot 3.5.5 + Java 17 + MySQL 8.0 主从 + Redis 7 + RabbitMQ + Vue3（见 `docs/面试版实施方案.md` v3 §三）。
- 数据模型采用 RBAC 五表；学生以学号、教师以工号为登录账号（已确认，见 `docs/需求决策记录.md`）。
- 交付标准：`mvn clean install` 全量通过、`docker compose up` 一键起 MySQL 主从 + Redis + RabbitMQ。

**当前状态**：工作区仅有 `docs/` 三份文档与 `spec/` 规范资产，无任何工程代码。

**期望状态**：具备可编译、可启动的单体工程骨架；统一响应/异常/日志；RBAC 五表建表脚本；Docker Compose 中间件编排。

## What Changes

- **单体工程骨架**：Spring Boot 3.5.5 + Maven 依赖（web/jjwt/redis/mysql/mybatis-plus/lombok/mail）。
- **统一响应与错误码**：`ApiResponse<T>` 包装 + `GlobalExceptionHandler` + 错误码枚举。
- **可观测基础**：RequestId 过滤器 + 结构化日志。
- **数据模型**：RBAC 五表（users/roles/permissions/user_roles/role_permissions）建表脚本，`users.username` 唯一（学生=学号、教师=工号），预置 ADMIN/TEACHER/STUDENT 角色。
- **中间件编排**：Docker Compose 编排 MySQL 主从 + Redis + RabbitMQ，含健康检查与依赖顺序。

## Impact

### 受影响的规范
- `spec/specs/authentication/spec.md` - 追加工程基础能力需求（`ADDED`）：工程结构、依赖锁定、统一响应、数据库初始化、中间件编排。

### 受影响的代码
- 工程根 `pom.xml`、`src/main/java/.../common`（响应/异常/日志）、`src/main/resources`（配置/建表 SQL）、`docker-compose.yml`

### 用户影响
- 本阶段无对外业务功能，仅工程就绪。

### API 变更
- 无业务端点；提供健康检查端点 `GET /actuator/health`。

### 需要迁移
- [x] 数据库迁移（RBAC 五表建表脚本）
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（本提案 + 规范）

## 时间线评估

小：约 2-3 天（W1 前半，对应执行计划「W1 骨架 + 认证」的骨架部分）。

## 风险

- **依赖版本冲突**（Spring Boot 3.5.5 × MyBatis-Plus × jjwt）—— 缓解：父 POM `dependencyManagement` 集中锁定版本矩阵。
- **主从 + MQ 编排复杂拖慢启动** —— 缓解：本阶段仅编排容器，读写分离与 MQ 业务接入留待后续变更；骨架以单库可跑通为准。
