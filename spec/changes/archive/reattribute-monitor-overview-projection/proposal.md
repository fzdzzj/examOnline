# 提案：监考总览答卷取数再归因——真引擎下的列投影与至多一条窄读

> 状态：执行中（先冻结判据、再测量、仅 GO 才实施）。静态核对基于 `bea34af`（分支 `feature/update-monitor-overview-submission-projection`，2026-09-29）。
> 注记：PREREGISTRATION 冻结入口 bea34af、测量与裁决 rev bede6c8，两者 src 零差异（判据 git diff --name-only 为空）。
> 本提案不是已测得的缺陷：它是**前序 `update-monitor-overview-submission-projection` NO-GO 之后的第二次归因**，只改一个站点（s6）、一次只改一类（答卷取数形态）。

## Why

`MonitorService.overview` 当前对整场答卷 `submissionMapper.selectList(eq(exam_id))` 全列取数，逐行载入 LONGTEXT `paper_json`/`answers`；题目总数只取第一份非空快照的 `questions` 数组长度，其余快照与全部 `answers` 不参与总览逐人展示。

前序变更按当时证据 NO-GO（2026-09-26）：「逐人 Redis 草稿 GET 是占比最高的可控因素」（120 次/请求，passMedian 30.9/33.4/42.2ms ≈ e2e mean 30.4/31.9/33.0ms），答卷长字段增量仅 1.17–2.52%（rep）或噪声（small），且引擎是 H2 内存库 + MockMvc 同进程、结构上看不见网络字节成本。该 NO-GO 的两个前提**均已改变**：

1. **逐人 Redis 草稿 GET 已被消除**：`update-monitor-overview-draft-batch-read`（GO，已合入）把 `MonitorService.overview` 的草稿读取改为 `draftService.getBatch` 一次 MGET（现码 `MonitorService.java` 第 94 行，K5 现场复核）；前序判据中的主导因素不再存在，长字段读取的相对份量需要重裁。
2. **本次改真 MySQL 引擎**：字节与时间为一次性 `mysql:8.0` 本地容器内的三臂实测（OLD / PROJ / OLDrep），网络与存储代价可见；量级锚取自同表同谓词的同批已入库读数：站点 s2（`exam_submissions`、`exam_id = ?`、`paper_json`/`answers` 两长列）在 n=3000 时 **67,941,000 → 27,000 字节（2516.33×）**、n=1000 时 22,647,000 → 9,000、n=200 时 4,529,200 → 1,800（`spec/changes/archive/project-scalar-only-submission-reads/evidence/`，机械复算）。

## What Changes

1. **先冻结判据再测量**：`evidence/PREREGISTRATION.md` 在任何捕获 IT 或容器脚本运行前写定并记 sha256；冻结站点定义、造数形状与分布、臂与轮数、S1–S4 算子、措辞纪律、两条新证据原文，以及「第一份非空快照」在未定义行序下的残余语义边界。
2. **真引擎三臂测量**：一次性 `mysql:8.0` 容器（tmpfs、127.0.0.1 高位端口、独立库名、整库执行 `schema.sql`、记镜像 digest 与 `SHOW CREATE TABLE`/`SHOW INDEX`、用毕销毁留零匹配证据）；形状 n ∈ {200,1000,3000}；每形状三臂位 OLD（全列）/ PROJ（投影 + 与主语句同计时窗口的至多一条窄读）/ OLDrep（OLD 等价副本）× 2 预热 + 5 计时轮、逐轮左轮转；应传字节用机械解析 SELECT 列表的确定性 LENGTH 求和（非计时）。
3. **仅 GO 才实施、且只动读形态**：把总览主语句投影到机械枚举出的标量列（`student_id, status`），题目总数改为至多一条独立的窄单行读（只取 `paper_json`），带「第一行非空快照」的等价边界说明；在线/离线降级、草稿批读、进度近似口径、异常高亮与排序、教师归属校验、Redis 键与 TTL、索引、schema、缓存、JVM/线程池/连接池、MQ、前端与其余站点一律不动；补护栏测试先红后绿。
4. **收口**：`spec/README.md` 归档行写明与前序 NO-GO 的关系（GO：两条新证据、被取代的结论、验收边界；NO-GO：「本方向第二次不成立，封盘」并按未采用草案归档）；仅 GO 且实施验收成立才把 delta 合入 anti-cheat 基线。

## Impact

- **规范**：仅 GO 且验收通过时，`spec/specs/anti-cheat/spec.md` 的「监考大屏」Requirement 整段替换（本目录 `specs/anti-cheat/spec-delta.md` 为候选；NO-GO 则原样留作未采用草案）。`spec/specs/performance/spec.md` 的先归因门禁已存在，不重复新增。
- **代码**：仅 GO 后改 `MonitorService.overview` 的答卷取数形态（如需独立窄读则相应 Mapper 复用/新增至多一条方法）及针对性测试/测量工具；`MonitorDraftBatchReadTest`、`AntiCheatIntegrationTest` 作为既有回归基线。NO-GO 不改 `src/main`。
- **用户/API**：不新增端点、不改响应契约或用户可见口径。
- **数据与部署**：不改 `schema.sql`、迁移、索引、JVM、线程池或 Redis 写入协议；不写共享 dev、不 push、不建 PR。

## 验收与停止条件

- 判据与措辞在执行前冻结（见 `evidence/PREREGISTRATION.md`）；裁决由机械复算脚本按冻结算子输出，不人脑算；不得混用 S2/S3 失败措辞、不得以中位数替代「每一轮」、不得挑轮、不得下调倍数。
- S1（语义等价）任一形状不成立即停手保留现场并回报；S2/S3/S4 按冻结措辞如实登记，不实施未过判据的部分。
- 门禁为仓库根 `mvnw.cmd clean test`；记录四计数、BUILD 与退出码及 revision；偶发红按既有协议原样重跑一次并如实记录两次结果，重跑仍红即停。
- 测量环境（一次性本地容器、单机、H2 测试上下文做语义层）的结论不外推生产 MySQL/Tomcat，不构成交卷或监考 P99 结论。
