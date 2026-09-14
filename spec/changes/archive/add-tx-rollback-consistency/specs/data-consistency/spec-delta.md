# 规范差异：data-consistency（事务回滚策略）

本文件包含对 `spec/specs/data-consistency/spec.md` 的规范变更（事务回滚策略，全部为新增）。

## ADDED Requirements

### Requirement: 事务显式回滚
WHEN 声明事务,
系统 SHALL 显式使用 `@Transactional(rollbackFor = Exception.class)`，不应依赖 Spring 默认回滚语义（仅 unchecked 回滚）。

#### Scenario: 显式声明回滚
GIVEN 一个需要事务的方法
WHEN 开发者声明事务
THEN 使用 `@Transactional(rollbackFor = Exception.class)`
AND 使受检异常也触发回滚

#### Scenario: 裸注解被禁止
GIVEN 代码中存在裸 `@Transactional`（不带 rollbackFor）
WHEN 代码审查或提交前检查
THEN 视为不符合规范
AND 要求补齐 rollbackFor

### Requirement: 受检异常转换
WHEN 业务方法需要抛出受检异常（如 JsonProcessingException/IOException）,
系统 SHALL 在业务边界将其转换为 RuntimeException（BusinessException），而非让受检异常跨事务边界传播。

#### Scenario: 受检异常转业务异常
GIVEN 序列化/IO 等操作抛出受检异常
WHEN 处于事务方法内
THEN catch 并转换为 BusinessException 重新抛出
AND 事务因 RuntimeException 回滚

#### Scenario: 防未来静默不回滚
GIVEN 未来有人直接 throws 受检异常而未转换
WHEN 方法声明了 rollbackFor = Exception.class
THEN 事务仍能回滚
AND 不会出现数据半提交