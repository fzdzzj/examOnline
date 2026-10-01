# 提案：交提交链路观测补齐（add-submit-observability，P2）

## Why

`docs/submit-loadtest-report.md` §7.3 与判据 G3：**改动后仍无法证明有效**——当前缺两块观测：

1. **Tomcat 线程 busy/max 指标未暴露**（Spring Boot 默认不开启 Tomcat MBean registry，`tomcat.threads.busy` / `tomcat.threads.config.max` 不在指标端点里），线程水位只能靠推理；
2. **`exam_submit_duration_seconds` 无直方图桶**，服务端 P99 只能靠 JMeter 客户端侧口径（含网络与排队噪声，属弱证据）。

`tune-submit-capacity`（容量调整）以本提案为前置——没有这两块，容量调整前后的服务端对比无从谈起。

**属后端独立立项**（改配置与指标注册），不在前端阶段 19–23 串行链内；本提案改 `application*.yml` 与指标代码是**明示授权**的例外（前端系列纪律针对的是前端阶段的子 agent，不约束本提案）。

## What Changes

1. 暴露 Tomcat 线程 busy/max 指标（开启 MBean registry 或等价 binder，`tomcat.threads.busy` / `tomcat.threads.config.max` 可从指标端点读取）；
2. `exam_submit_duration_seconds` 带直方图桶（percentiles-histogram 或 SLO 桶，桶边界覆盖 0.5s/1s/2s/5s 量级，P99 可从服务端直方图计算）；
3. 若既有 Grafana 面板（`spec/specs/observability/spec.md`「观测面板」Requirement）需补图，补最小可用的线程水位与提交时延面板；
4. 指标存在性与桶配置的测试（防配置回退后静默失效）；
5. 更新观测基线 spec-delta（待指导 agent 验收后合入）。

## Impact

### 受影响的规范
- `spec/specs/observability/spec.md` — ADDED：交卷时延直方图、Tomcat 线程水位可观测（与既有「指标导出」「指标基数有界」对齐：新增指标不得引入无界标签）。

### 受影响的文件
- `application.yml` 或 `application-*.yml`（指标配置）
- `src/main/**`（若桶配置需代码注册；指标命名沿用 `exam_submit_duration_seconds` 既有口径）
- `src/test/**`（存在性/配置测试）
- Grafana 面板 JSON（若仓库内有）

### 需要迁移
- [ ] 数据库迁移（无）

## 时间线评估

小–中：约 0.5–1 天（配置 + 测试为主）。

## 风险

- 桶配置对内存有代价（直方图每个系列多一组桶计数）——须与既有「指标基数有界」Requirement 对齐，标签维度不加业务键；
- Spring Boot 版本对 Tomcat 指标的暴露方式有版本差异——以现场 `/actuator/metrics` 实测为准，不凭记忆写配置；
- 配置类改动可能影响既有指标导出测试——收尾跑全量门禁，计数只增不减。
