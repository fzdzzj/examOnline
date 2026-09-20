# 提案：线程池隔离与资源管控（阶段 9，性能优化）

> ⛔ **本提案已整体撤回并删除实现**（核实见
> [`../../IMPLEMENTATION_STATUS.md`](../../IMPLEMENTATION_STATUS.md)）。
> 对应的 `46d7004` 曾交付三个线程池 + `@EnableAsync` + 一个断言池参数的测试，
> 现已全部移除。
>
> 撤回理由（按发现顺序）：
>
> 1. **两处 @Async 挂错在"有返回值且返回值被调用方使用"的方法上**，Spring 直接抛
>    `Invalid return type for async method (only Future and void supported)`：
>    `ExamSweepService.sweep()` 返回 `int`（且由同 bean 的 `scheduledSweep()` 自调用，
>    自调用本就绕过代理，注解永远不可能生效）；
>    `BehaviorEventCollectService.collect()` 返回 `EventVerdict`，其契约明确要求同步返回
>    判定结果。两处移除后，行为上报接口从全量 500 恢复。
> 2. **摘掉后三个池一个租户都不剩**：`submitAsync()` 全仓库（含测试）**零调用方**，
>    grade/monitor 池的唯一候选就是上面那两个挂错的注解。也就是说该提交交付的是
>    三个空转 Bean，"隔离"实际一件都没发生——而 `ThreadPoolConfigTest` 只断言
>    core/max/queue 参数与"三个池不是同一实例"，这类断言在零租户下依然全绿。
> 3. **异步本身不成立**：交卷必须把结果同步返回给学生，包一层异步没有落点；
>    兜底扫描由 `@Scheduled` 驱动、行为采集被契约要求同步，都没有可腾挪的异步工作。
>
> 附带收益：移除了 `@EnableAsync`（同为该提交引入）。它会让 Spring 注册一个
> 默认 `TaskExecutor`，在没有真实异步工作时纯属多余的运行时面。
>
> 将来若真有批量重活（成绩导出、判分回填、跨机构批量通知），应带着真实调用方
> 和"能证明隔离起作用的并发测试"一起重建，而不是先占三个空池。

## Why

**当前问题**: 无线程池隔离，不同业务域共享同一线程池，存在资源抢占风险。

### 问题现状

**缺失能力**:
- 考试提交、评分、监控共享同一 Tomcat 线程池
- 大请求（批量导出）可能耗尽全部线程
- 无 CPU/内存资源限制
- 无法实现业务分级隔离

**影响分析**:
| 场景 | 当前表现 | 期望表现 |
|------|---------|----------|
| 批量导出 1000 份成绩 | 阻塞所有接口 | 独立线程池，不影响核心业务 |
| 评分任务高并发 | 占用数据库连接池 | 独立连接池配置 |
| 监控采集突发 | 抢占考试提交线程 | 低优先级队列 |

**背景**:
- Spring Boot 默认使用单一 ThreadPoolTaskExecutor
- 未针对不同业务特征定制线程池
- 面试价值：可讲"线程池隔离"实战经验

**期望状态**:
- 创建 3 个独立线程池：submit/grade/monitor
- 配置合理的 core/max/pool size
- 添加拒绝策略与告警

## What Changes

### 代码变更

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `ThreadPoolConfig.java` | NEW | 新建线程池配置类 |
| `ExamSubmitService.java` | MODIFIED | 使用 submit 线程池 |
| `ExamSweepService.java` | MODIFIED | 使用 grade 线程池 |
| `BehaviorEventCollectService.java` | MODIFIED | 使用 monitor 线程池 |
| `application.yml` | ADDED | 线程池参数配置 |

### 规范变更

- `spec/specs/performance/spec.md` - **ADDED**: 新建线程池隔离规范

## Impact

### 受影响的规范
- `spec/specs/performance/spec.md` - 新建线程池隔离规范

### 受影响的代码
- `com.exam.config.ThreadPoolConfig` (新建)
- `com.exam.service.ExamSubmitService`
- `com.exam.service.ExamSweepService`
- `com.exam.service.BehaviorEventCollectService`

### 用户影响
- **稳定性提升**: 大请求不阻塞核心业务
- **性能提升**: 合理分配线程资源

### API 变更
- 无外部 API 变更

### 需要迁移
- [x] 数据库迁移（无）
- [ ] 配置变更（application.yml）
- [x] 文档更新（规范 + 决策记录）
- [ ] 测试验证（压测对比）

## 时间线评估

**中等**: 约 1.5 天（W14）
- 线程池配置：0.5 天
- Service 层改造：0.5 天
- 压测验证：0.25 天
- 文档更新：0.25 天

## 风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 线程池配置不当 | 中 | 中 | 压测调优，监控告警 |
| 上下文传递丢失 | 低 | 中 | 使用 AsyncUtils 包装 |

## 验收标准

1. ✅ Prometheus 可见 3 个线程池指标（active, queue, completed）
2. ✅ 批量导出 1000 份成绩不阻塞考试提交接口
3. ✅ 线程池满时触发告警而非静默失败
4. ✅ 压测：100 并发下核心接口 P95 < 200ms
5. ✅ 全量测试通过，无回归问题

## 备选方案

**方案 B（不推荐）**: 仅使用 @Async 默认线程池
- 缺点：无法控制线程数，易 OOM

**方案 C（折中）**: 按 Controller 划分线程池
- 优点：粒度细
- 缺点：管理复杂度高

**推荐方案 A**: 按业务域划分（submit/grade/monitor）

## 参考资源

- [Spring ThreadPoolTaskExecutor](https://docs.spring.io/spring-framework/reference/core/testing/async-execution.html)
- [阿里巴巴 Java 开发手册 - 线程池规范](https://github.com/alibaba/checkstyle/blob/master/checkstyle-checks/src/main/resources/alibaba/checkstyle.xml)
