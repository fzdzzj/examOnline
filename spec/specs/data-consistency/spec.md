# data-consistency 规范

> 能力域：数据一致性（阶段 8，W10；2026-09-20 补批量操作失败分级与代理增强有效性）。
> 来源：`spec/changes/archive/add-tx-rollback-consistency` 合入（事务显式回滚、受检异常转换）+ `spec/changes/archive/optimize-transaction-boundary` 合入（批量操作失败分级、声明式增强必须生效；其"每场独立事务"主张经核实为伪，未合入）。
> 实施注记：2026-09-13 统一 26 处裸注解为 `@Transactional(rollbackFor = Exception.class)`（9 个 Service 类），未引入传播/隔离/只读等其他属性；当前 `^\s*@Transactional\s*$` 残留 0 处。决策记录见 `docs/需求决策记录.md` §十五。

## Requirements

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

---

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

---

### Requirement: 批量操作失败分级

WHEN 以列表形式批量处理互相独立的实体（如批量发布成绩）,

系统 SHALL 让**业务性失败**只影响该条并逐条返回结果，让**基础设施异常**中止整批并回滚，二者不得混为一谈。

#### Scenario: 业务性失败逐条隔离

GIVEN 批量发布包含一场不满足前置条件的考试

WHEN 执行批量发布

THEN 该场返回失败项与原因

AND 其余考试的写入照常提交

AND 失败场次不留审计痕迹

#### Scenario: 基础设施异常整批回滚

GIVEN 批量处理第 2 条时数据库抛出非业务异常

WHEN 执行批量发布

THEN 该异常向外传播并回滚整个事务

AND 第 1 条已写入的状态迁移与审计一并撤销

AND 不得静默留下"半批已发布"

---

### Requirement: 声明式增强必须真的生效

WHEN 在方法上声明事务或异步等由代理实现的增强注解,

系统 SHALL 确认该声明在运行期实际生效；不生效的声明必须删除，不得靠注释暗示其行为。

#### Scenario: 私有或自调用方法不得挂代理注解

GIVEN 一个 private 方法，或被同类其它方法直接调用的方法

WHEN 其上标注 `@Transactional` 或 `@Async`

THEN 视为无效声明（Spring 不代理私有方法，自调用绕过代理）

AND 应改由外部协作者承载该增强，或删去注解并如实描述真实边界

#### Scenario: 有返回值的方法不得挂 @Async

GIVEN 一个返回值被调用方使用的方法（如返回判定结果的采集方法）

WHEN 其上标注 `@Async`

THEN 运行期抛"Invalid return type for async method"

AND 该注解必须移除，异步化需连带改造调用契约

---

> 合入注记（2026-09-20，`optimize-transaction-boundary`）：该提案原拟"把批量发布拆成每场独立事务"
> **前提不成立并已回退**——业务性失败在循环内被捕获，根本不污染外层事务；基础设施异常一路上抛、
> 整批回滚，正是期望语义，故未合入"每场独立事务"需求，改为上面的两条。
> 提案另要求"`@Transactional` 补 timeout"亦无需实施：`spring.transaction.default-timeout: 30`
> 已在 `application.yml`。上述两场景均由真实 H2 集成用例锁定
> （`ScorePublishTransactionIntegrationTest`）；此前的 Mockito 单测把 mapper 全打桩，
> 只能证明"循环中止"，无法证明"整批回滚"。
