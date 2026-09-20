# 规范差异：observability（分布式追踪）

本文件包含对 `spec/specs/observability/spec.md` 的规范变更。

## MODIFIED Requirements

### Requirement: 日志追踪 ID

**原需求文本**:
```markdown
WHEN 记录业务日志，
系统 SHALL 包含请求 ID 用于日志关联。
```

**新需求文本**:
```markdown
WHEN 记录业务日志，
系统 SHALL:
1. 使用 Trace ID（OpenTelemetry Standard）而非自定义 Request ID
2. Trace ID 自动贯穿整个请求链路（Controller → Service → Mapper）
3. 日志格式包含 traceId、spanId、parentId
```

#### Scenario: 日志格式增强
GIVEN 一个请求处理过程
WHEN 记录日志
THEN MDC 自动注入:
  - traceId (16 位 Hex)
  - spanId (8 位 Hex)
  - parentId (可选)
AND 日志输出:
  `[2026-09-19 10:30:00] [trace=abc123def456] [span=789] Exam submitted successfully`

---

## ADDED Requirements

### Requirement: 分布式追踪

```markdown
### Requirement: OpenTelemetry 集成
WHEN 应用启动，
系统 SHALL 配置 OpenTelemetry TracerProvider，自动采集以下 Span:
- HTTP 请求（Controller 层）
- 方法调用（Service 层）
- SQL 查询（Mapper 层）

#### Scenario: 请求链路追踪
GIVEN 用户发起考试查询请求
WHEN 请求完成
THEN Jaeger UI 可见完整链路:
  http-get-exams (50ms)
    └── ExamService.getExams() (45ms)
        └── ExamMapper.selectList() (30ms)
            └── MySQL Query (25ms)

#### Scenario: Span 标签规范
GIVEN 每个 Span
WHEN 创建 Span
THEN 必须包含标签:
  - db.statement (SQL 语句，脱敏)
  - http.method, http.url (HTTP 信息)
  - component (模块名：controller/service/mapper)
  - status (SUCCESS/ERROR)
```

---

### Requirement: 采样策略

```markdown
### Requirement: 自适应采样
WHEN 配置追踪采样率，
系统 SHALL:
- 开发环境：100% 采样
- 测试环境：50% 采样
- 生产环境：10% 采样

#### Scenario: 生产采样配置
GIVEN production 环境
WHEN 应用启动
THEN otel.traces.sampler=parentbased_traceidratio:0.1
AND 日志输出"OpenTelemetry tracing enabled with 10% sampling"
```

---

### Requirement: 错误传播

```markdown
### Requirement: 异常 Span 标记
WHEN 发生异常，
系统 SHALL 在对应 Span 标记 error=true 并记录堆栈。

#### Scenario: 异常追踪
GIVEN Service 层抛出 BusinessException
WHEN 捕获异常
THEN 当前 Span 设置:
  - exception.name = "BusinessException"
  - exception.message = "考试已发布"
  - exception.stacktrace = true
AND Jaeger UI 显示红色错误标记
AND 父 Span 自动继承错误状态
```

---

### Requirement: 链路拓扑

```markdown
### Requirement: 服务依赖图
WHEN 多个微服务协作时，
系统 SHALL 自动生成服务依赖拓扑图。

#### Scenario: 跨服务调用
GIVEN 考试服务调用评分服务
WHEN 请求完成
THEN Jaeger Service Graph 展示:
  exam-service → score-service
AND 每个服务的 P95 耗时
AND 错误率统计
```

---

## REMOVED Requirements

无移除项。

---

## 规范变更总结

| 类型 | 需求名称 | 变更说明 |
|------|---------|----------|
| MODIFIED | 日志追踪 ID | 改用 OpenTelemetry Trace ID |
| ADDED | OpenTelemetry 集成 | 自动采集 Controller/Service/Mapper Span |
| ADDED | 采样策略 | 按环境配置不同采样率 |
| ADDED | 错误传播 | Span 标记异常并记录堆栈 |
| ADDED | 链路拓扑 | 服务依赖图可视化 |

---

## 验证清单

- [ ] `docker-compose up jaeger` 启动成功
- [ ] Jaeger UI 可见完整请求链路
- [ ] Trace ID 存在于请求头 X-Trace-ID
- [ ] 日志中可见 traceId、spanId
- [ ] Span 分解：Controller → Service → Mapper
- [ ] 异常 Span 标记 error=true
- [ ] 全量测试通过，无回归问题

---

## 参考示例

### ✅ 正确示例：OpenTelemetry 配置

```java
@Configuration
public class TracerConfig {
    
    @Bean
    public TracerProvider tracerProvider() {
        returnSdkBuilder.setTracerProvider(
            SdkTracerProvider.builder()
                .setSampler(Samplers.traceIdRatioBased(0.1)) // 10% 采样
                .addSpanProcessor(BatchSpanProcessor.builder(
                    JaegerGrpcSpanExporter.builder()
                        .setEndpoint("http://jaeger:14250")
                        .build()
                ).build())
                .build()
        );
    }
}
```

### ✅ 正确示例：MDC 注入 Trace ID

```java
@Component
public class TraceIdInterceptor implements HandlerInterceptor {
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String traceId = request.getHeader("X-Trace-ID");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put("traceId", traceId);
        return true;
    }
}
```
