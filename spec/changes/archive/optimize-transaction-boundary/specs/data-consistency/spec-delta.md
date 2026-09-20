# 规范差异：data-consistency（事务粒度与原子性）

> ⚠️ **本 delta 大部分不应合入**：其"每场独立事务""批量操作事务隔离"主张经核实前提不成立
> （业务性失败在循环内被捕获、不污染外层事务；基础设施异常整批回滚正是期望语义），
> 相关实现已回退。实际合入 `spec/specs/data-consistency/spec.md` 的是
> 「批量操作失败分级」与「声明式增强必须真的生效」两条，与本文件内容不同，勿照搬。
> 其中"补 `timeout`"亦无需实施——`spring.transaction.default-timeout: 30` 本已在配置中。

本文件包含对 `spec/specs/data-consistency/spec.md` 的规范变更。

## MODIFIED Requirements

### Requirement: 事务显式回滚

**原需求文本**:
```markdown
WHEN 声明事务，
系统 SHALL 显式使用 `@Transactional(rollbackFor = Exception.class)`，不应依赖 Spring 默认回滚语义。
```

**新需求文本**:
```markdown
WHEN 声明事务，
系统 SHALL 显式使用 `@Transactional(rollbackFor = Exception.class, timeout = 30)`，
不应依赖 Spring 默认回滚语义，且应设置超时时间防止长事务阻塞连接池。
```

#### Scenario: 显式声明回滚与超时
GIVEN 一个需要事务的方法
WHEN 开发者声明事务
THEN 使用 `@Transactional(rollbackFor = Exception.class, timeout = 30)`
AND 使受检异常也触发回滚
AND 30 秒未完成自动回滚

#### Scenario: 裸注解被禁止
GIVEN 代码中存在裸 `@Transactional`（不带 rollbackFor）
WHEN 代码审查或提交前检查
THEN 视为不符合规范
AND 要求补齐 rollbackFor 和 timeout

---

### Requirement: 事务粒度控制

**新增需求**:

```markdown
### Requirement: 批量操作事务隔离
WHEN 处理批量业务操作（如批量发布、批量评分）,
系统 SHALL 为每个独立实体分配单独的事务边界，避免"部分成功"的半提交状态。

#### Scenario: 批量发布考试
GIVEN 10 个待发布的考试 ID
WHEN 调用 publish(List<Long> examIds)
THEN 每个考试在独立事务中发布
AND 单个考试失败不影响其他考试提交
AND 记录每个考试的成功/失败指标

#### Scenario: 批量评分任务
GIVEN 100 份待评分试卷
WHEN 调用 gradeBatch(List<Long> submissionIds)
THEN 每份试卷在独立事务中评分
AND 单份试卷评分失败不阻塞其他试卷
AND 暴露 grade.success.count 和 grade.fail.count 指标
```

#### Scenario: 大事务检测
GIVEN 一个包含循环的事务方法
WHEN 代码审查
THEN 标记为潜在大事务风险
AND 建议拆分为独立事务

---

### Requirement: 事务监控指标

**新增需求**:

```markdown
### Requirement: 事务可观测性
WHEN 执行关键事务操作（发布、评分、提交等）,
系统 SHALL 暴露以下 Micrometer 计数器：
- `<operation>.success.count`: 成功次数
- `<operation>.fail.count`: 失败次数
- `<operation>.duration.ms`: 平均耗时（ Histogram）

#### Scenario: 发布操作监控
GIVEN 考试发布服务
WHEN 发布完成
THEN Prometheus 端点可获取：
  - exam_publish_success_total
  - exam_publish_fail_total
  - exam_publish_duration_seconds

#### Scenario: 评分操作监控
GIVEN 试卷评分服务
WHEN 评分完成
THEN Prometheus 端点可获取：
  - score_grade_success_total
  - score_grade_fail_total
  - score_grade_duration_seconds
```

---

### Requirement: 全局事务配置

**新增需求**:

```markdown
### Requirement: 默认事务超时
WHEN 应用启动时，
系统 SHALL 配置全局事务超时默认值（spring.transaction.default-timeout=30s）。

#### Scenario: 配置文件验证
GIVEN application.yml
WHEN 读取 Spring 配置
THEN spring.transaction.default-timeout 值为 30s
AND 每个 @Transactional 可覆盖此默认值
```

---

## ADDED Requirements

### Requirement: 事务日志审计

```markdown
WHEN 事务提交或回滚，
系统 SHALL 记录审计日志，包含：
- 事务名称（方法签名）
- 输入参数摘要（脱敏）
- 执行结果（成功/失败原因）
- 耗时（毫秒）

#### Scenario: 发布事务审计
GIVEN 考试发布事务
WHEN 事务完成
THEN 日志记录：
  "Transaction [publishOne] completed: examId=123, status=SUCCESS, duration=245ms"
OR
  "Transaction [publishOne] rolled back: examId=124, reason=BusinessException: 考试已发布"
```

---

## REMOVED Requirements

无移除项。

---

## 规范变更总结

| 类型 | 需求名称 | 变更说明 |
|------|---------|----------|
| MODIFIED | 事务显式回滚 | 新增 timeout=30 参数 |
| ADDED | 事务粒度控制 | 批量操作需独立事务 |
| ADDED | 事务监控指标 | 添加 Micrometer 计数器 |
| ADDED | 全局事务配置 | 默认超时 30 秒 |
| ADDED | 事务日志审计 | 记录事务执行结果 |

---

## 验证清单

- [ ] `grep '@Transactional' ScoreService.java` 显示 `publishOne()` 有独立注解
- [ ] `curl http://localhost:8080/actuator/prometheus | grep exam_publish` 可见指标
- [ ] `application.yml` 包含 `spring.transaction.default-timeout: 30s`
- [ ] 日志中可见事务审计记录
- [ ] 批量发布测试通过，部分失败不影响其他考试
