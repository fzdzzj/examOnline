# 提案：线程池隔离与资源管控（阶段 9，性能优化）

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
