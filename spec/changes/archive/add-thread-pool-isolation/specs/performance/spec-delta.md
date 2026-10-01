# 规范差异：performance（线程池隔离）

> ⛔ **本 delta 未合入且不应合入**：其描述的三池隔离实现经核实为纯空转，已整体删除
> （见 `spec/changes/archive/add-thread-pool-isolation/proposal.md` 顶部与
> `spec/specs/performance/spec.md` 文末撤回说明）。照本文件合入会写入不存在的能力。

本文件包含对 `spec/specs/performance/spec.md` 的规范变更。

## ADDED Requirements

### Requirement: 线程池隔离

```markdown
WHEN 定义异步任务处理，
系统 SHALL 按业务域创建独立线程池，禁止共享同一线程池。

#### Scenario: 关键路径线程池
GIVEN 考试提交等核心接口
WHEN 执行异步任务
THEN 使用 submit 线程池:
  - corePoolSize = 10
  - maximumPoolSize = 50
  - queueCapacity = 100 (LinkedBlockingQueue)
  - keepAliveSeconds = 60
  - threadNamePrefix = "exam-submit-"

#### Scenario: 批量任务线程池
GIVEN 评分、Sweep 等批量任务
WHEN 执行异步任务
THEN 使用 grade 线程池:
  - corePoolSize = 20
  - maximumPoolSize = 100
  - queueCapacity = 500
  - threadNamePrefix = "exam-grade-"

#### Scenario: 后台采集线程池
GIVEN 行为监控、日志采集等非核心任务
WHEN 执行异步任务
THEN 使用 monitor 线程池:
  - corePoolSize = 5
  - maximumPoolSize = 20
  - queueCapacity = 50
  - threadNamePrefix = "exam-monitor-"
```

---

### Requirement: 拒绝策略与告警

```markdown
### Requirement: 线程池满处理
WHEN 线程池队列已满且达到最大线程数，
系统 SHALL 执行拒绝策略并上报指标。

#### Scenario: 拒绝策略配置
GIVEN 线程池饱和
WHEN 提交新任务
THEN 执行 ThreadPoolExecutor.AbortPolicy
AND 抛出 RejectedExecutionException
AND 上报计数器 thread_pool_rejected_total
AND 记录错误日志"thread pool saturated: submit"

#### Scenario: 告警触发
GIVEN 线程池 activeThreads > max*0.9
WHEN 检查周期到达（每分钟）
THEN 触发 Grafana 告警"Thread pool near saturation"
AND 通知运维团队
```

---

### Requirement: 上下文传递

```markdown
### Requirement: 跨线程上下文传播
WHEN 任务切换到工作线程，
系统 SHALL 保证以下上下文正确传递:
- Trace ID（分布式追踪）
- UserContext（用户信息）
- MDC 日志上下文

#### Scenario: 自动上下文包装
GIVEN @Async 方法调用
WHEN 任务执行
THEN 使用 AsyncUtils.wrap() 自动复制 ThreadLocal
AND 子线程可见 traceId、userId
AND 日志输出包含完整链路信息
```

---

### Requirement: 连接池隔离

```markdown
### Requirement: 数据库连接池参数调优
WHEN 配置数据源，
系统 SHALL 为不同业务设置连接池参数。

#### Scenario: 主业务连接池
GIVEN 考试查询、提交等核心操作
WHEN 获取数据库连接
THEN HikariConfig:
  - maximumPoolSize = 20
  - minimumIdle = 5
  - connectionTimeout = 30000

#### Scenario: 批量任务连接池（可选）
GIVEN 评分、导出等大批量操作
WHEN 需要独立连接池
THEN 创建第二个 DataSource:
  - maximumPoolSize = 50
  - minimumIdle = 10
  - 用于批处理任务
```

---

### Requirement: CPU/内存资源限制

```markdown
### Requirement: 线程资源管控
WHEN 启动应用，
系统 SHALL 设置 JVM 参数限制资源使用。

#### Scenario: JVM 参数配置
GIVEN Docker/K8s 部署
WHEN 容器启动
THEN 设置:
  - Xms=512m, Xmx=1g (堆内存)
  - XX:MaxCPUCount=4 (线程数限制)
AND 容器级别 limit:
  - memory: 2Gi
  - cpu: 2 cores
```

---

## MODIFIED Requirements

### Requirement: 性能监控

**原需求文本**:
```markdown
WHEN 执行关键操作，
系统 SHALL 暴露耗时指标。
```

**新需求文本**:
```markdown
WHEN 执行关键操作，
系统 SHALL:
1. 暴露耗时指标（Histogram）
2. 暴露线程池状态指标（Gauge + Counter）
3. 暴露连接池状态指标（Gauge）
```

#### Scenario: 线程池指标
GIVEN 应用运行中
WHEN 访问 /actuator/prometheus
THEN 可见:
  - thread_pool_active_threads{pool="submit"} (Gauge)
  - thread_pool_queue_size{pool="submit"} (Gauge)
  - thread_pool_completed_total{pool="submit"} (Counter)
  - thread_pool_rejected_total{pool="submit"} (Counter)

---

## REMOVED Requirements

无移除项。

---

## 规范变更总结

| 类型 | 需求名称 | 变更说明 |
|------|---------|----------|
| ADDED | 线程池隔离 | 按业务域划分 submit/grade/monitor |
| ADDED | 拒绝策略与告警 | 线程池满时触发告警 |
| ADDED | 上下文传递 | 跨线程传播 Trace ID、UserContext |
| ADDED | 连接池隔离 | 为核心业务与批量任务配置不同连接池 |
| ADDED | CPU/内存限制 | JVM 参数与容器资源限制 |
| MODIFIED | 性能监控 | 新增线程池与连接池指标 |

---

## 验证清单

- [ ] Prometheus 可见 3 个线程池的 active/queue/completed 指标
- [ ] Grafana Dashboard 展示线程池状态
- [ ] 批量导出 1000 份成绩不阻塞考试提交接口
- [ ] 线程池满时触发告警而非静默失败
- [ ] 压测：100 并发下核心接口 P95 < 200ms
- [ ] 全量测试通过，无回归问题

---

## 参考示例

### ✅ 正确示例：线程池配置

```java
@Configuration
public class ThreadPoolConfig {
    
    @Bean("submitExecutor")
    public Executor examSubmitExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("exam-submit-");
        executor.setRejectedExecutionHandler(
            new ThreadPoolExecutor.AbortPolicy()
        );
        executor.initialize();
        return executor;
    }
    
    @Bean("gradeExecutor")
    public Executor examGradeExecutor() {
        // ... 类似配置
    }
}
```

### ✅ 正确示例：@Async 使用

```java
@Service
public class ExamSubmitService {
    
    @Async("submitExecutor")
    public CompletableFuture<Void> submitAsync(SubmitRequest request) {
        // 异步提交逻辑
    }
}
```

### ❌ 错误示例：默认线程池

```java
// ❌ 禁止使用默认 @Async（无隔离）
@Async
public void submitAsync(SubmitRequest request) {
    // ...
}
```
