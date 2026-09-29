# anti-cheat 规范

> 能力域：防作弊（阶段 7，W8）。
> 来源：`spec/changes/add-anti-cheat` 合入（行为事件采集、事件严重度分级、切屏处理策略、行为日志时间线、监考大屏）。

## Requirements

### Requirement: 行为事件采集

WHEN 学生考试中发生可疑行为,

系统 SHALL 以统一事件模型采集切屏、失焦、刷新等事件，新增事件类型 SHALL 不改变采集核心。

#### Scenario: 采集切屏事件

GIVEN 学生考试中切出页面

WHEN 系统检测到切屏

THEN 采集切屏事件落库

AND 记录事件类型与时间

---

#### Scenario: 新增事件不动核心

GIVEN 需要新增一种行为事件类型

WHEN 开发者添加事件实现

THEN 无需修改采集核心逻辑

---

### Requirement: 事件严重度分级

WHEN 行为事件被采集,

系统 SHALL 分配严重度（低/中/高），切屏次数达到阈值 SHALL 提升严重度。

#### Scenario: 切屏超过阈值

GIVEN 学生切屏次数达到配置阈值

WHEN 系统判定

THEN 事件严重度提升

AND 记录升级标记

---

### Requirement: 切屏处理策略

WHEN 学生切屏,

系统 SHALL 仅警告并记录，不强制交卷。

#### Scenario: 切屏警告不交卷

GIVEN 学生考试中切屏

WHEN 系统检测到切屏

THEN 弹出警告提示

AND 记录行为日志

AND 不强制交卷

---

### Requirement: 行为日志时间线

WHEN 教师查看行为日志,

系统 SHALL 提供按考试、学生、事件类型、严重度筛选的时间线，且 SHALL 仅教师与管理员可见。

#### Scenario: 教师查看时间线

GIVEN 教师进入某考试行为日志

WHEN 教师按学生筛选

THEN 系统按时间顺序展示该学生行为轨迹

---

#### Scenario: 学生不可见

GIVEN 学生尝试访问行为日志

WHEN 学生发起查询

THEN 系统返回 403 拒绝

---

### Requirement: 监考大屏

WHEN 教师进入监考大屏,

系统 SHALL 展示在线/离线/已交卷人数、答题进度，并 SHALL 高亮异常行为。WHEN 总览需要读取进行中学生的草稿进度时，系统 SHALL 按本场进行中学生批量读取草稿，而 SHALL NOT 在成功路径按学生逐个发起 Redis GET；系统 SHALL 保持缺失/损坏草稿、Redis 失败、权限校验、进度近似口径与其他总览字段的既有语义。无进行中学生时 SHALL NOT 为草稿发起批量读。WHEN 总览读取整场答卷以生成逐人状态时，系统 SHALL 只读取逐人状态所需的标量列，而 SHALL NOT 为每份答卷载入 `paper_json` 与 `answers`；题目总数所需的个人快照 SHALL 经至多一条只取所需列的窄读取得，而 SHALL NOT 按学生回查；系统 SHALL 保持权限校验、进度近似口径、异常高亮与排序、在线/离线判定及响应形状的既有语义。

#### Scenario: 实时状态展示

GIVEN 考试进行中有学生在线/离线/已交卷

WHEN 教师打开监考大屏

THEN 系统展示各类人数与进度

AND 异常行为学生被高亮

---

#### Scenario: 查看异常详情

GIVEN 监考大屏高亮某学生异常

WHEN 教师点击该学生

THEN 系统展示该学生行为时间线

---

#### Scenario: 进行中草稿批读且进度口径不变

GIVEN 本场有多名进行中学生，另有已交卷学生

WHEN 有权限的教师轮询监考总览

THEN 进行中学生的草稿仅经批量读取，不逐人 Redis GET

AND 批读值与学生正确关联，已交卷学生不读取草稿且进度仍按现有题数分母计算（有题为 100%，题数为 0 时仍为 0）

AND 在线/离线/已交卷计数、异常高亮与排序、教师归属校验及响应形状不变

---

#### Scenario: 草稿缺失、损坏与读取失败

GIVEN 部分进行中学生缺少草稿或草稿为空白/损坏/answers 非对象

WHEN 有权限的教师轮询监考总览

THEN 这些学生按旧的无草稿或零题进度口径展示，其他学生进度不受影响

AND 损坏值按学生定位并遵循单份 get 的日志语义

AND Redis 批读本身失败或返回无法可靠对齐的结果时，不伪装为所有学生都未作答

---

#### Scenario: 无进行中学生

GIVEN 本场为空或所有答卷均已提交

WHEN 有权限的教师轮询监考总览

THEN 不为草稿发起 Redis 批量读取

AND 原有统计和已交卷进度语义不变

---

#### Scenario: 逐人状态取数不载入长字段且题数至多一次窄读

GIVEN 本场有多份答卷且存在非空个人快照

WHEN 有权限的教师轮询监考总览

THEN 生成逐人状态的主查询只取标量列，不取 `paper_json` 与 `answers`

AND 题目总数经至多一条只取所需列的窄读取得，不按学生回查

AND 无进行中学生或无非空快照时，题数与进度的既有降级语义不变

AND 在线/离线/已交卷计数、草稿进度口径、异常高亮与排序、教师归属校验及响应形状不变

---

> 评估结论注记（2026-09-26，`update-monitor-overview-submission-projection` 归档）：本基线
> **未合入**该提案的任何 Requirement——其 spec-delta（总览逐人答卷投影 + 至多一次窄快照读题数）
> 按**未采用草案**随目录归档。要点：在专用隔离环境（H2 内存库 + Redis db15，非共享 dev、不启
> Docker）对旧实现同负载三轮实测，**占比最高的可控因素是逐人 Redis 草稿 GET**（120 次/请求，
> passMedian 30.9/33.4/42.2ms，≈整条请求延迟 `e2e` 并发1 mean 30.4/31.9/33.0ms），答卷长字段
> （`paper_json`+`answers`）读取增量仅 `longFieldDelta` 0.355/0.592/0.829ms＝1.17%/1.86%/2.52%，
> 且增量自身轮间波动与绝对值同量级（small 变体甚至一轮为负）＝噪声；投影+一次窄读约 1.5–1.7ms，
> 相对该口径无净收益。按门禁判据（长字段非主导、预期改善不可分辨）裁定 **NO-GO 并停止实现**，
> `src/main` 零改动。后续独立方向＝草稿批读（同轮一次 multiGet 取回 120 键 p50≈0.45ms 对照），
> 须另立变更、按 performance 基线「先归因、一次只改一类」走。数字与依据见
> `spec/changes/archive/update-monitor-overview-submission-projection/tasks.json` 阶段 2 evidence；
> 隔离 H2+MockMvc 结果不能外推生产 MySQL/Tomcat，不构成监考 P99 结论。

> 合入注记（2026-09-26，`update-monitor-overview-draft-batch-read` 归档，GO）：上条指出的独立
> 方向已另立变更执行——监考大屏「进行中草稿批读」整段 MODIFIED Requirement 合入本基线（新增
> 「进行中草稿批读且进度口径不变」「草稿缺失、损坏与读取失败」「无进行中学生」三个 Scenario）。
> 归因：逐人 Redis GET 为可控主导因素（旧实现每请求 121 次 GET，120 次用于进行中草稿；组件臂
> `draftGetEach` passMedian 91.4–151.5ms）。实现：`ExamDraftService` 抽出私有 `parseDraft` 供
> `get` 与新增 `getBatch` 共用同一解析口径，`MonitorService.overview` 一次 MGET 取回进行中草稿；
> 无进行中学生不触达 Redis，MGET 结果无法与请求学生对齐或 Redis 读取异常一律抛错，不降级成
> 全员零进度；保存/恢复/交卷兜底/超时扫尾仍走单份 `get`，在线 MGET、异常聚合 SQL、排序、schema、
> API、前端、JVM 均未改。实施后同数据/同工具/同预热三轮复测：`e2e` 并发1 mean 由 99.3–125.5ms
> 降到 22.1–27.0ms（逐轮 4.29–4.65×）、吞吐 7.9–10.0→36.4–44.3 rps（≈4.4×）；并发8 吞吐
> 56.3–60.7→168.0–193.7 rps（≈3.05×）；每请求 Redis 命令由 121 get 降为 1 get + 2 mget；响应体
> 27440–27480B 与 GC 后堆（67.00–67.27MB）、峰值堆（175.2–183.8MB）无回归；批取与逐人 get 等价
> 校验三轮 mismatch 全 0。组件臂与端到端不是同一计时窗口，本基线不写两者相除的精确占比。仓库根
> `mvnw.cmd clean test` @ `e6b9a17` → 314/0/0/1 BUILD SUCCESS 退出码 0。**边界**：隔离 H2 + MockMvc
> 同进程 + 专用 Redis 容器端口映射（每次 Redis 往返约 0.68–1.16ms，放大了逐次往返代价），不能外推
> 生产 MySQL/Tomcat，不构成监考 P99 结论。**证据返修（2026-09-26）**：首轮逐轮 JSON 被阶段4 全量
> 门禁的 `clean` 删除（仅数值留存），故在**同隔离环境类、同工具 revision、同数据/预热/并发**下重跑
> 并与归档对齐；本注记与 tasks.json 的数值取自归档原始 JSON（首轮数值亦如实留存作对照）。逐轮原始
> JSON（含 sha256 清单）见 `spec/changes/archive/update-monitor-overview-draft-batch-read/evidence/`，
> 命令与判读见同目录 `tasks.json`。

> 合入注记（2026-09-29，`reattribute-monitor-overview-projection` 归档，GO）：上方 2026-09-26 的
> **评估结论注记（NO-GO）已被本次测量取代**——它绑定的两个前提都已消失：①前序主因「逐人 Redis
> 草稿 GET（120 次/请求）」已由 `update-monitor-overview-draft-batch-read` 消除（现码
> `draftService.getBatch` 一次 MGET）；②前序引擎是 H2 内存库，**结构上看不见网络字节成本**
> （答卷长字段 20KB 级在进程内取数下不可能显现）。本次另立变更按 performance 基线「先归因、
> 一次只改一类」重做，判据在任何测量运行前冻结（`evidence/PREREGISTRATION.md`，sha256
> `1481d739c8e830969c139e2d124ebf17219231503a076ab4ff5f6ef097e59fcb`），在**一次性 mysql:8.0
> 容器**（tmpfs、127.0.0.1:13319、独立库 `monitor_projection_measure`、整库 `schema.sql` 退出码 0、
> 镜像 digest `mysql@sha256:7dcddc01…ae2b`、容器 `67add6f16527`；用毕 `docker rm -f` 并留
> `docker ps -a` 零匹配证据）对三形状 n=200/1000/3000 × 三臂（OLD 现状全列 / PROJ 投影+至多一条
> 窄读 / OLDrep 等价副本）× 2 预热 + 5 计时轮、逐轮左轮转、不挑轮。机械账目（容器侧 `SUM(LENGTH)`，
> 确定性、非计时）：**S2 字节比 190.46 / 702.34 / 1272.22**（阈值 5.0）；**S3** 端到端 PROJ 逐轮
> wall-clock 全落 `max(OLD ∪ OLDrep 全部成功轮)` 噪声带内、S2∧S3 均成立 ⇒「额外往返抵消字段节省」
> **不成立**（窄读计入同一计时窗口）；**S4** 主语句两臂均 1 条/n 行、窄读 PROJ 1 条/1 行；**S1**
> 逐字段对拍等价（时钟字段只比有无/正负）；三形状 M1–M3 全成立 ⇒ 裁决 **GO**。量级锚＝s2 在同表
> 同谓词同两列上的已入库读数 `67941000 → 27000` 字节。实现（只动读形态这一类）：
> `MonitorService.overview` 主语句投影 `student_id, status`；题数分母改由
> `ExamSubmissionMapper.selectFirstPaperJson`（`paper_json` 非空且 `LIMIT 1`）**至多一条**窄单行读
> 取得，无答卷/无非空快照时按现码口径 0 题且不多发查询；在线/离线降级、进度近似口径、异常高亮
> 与排序、教师归属校验、草稿批读、Redis 键与 TTL、索引、`schema.sql`、缓存、JVM/线程池/连接池、
> MQ、前端及其余站点（s3/s4/s5）零改动。护栏 `MonitorOverviewProjectionGuardTest` 先红后绿
> （旧实现 `expected: <0> but was: <3>`），常驻门禁。仓库根 `mvnw.cmd clean test` @ 实施提交
> `69617a9` → **327/0/0/1 BUILD SUCCESS 退出码 0**（325→327 = +2 护栏用例，Skipped 1 不变；测量 IT
> 以 `IT` 结尾不进 Surefire）。**过程披露**：门禁两次为确定性红（共享 H2 下新护栏夹具 4 条
> `published=1` 考试挤占「学生考试列表第 1 页 50 条」窗口，令 `ExamTakingIntegrationTest.examListGroups`
> 的目标考试落第 2 页），最小修正夹具 `published` 位后转绿；两次红、归因与修正依据见
> `evidence/gate-runs.md`，机制登记为 spec/README 遗留 #20。**边界**：一次性本地容器、单机、
> 空并发、MockMvc 测试上下文；不外推生产 MySQL/Tomcat，不构成交卷或监考 P99 结论。逐轮原始 JSON、
> 裁决书、容器日志与 sha256 清单见 `spec/changes/archive/reattribute-monitor-overview-projection/evidence/`。
