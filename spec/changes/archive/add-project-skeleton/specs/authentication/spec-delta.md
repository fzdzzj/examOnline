# 规范差异：authentication

本文件包含对 `spec/specs/authentication/spec.md` 的规范变更（工程骨架阶段，全部为新增）。

## ADDED Requirements

### Requirement: 单体工程结构
WHEN 工程被构建,
系统 SHALL 通过 `mvn clean install` 全量编译打包，且 SHALL 提供健康检查端点。

#### Scenario: 全量构建成功
GIVEN 本地已安装 JDK 17 与 Maven
WHEN 执行 `mvn clean install`
THEN 工程编译打包成功
AND 无依赖缺失报错

#### Scenario: 健康检查可访问
GIVEN 应用已启动
WHEN 访问健康检查端点
THEN 返回 UP 状态

### Requirement: 依赖版本集中锁定
WHERE 工程声明依赖,
系统 SHALL 由父 POM dependencyManagement 统一管理版本，避免版本冲突。

#### Scenario: 依赖版本一致
GIVEN 工程引入 mybatis-plus、jjwt、redis 等依赖
WHEN 构建解析依赖
THEN 版本继承父 POM 锁定值
AND 与 Spring Boot 3.5.5 兼容矩阵一致

### Requirement: 统一响应与错误码
WHEN 任一接口返回结果,
系统 SHALL 使用统一 `ApiResponse<T>` 包装；失败时 SHALL 返回结构化错误码与信息。

#### Scenario: 成功响应包装
GIVEN 接口处理成功
WHEN 返回结果
THEN 响应体为 `{code:0, message:"ok", data:<payload>}`

#### Scenario: 业务异常
GIVEN 接口抛出业务异常
WHEN 全局异常处理器拦截
THEN 返回对应错误码与可读信息
AND 不泄漏堆栈细节

### Requirement: 数据库初始化
WHEN 首次启动系统,
系统 SHALL 执行建表脚本初始化 RBAC 五表，且建表 SHALL 幂等。

#### Scenario: 首次建表成功
GIVEN 数据库为空
WHEN 执行建表脚本
THEN 五表创建成功
AND users.username 唯一约束生效

#### Scenario: 幂等重放
GIVEN 表已存在
WHEN 再次执行建表脚本
THEN 脚本幂等，不报错且不破坏既有结构

### Requirement: 中间件一键编排
WHEN 开发者执行 `docker compose up`,
系统 SHALL 一键启动 MySQL 主从、Redis、RabbitMQ，并保证启动依赖顺序与健康就绪。

#### Scenario: 一键起全部中间件
GIVEN 已安装 Docker
WHEN 执行 `docker compose up`
THEN 各中间件按依赖顺序启动
AND 健康检查全部就绪

#### Scenario: 依赖未就绪被等待
GIVEN MySQL 尚未健康
WHEN 依赖 MySQL 的容器启动
THEN 容器等待 MySQL 健康检查通过后再启动
