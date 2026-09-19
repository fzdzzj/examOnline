# 线程池隔离规范 (Thread Pool Isolation)

## 概述

本规范定义了 examOnline 项目的线程池隔离策略，按业务域创建独立线程池，避免资源抢占风险。

## 设计目标

1. **业务隔离**: 不同业务域使用独立线程池，防止相互影响
2. **资源管控**: 为每个线程池配置合理的核心/最大线程数和队列容量
3. **可观测性**: 通过 Micrometer 暴露线程池指标到 Prometheus
4. **告警机制**: 线程池满时触发拒绝策略并记录日志

## 线程池配置

### 1. Submit 线程池 (考试提交核心路径)

**用途**: 处理学生交卷操作（高优先级、低延迟场景）

| 参数 | 值 | 说明 |
|------|-----|------|
| Core Pool Size | 10 | 核心线程数 |
| Max Pool Size | 50 | 最大线程数 |
| Queue Capacity | 100 | 队列容量 |
| Thread Name Prefix | `exam-submit-` | 线程名称前缀 |
| Keep Alive Seconds | 60 | 非核心线程存活时间 |
| Rejection Policy | AbortPolicy | 队列满时直接拒绝 |

**拒绝策略**: 抛出异常，确保快速失败，不影响用户体验

**监控指标**:
- `thread_pool_rejected{pool="submit"}` - 拒绝次数计数器
- `thread_pool_rejected_total{pool="submit"}` - 拒绝次数 Gauge

### 2. Grade 线程池 (评分与批量任务)

**用途**: 处理评分任务和批量导出（高吞吐批处理场景）

| 参数 | 值 | 说明 |
|------|-----|------|
| Core Pool Size | 20 | 核心线程数 |
| Max Pool Size | 100 | 最大线程数 |
| Queue Capacity | 500 | 队列容量 |
| Thread Name Prefix | `exam-grade-` | 线程名称前缀 |
| Keep Alive Seconds | 60 | 非核心线程存活时间 |
| Rejection Policy | CallerRunsHandler | 由调用线程执行，提供背压 |

**拒绝策略**: CallerRunsHandler - 由当前线程执行任务，提供背压保护

**监控指标**:
- `thread_pool_rejected{pool="grade"}` - 拒绝次数计数器
- `thread_pool_rejected_total{pool="grade"}` - 拒绝次数 Gauge

### 3. Monitor 线程池 (监控采集后台任务)

**用途**: 处理行为事件采集等后台监控任务（低优先级后台场景）

| 参数 | 值 | 说明 |
|------|-----|------|
| Core Pool Size | 5 | 核心线程数 |
| Max Pool Size | 20 | 最大线程数 |
| Queue Capacity | 50 | 队列容量 |
| Thread Name Prefix | `exam-monitor-` | 线程名称前缀 |
| Keep Alive Seconds | 30 | 非核心线程存活时间 |
| Rejection Policy | AbortPolicy | 队列满时直接拒绝 |

**拒绝策略**: 抛出异常，后台任务失败不影响主流程

**监控指标**:
- `thread_pool_rejected{pool="monitor"}` - 拒绝次数计数器
- `thread_pool_rejected_total{pool="monitor"}` - 拒绝次数 Gauge

## 使用方式

### 1. 启用异步执行

在启动类添加 `@EnableAsync` 注解：

```java
@EnableAsync
@SpringBootApplication
public class ExamOnlineApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExamOnlineApplication.class, args);
    }
}
```

### 2. 指定线程池

使用 `@Async("executorName")` 注解指定线程池：

```java
@Service
public class ExamSubmitService {
    
    /** 异步交卷：使用 submitExecutor 线程池 */
    @Async("submitExecutor")
    public void submitAsync(Long examId, SubmitRequest request) {
        submit(examId, request);
    }
}
```

### 3. 可用线程池名称

- `submitExecutor` - 考试提交线程池
- `gradeExecutor` - 评分与批量任务线程池
- `monitorExecutor` - 监控采集线程池

## 监控与告警

### Prometheus 指标

所有线程池指标自动暴露到 `/actuator/prometheus` 端点：

```prometheus
# 示例指标
thread_pool_rejected{pool="submit"} 0
thread_pool_rejected_total{pool="submit"} 0
thread_pool_active{pool="submit"} 5
thread_pool_completed{pool="submit"} 150
```

### Grafana Dashboard

建议在 Grafana 中创建以下面板：

1. **线程池活跃数**: `thread_pool_active` Gauge
2. **队列大小**: `thread_pool_queue_size` Gauge  
3. **拒绝次数**: `thread_pool_rejected` Counter
4. **完成次数**: `thread_pool_completed` Counter

### 告警规则

建议配置以下告警：

```yaml
groups:
  - name: thread-pool-alerts
    rules:
      - alert: ThreadPoolRejected
        expr: rate(thread_pool_rejected[5m]) > 0
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "线程池 {{ $labels.pool }} 检测到拒绝"
          
      - alert: ThreadPoolHighUtilization
        expr: thread_pool_active / thread_pool_max > 0.9
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "线程池 {{ $labels.pool }} 利用率超过 90%"
```

## 性能调优

### 调整策略

1. **核心线程数不足**: 增加 `corePoolSize`
2. **队列堆积严重**: 增加 `queueCapacity` 或调整 `maxPoolSize`
3. **频繁拒绝**: 检查是否配置合理，考虑扩容

### 压测验证

建议进行以下压测场景：

1. **并发交卷测试**: 验证 submit 线程池在高并发下的表现
2. **批量导出测试**: 验证 grade 线程池处理大批量任务的能力
3. **混合负载测试**: 验证三个线程池互不干扰

## 配置开关

可通过以下配置禁用线程池隔离（默认启用）：

```yaml
thread-pool:
  isolation:
    enabled: true  # 设置为 false 禁用线程池隔离
```

## 参考文档

- [Spring ThreadPoolTaskExecutor](https://docs.spring.io/spring-framework/reference/core/testing/async-execution.html)
- [阿里巴巴 Java 开发手册 - 线程池规范](https://github.com/alibaba/checkstyle/blob/master/checkstyle-checks/src/main/resources/alibaba/checkstyle.xml)
- [Micrometer 指标文档](https://micrometer.io/docs/concepts)

## 版本历史

| 版本 | 日期 | 变更说明 |
|------|------|----------|
| 1.0 | 2026-09-20 | 初始版本，定义三个独立线程池 |
