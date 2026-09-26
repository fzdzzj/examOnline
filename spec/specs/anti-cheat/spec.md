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

系统 SHALL 展示在线/离线/已交卷人数、答题进度，并 SHALL 高亮异常行为。WHEN 总览需要读取进行中学生的草稿进度时，系统 SHALL 按本场进行中学生批量读取草稿，而 SHALL NOT 在成功路径按学生逐个发起 Redis GET；系统 SHALL 保持缺失/损坏草稿、Redis 失败、权限校验、进度近似口径与其他总览字段的既有语义。无进行中学生时 SHALL NOT 为草稿发起批量读。

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
