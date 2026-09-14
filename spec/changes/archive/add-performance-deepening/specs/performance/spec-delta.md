# 规范差异：performance

本文件包含对 `spec/specs/performance/spec.md` 的规范变更（性能：缓存三防，全部为新增）。

## ADDED Requirements

### Requirement: 热点只读缓存
WHEN 系统读取试卷快照、考试快照等只读数据,
系统 SHALL 优先从缓存返回，命中则 SHALL 不访问数据库。

#### Scenario: 缓存命中
GIVEN 试卷快照已缓存
WHEN 学生读取该快照
THEN 系统从缓存返回
AND 不访问数据库

#### Scenario: 缓存未命中回源
GIVEN 快照未缓存
WHEN 学生读取
THEN 系统访问数据库取回
AND 回填缓存

### Requirement: 缓存穿透防护
WHEN 查询不存在的键,
系统 SHALL 缓存空结果以避免缓存穿透直击数据库。

#### Scenario: 空结果缓存
GIVEN 查询一个不存在的快照 ID
WHEN 系统处理
THEN 缓存空结果（短 TTL）
AND 后续同样查询命中空缓存而非直击 DB

### Requirement: 缓存击穿防护
WHEN 热点缓存失效瞬间,
系统 SHALL 以互斥锁保证仅一个线程回源重建，其余 SHALL 等待或返回旧值。

#### Scenario: 单线程重建
GIVEN 热点 key 已失效
AND 多线程同时请求该 key
WHEN 缓存击穿
THEN 仅一个线程回源
AND 其余线程等待或取旧值

### Requirement: 缓存雪崩防护
WHEN 大量缓存同时过期,
系统 SHALL 以基础 TTL 加上随机抖动避免集中失效。

#### Scenario: TTL 随机抖动
GIVEN 大量 key 设定相同基础 TTL
WHEN 缓存写入
THEN 每 key 的 TTL 叠加随机抖动
AND 避免同一时刻集体失效

### Requirement: 慢查询识别
WHEN SQL 执行耗时超过阈值,
系统 SHALL 记录慢 SQL 日志（含耗时与 SQL 及请求关联）。

#### Scenario: 慢 SQL 告警
GIVEN 一条 SQL 执行超过阈值（如 1000ms）
WHEN 系统检测
THEN 输出 WARN 日志
AND 日志含执行耗时、SQL 与关联 requestId