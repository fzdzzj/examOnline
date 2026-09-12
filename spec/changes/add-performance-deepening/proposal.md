# 提案：性能深化（阶段 8，缓存三防 + 读写分离 + 可观测性）

## Why

核心闭环（认证/题库/组卷/考试/答题交卷/判分/成绩/防作弊）已全部落地，但《面试版实施方案》v3 深水区 4「性能与高可用论证」承诺的三件事尚未兑现，是当前最大的技术债：

1. **缓存三防**：开考瞬间 5000 人同时拉卷，当前无任何缓存层，全部直击 DB —— 这是容量论证里"读走缓存"承诺的缺失；
2. **读写分离**：docker 已部署 MySQL 主从（master:3306 / slave:3307，GTID 复制），但应用代码仍单数据源只连主库，主从形同虚设 —— 深水区 4「主从读写分离 + 读己之写」未落地；
3. **可观测性**：`management` 仅暴露 health/metrics，无 Prometheus 导出、无自定义业务指标（交卷 QPS/队列深度）、无慢 SQL 识别 —— "出问题怎么定位"缺量化手段。

本阶段补齐这三块，让性能结论可测量、可证明，为 W6 压测提供观测底座。

**背景**：
- 缓存选型：**Spring Cache 抽象**（`@Cacheable` 等注解，底层 Redis），以后可换底层实现；
- 读写分离选型：**dynamic-datasource** 插件（`@DS` 注解 + 线程级路由），面试常用；
- 可观测选型：**Micrometer + Prometheus**（spring-boot-starter-actuator + micrometer-registry-prometheus）+ MyBatis 慢 SQL 拦截器；
- 已存在的良好基础：`RequestIdFilter`（日志 %X{requestId} 已打通）、`ExamSnapshot`（快照只写一次天然一致，缓存只需读路径）、`ExamSubmitSender`（confirm 可靠投递）、`ExamSweepService`（兜底扫描）。
- 依赖已完成的所有业务模块。

**当前状态**：业务闭环完整，但无缓存层、单数据源、无指标导出与慢 SQL 识别。

**期望状态**：开考拉卷命中缓存不压 DB；读走从库、写走主库、刚写完强制读主库（读己之写）；核心接口有 QPS/延迟指标、队列有积压指标、慢 SQL 有阈值告警日志。

## What Changes

- **缓存三防（Spring Cache + Redis）**：
  - 对试卷快照、考试快照、考试信息等热点只读数据加 `@Cacheable`；
  - 防穿透：空结果缓存（短 TTL）；
  - 防击穿：热点 key 互斥锁重建；
  - 防雪崩：基础 TTL + 随机抖动。
- **读写分离（dynamic-datasource）**：
  - 接入 master/slave 双数据源，写默认走 master，读路由到 slave；
  - `@DS("slave")` 注解 + 线程级读己之写路由（刚交卷/刚发布请求强制走 master）；
  - 保证 GTID 复制下的主从一致性（写后立读主库）。
- **可观测性（Micrometer + Prometheus）**：
  - 暴露 `/actuator/prometheus`（registry-prometheus）；
  - 自定义指标：交卷 QPS、交卷成功率、MQ 队列深度、防作弊事件计数；
  - MyBatis 慢 SQL 拦截器（可配阈值，超阈值打 WARN 日志）。

## Impact

### 受影响的规范
- `spec/specs/performance/spec.md` - 新建（`ADDED`）：缓存三防、读写分离读己之写、慢查询。
- `spec/specs/data-access/spec.md` - 新建（`ADDED`）：读写分离路由。
- `spec/specs/observability/spec.md` - 新建（`ADDED`）：指标导出、自定义指标、慢 SQL。

### 受影响的代码
- `config`（CacheConfig/多数据源配置）、`common`（慢 SQL 拦截器）、业务 Service 加 `@Cacheable`/`@DS`、指标打点

### 用户影响
- 无功能变化；性能与可观测性提升。

### API 变更
- 无业务端点变化；新增 `/actuator/prometheus` 监控端点。

### 需要迁移
- [ ] 数据库迁移（复用已有表，无新表）
- [x] 配置变更（多数据源、缓存、指标）
- [ ] API 版本提升
- [x] 文档更新（本提案 + 规范 + 面试弹药深水区 4）

## 时间线评估

中：约 5-6 天（W9-W10，对应执行计划「W9-W10 性能 + 可观测」）。

## 风险

- **缓存与 DB 不一致** —— 缓解：只缓存只读快照（快照发布后不可变），无更新冲突；有更新路径（考试信息）设短 TTL + 显式失效。
- **读己之写遗漏导致查不到刚提交数据** —— 缓解：ThreadLocal 标记写后 N 秒内路由主库，关键查询（答卷详情）强制读主。
- **主从延迟读到旧数据** —— 缓解：线上读写分离只用于非强一致读；强一致读（答卷/成绩）走主库。
- **引入依赖影响构建** —— 缓解：dynamic-datasource / registry-prometheus 均为成熟 starter，锁定版本。