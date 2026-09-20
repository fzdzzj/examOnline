# 提案：并发监控与锁性能优化（阶段 9，可观测性增强）

## Why

**当前问题**: Redis 分布式锁无等待时间监控，无法识别锁竞争热点。

### 问题现状

```java
// ExamSubmitService.java:126-130
String lockKey = "exam:submit:lock:" + examId;
Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, uuid, 30, TimeUnit.SECONDS);
if (!locked) {
    Thread.sleep(50); // ❌ 轮询无监控
}
```

**缺失能力**:
- 无锁等待时间分布指标（P50/P95/P99）
- 无锁竞争次数统计
- 无超时重试次数追踪
- 无法定位高频竞争接口

**影响分析**:
| 场景 | 当前表现 | 期望表现 |
|------|---------|----------|
| 考试提交峰值 | 未知锁等待耗时 | P95 < 100ms |
| 多实例部署 | 无法量化锁开销 | 暴露 `exam.submit.lock.wait.time` |
| 重复扫描率 | 无监控指标 | `exam.sweep.duplicate_rate` |

**背景**:
- 项目已有三级幂等设计（Redis 锁 + 去重表 + CAS）
- 但缺少"锁本身"的监控，形成盲区
- 面试价值：可讲"分布式锁监控"实战经验

**期望状态**:
- 暴露锁等待时间直方图指标
- 记录锁竞争次数
- 自动识别热点 Key

## What Changes

### 代码变更

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `ExamSubmitService.java` | ADDED | 添加锁等待时间计数器 |
| `ExamSweepService.java` | ADDED | 添加重复扫描率指标 |
| `MetricsConfig.java` | ADDED | 新增 Histogram 定义 |
| `application.yml` | ADDED | 启用 Micrometer 调节器 |

### 规范变更

- `spec/specs/observability/spec.md` - **MODIFIED**: 新增锁监控要求

## Impact

### 受影响的规范
- `spec/specs/observability/spec.md` - 修改监控粒度

### 受影响的代码
- `com.exam.service.ExamSubmitService`
- `com.exam.service.ExamSweepService`
- `com.exam.config.MetricsConfig`

### 用户影响
- **可观测性提升**: 可观察锁竞争情况
- **性能优化**: 识别并解决热点 Key

### API 变更
- 无外部 API 变更

### 需要迁移
- [ ] 配置变更（Micrometer 配置）
- [x] 文档更新（规范 + 决策记录）
- [ ] 测试验证（压测对比）

## 时间线评估

**小**: 约 1 天（W13）
- 指标接入：0.5 天
- 压测验证：0.25 天
- 文档更新：0.25 天

## 风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 指标采集增加延迟 | 低 | 低 | 使用异步上报 |
| 误报竞争热点 | 低 | 低 | 结合业务上下文分析 |

## 验收标准

1. ✅ Prometheus 可见 `exam_submit_lock_wait_seconds` 直方图
2. ✅ `exam_sweep_duplicate_total` 计数器正常上报
3. ✅ Grafana Dashboard 展示锁等待 P95 < 100ms
4. ✅ 压测：100 并发下锁等待无显著增加
5. ✅ 全量测试通过，无回归问题
