# dev-config 规范变更

## ADDED Requirements

### Requirement: 基础设施与运行时配置显式化与环境隔离

系统 SHALL 清理无效的 Boot 自动配置死配置（RabbitMQ listener），将所有隐式 @Value 运行时旋钮在 application.yml 中显式声明，并在 dev/prod 环境下提供合理的默认 Redis 分库隔离（dev: 0, prod: 1）。

#### Scenario: 死配置清理与隐式旋钮显式化 (harden-cross-cutting-edge-cases)

GIVEN 系统读取主配置文件 `application.yml`
WHEN 检查配置树
THEN 不包含未生效的 `spring.rabbitmq.listener.simple` 配置
AND 显式声明预约提醒、MQ死信重试、用户/题库导入上限、题干图片存储、判分分值比例等全部业务旋钮
AND `application-prod.yml` 默认分库为 `database: ${REDIS_DATABASE:1}`
