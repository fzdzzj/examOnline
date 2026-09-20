# 提案：分布式追踪与链路诊断（阶段 9，可观测性增强）

## Why

**当前问题**: 无分布式追踪，无法定位跨服务调用链路的性能瓶颈。

### 问题现状

**缺失能力**:
- 无 Trace ID 贯穿整个请求链路
- 无 Span 层级耗时分析
- 无错误传播路径追踪
- 无法可视化请求流向

**影响分析**:
| 场景 | 当前表现 | 期望表现 |
|------|---------|----------|
| 慢查询定位 | grep 日志手动关联 | Jaeger 可视化链路 |
| 错误根因分析 | 多日志拼接 | Trace 聚合展示 |
| 性能瓶颈识别 | 凭经验猜测 | Span 耗时分布图 |

**背景**:
- 项目已有 Prometheus 指标监控
- 但缺少"请求级"的可观测性
- 面试价值：可讲"OpenTelemetry + Jaeger"实战经验

**期望状态**:
- 集成 OpenTelemetry + Jaeger
- 自动生成请求链路拓扑
- P95 耗时分解到每个 Span

## What Changes

### 代码变更

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `pom.xml` | ADDED | 添加 OpenTelemetry 依赖 |
| `ApplicationConfig.java` | ADDED | 配置 TracerProvider |
| `ExamController.java` | MODIFIED | 自动注入 Trace ID |
| `application.yml` | ADDED | Jaeger 采样率配置 |

### 规范变更

- `spec/specs/observability/spec.md` - **MODIFIED**: 新增分布式追踪要求

## Impact

### 受影响的规范
- `spec/specs/observability/spec.md` - 修改追踪粒度

### 受影响的代码
- `com.exam.ExamOnlineApplication` (新建配置)
- 所有 Controller / Service / Mapper（自动增强）

### 用户影响
- **可观测性提升**: 可视化请求链路
- **故障排查**: 快速定位慢调用

### API 变更
- 响应头新增 `X-Trace-ID`

### 需要迁移
- [ ] 部署 Jaeger 服务（Docker）
- [x] 配置变更（application.yml）
- [x] 文档更新（规范 + 决策记录）
- [ ] 测试验证（链路模拟）

## 时间线评估

**中等**: 约 2 天（W13-W14）
- OpenTelemetry 集成：1 天
- Jaeger 部署：0.5 天
- 测试验证：0.25 天
- 文档更新：0.25 天

## 风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 追踪增加延迟 | 低 | 低 | 采样率 10%，异步上报 |
| Jaeger 资源占用 | 低 | 低 | 限制存储 24 小时 |

## 验收标准

1. ✅ Jaeger UI 可见完整请求链路
2. ✅ Trace ID 存在于请求头与日志中
3. ✅ Span 分解：Controller → Service → Mapper
4. ✅ P95 耗时分解到每个 Span
5. ✅ 全量测试通过，无回归问题

## 备选方案

**方案 B（不推荐）**: 仅使用 SkyWalking
- 优点：APM 功能完整
- 缺点：学习成本高，侵入性强

**方案 C（折中）**: 简单 Trace ID 传递
- 优点：轻量
- 缺点：无 Span 层级分析

**推荐方案 A**: OpenTelemetry + Jaeger（云原生标准）

## 参考资源

- [OpenTelemetry Java](https://opentelemetry.io/docs/instrumentation/java/)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/getting-started/)
