# 提案：事务边界细化与原子性保障（阶段 9，数据一致性强化）

## Why

**当前问题**: `ScoreService.publish()` 方法存在事务边界过大导致的**部分提交风险**。

```java
// ScoreService.java:305-316
@Transactional(rollbackFor = Exception.class)
public List<ScoreActionItem> publish(List<Long> examIds) {
    List<ScoreActionItem> results = new ArrayList<>(examIds.size());
    for (Long examId : examIds) {
        try {
            results.add(publishOne(examId)); // ❌ 整个方法在一个大事务中
        } catch (BusinessException e) {
            results.add(new ScoreActionItem(examId, false, e.getMessage()));
        }
    }
    return results;
}
```

**风险场景**: 
- 发布 10 个考试，前 5 个成功，第 6 个失败
- **当前行为**: 前 5 个已提交（无法回滚），第 6 个及之后失败
- **期望行为**: 每个考试独立事务，互不影响

**影响范围**:
- **数据一致性**: 批量操作可能出现"部分成功"的半提交状态
- **业务语义**: 用户期望"批量发布"是并发而非强事务关联
- **可观测性**: 无单个考试发布的监控指标

**背景**:
- 项目已有三级幂等设计（Redis 锁 + 去重表 + CAS），但事务粒度未细化
- `@Transactional(timeout = 30)` 缺失，长事务可能阻塞连接池
- 参考 Spring 官方建议："将事务粒度控制在最小必要范围"

**期望状态**:
- `publishOne()` 方法单独加 `@Transactional`，实现原子性发布
- 添加事务超时配置（30 秒）
- 暴露 `exam.publish.success.count` 和 `exam.publish.fail.count` 指标

## What Changes

### 代码变更

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `ScoreService.java:305-316` | MODIFIED | 拆分事务边界，`publishOne()` 独立事务 |
| `ScoreService.java:320` | ADDED | 添加 Micrometer 计数器 |
| `application.yml` | ADDED | 全局事务超时默认值配置 |

### 规范变更

- `spec/specs/data-consistency/spec.md` - **MODIFIED**: 新增事务粒度要求
- `spec/specs/observability/spec.md` - **ADDED**: 事务监控指标规范

## Impact

### 受影响的规范
- `spec/specs/data-consistency/spec.md` - 修改事务粒度策略
- `spec/specs/observability/spec.md` - 新建事务监控规范

### 受影响的代码
- `com.exam.service.ScoreService` - 事务边界重构
- `com.exam.config.MetricsConfig` - 新增计数器定义
- `application.yml` - 新增全局事务超时配置

### 用户影响
- **无功能变化**: 批量发布结果相同，只是原子性更强
- **性能提升**: 小事务减少锁竞争，提升并发能力
- **监控增强**: 可观察单次发布成功率

### API 变更
- 无外部 API 变更

### 需要迁移
- [x] 数据库迁移（无）
- [ ] 配置变更（application.yml 新增配置项）
- [x] 文档更新（规范 + 决策记录）
- [ ] 测试验证（需回归批量发布场景）

## 时间线评估

**中等**: 约 1-2 天（W11）
- 代码重构：0.5 天
- 指标接入：0.5 天
- 测试验证：0.5 天
- 文档更新：0.25 天

## 风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 事务拆分后性能下降 | 低 | 中 | 压测验证，必要时调整批处理大小 |
| 漏改其他 Service 的大事务 | 中 | 中 | 全项目 grep `@Transactional` 扫描 |
| 指标采集遗漏 | 低 | 低 | 使用 Micrometer 模板类统一注册 |

## 验收标准

1. ✅ `publishOne()` 方法有独立的 `@Transactional` 注解
2. ✅ 单个考试发布失败不影响其他考试
3. ✅ Micrometer 计数器正常上报（`exam.publish.success.count`, `exam.publish.fail.count`）
4. ✅ 事务超时 30 秒自动回滚（通过压测验证）
5. ✅ 全量测试通过，无回归问题

## 备选方案

**方案 B（不推荐）**: 保持现有大事务，增加补偿机制
- 缺点：复杂度高，需要额外的事务日志和补偿逻辑

**方案 C（折中）**: 使用 `TransactionTemplate` 手动控制
- 缺点：样板代码多，不如注解清晰

**推荐方案 A**: 最小改动，符合 Spring 最佳实践
