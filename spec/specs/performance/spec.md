# performance 规范

> 能力域：性能（阶段 8，W9-W10）。
> 来源：`spec/changes/archive/add-performance-deepening` 合入（热点只读缓存、缓存穿透/击穿/雪崩三防）。
> 归并说明：原提案 performance spec-delta 中的「慢查询识别」与 observability spec-delta 的「慢 SQL 识别」是同一需求，**不重复登记**——统一归入 `spec/specs/observability/spec.md`（日志与请求关联属可观测性能力域），本文件不再重复。

## Requirements

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

---

### Requirement: 缓存穿透防护

WHEN 查询不存在的键,

系统 SHALL 缓存空结果以避免缓存穿透直击数据库。

#### Scenario: 空结果缓存

GIVEN 查询一个不存在的快照 ID

WHEN 系统处理

THEN 缓存空结果（短 TTL）

AND 后续同样查询命中空缓存而非直击 DB

---

### Requirement: 缓存击穿防护

WHEN 热点缓存失效瞬间,

系统 SHALL 以互斥锁保证仅一个线程回源重建，其余 SHALL 等待或返回旧值。

#### Scenario: 单线程重建

GIVEN 热点 key 已失效

AND 多线程同时请求该 key

WHEN 缓存击穿

THEN 仅一个线程回源

AND 其余线程等待或取旧值

---

### Requirement: 缓存雪崩防护

WHEN 大量缓存同时过期,

系统 SHALL 以基础 TTL 加上随机抖动避免集中失效。

#### Scenario: TTL 随机抖动

GIVEN 大量 key 设定相同基础 TTL

WHEN 缓存写入

THEN 每 key 的 TTL 叠加随机抖动

AND 避免同一时刻集体失效

---

> 撤回说明（2026-09-20，`add-thread-pool-isolation`）：本能力域**未**从该提案合入任何需求——
> 其实现经核实为纯空转并已整体删除（`46d7004` 建池 + `@EnableAsync`，`df5f311` 全部移除）。
> 三点事实供后来者避坑：
> ① 兜底扫描与行为采集被 `@Async` 挂在**有返回值且返回值被调用方使用**的方法上，运行期直接抛
> 非法返回类型异常，行为上报接口全量 500；且扫描方法由同 bean 自调用，本就绕过代理，注解永不可能生效。
> ② 摘掉误用后三个池一个租户不剩（异步交卷入口零调用方），"隔离"实际从未发生。
> ③ 断言池参数与"三池互不相同实例"的测试在零租户下依旧全绿——**代理型注解必须验证其真生效**，
> 该规则已登记到 `spec/specs/data-consistency/spec.md`「声明式增强必须真的生效」。
