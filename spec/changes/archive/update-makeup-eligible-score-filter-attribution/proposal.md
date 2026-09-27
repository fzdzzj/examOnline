# 提案：补考候选名单低分取数先归因，再裁决是否下推

> 状态：**已归档（NO-GO）**（2026-09-27）。静态核对基于 `31a5ff8`（2026-09-27）；这不是已测得的性能缺陷，也不是对真实调用频度的结论。收口：按本提案第 3 条预登记判据（算子化为 `evidence/PREREGISTRATION.md`，写定于正式测量前）测量后裁决 **NO-GO，未实施**——三次独立 JVM 调用（同夹具/预热/口径，隔离 H2 进程内、不启 Docker、不写共享 dev）各 6 形状 × 4 轮：D1 不成立（`s200-p10-a2k` 有 5/12 样本中「答卷取回+Java 过滤」不是最大相位，200 人规模下低分取回非占比最高的可控因素）、D2 不成立（`s200` 下推比值最小 1.317 < 判定带 1.797＝波动吞没收益；`a20k` 最小 2.399 < 判定带 3.518＝顺序漂移上界使判定带不可达），D3/D6 成立、D5 不成立；命中停止条件「波动吞没收益」与「低分筛选非主导」。敏感性四读法中三种仍 NO-GO（唯一翻绿的读法用中位数替换「每一轮」，不再检验逐轮稳定性，按「不得为追求 GO 放宽判据」不采用）。`src/main` 零改动（与基线 `31a5ff8` 零 diff，唯一提交 `f88655a` 仅测试侧测量工具），拟实施条款（spec-delta）按**未采用草案**随本目录归档、**未合入** absence-makeup 基线（基线仅追加评估结论注记）；`spec/specs/performance/spec.md` 的「先归因、一次只改一类」门禁不改。仓库根 `mvnw.cmd clean test` @ `f88655a` → 314/0/0/1 BUILD SUCCESS 退出码 0。逐轮原始 JSON 与 sha256 见 `evidence/`（`measurement-rounds.md` / `evidence-sha256.txt`）。

## Why

`MakeupService.listEligibleStudents` 在 `passLine != null` 时，以 `exam_id` 和 `total_score IS NOT NULL` 取回本场 `GradingSubmission` 全列（包括 `answers`），再在 Java 用 `totalScore.compareTo(passLine) < 0` 过滤。缺考行先进入 `LinkedHashMap`，低分行后覆盖相同学生的原因，最后批量装姓名。静态上存在少取低分以外行的机会，但尚无该端点的运行时请求频度、考试人数/分数分布、`answers` 字节或请求内耗时证据。前端调用点、少量 dev 演示记录和其他路径的性能数据都不能充当此处归因。

最强反例是此入口很少调用、考试规模很小，或全列中的长字段和数据库/姓名查询才是主要成本；单纯下推分数谓词未必改变可感知延迟。不能把“有索引”写成范围谓词已被索引覆盖：目前 `total_score` 无索引。

## What Changes

1. 先固定旧行为 oracle 与隔离负载。覆盖 `passLine=null`、负值/边界/相等与不同 BigDecimal scale、未批总分为 null、缺考与低分集合/同 ID 原因覆盖、姓名缺失/软删、空候选短路、权限/归属；不制造现状不存在的排序、考试状态或成绩发布限制。只使用仓库正式 `schema.sql`，不以 `@Sql` 自建表；工具与数据局限在隔离 H2/test profile，不连接共享 dev、不启 Docker。
2. 在多个固定人数、低分比例与 `answers` 大小的形状下预热、多轮测量旧路径：请求级墙钟/吞吐/分配、各 SQL 的耗时与行数、答卷所选列及其估计大小、Java 过滤、姓名装载。以同数据的 test-only 受控变体分别估算**仅分数谓词下推**与**仅窄列投影**；核对结果语义和 SQL 交互。两种变体不得混成“下推收益”，H2 字段估计不是 MySQL 网络传输字节，跨调用分位数不得相减或充作请求内占比。频度没有授权的运行时来源则明确未知，不采集共享 dev/未授权日志。
3. 预先裁决：仅当多轮同负载证据表明“低分以外行的取回及 Java 过滤”是本入口占比最高的**可控**因素、下推单臂请求级收益稳定超过轮间/探针波动、适用负载不只是人为极端，且行为 oracle 一致，才 GO。若投影、其他查询/因素主导，收益不可辨，或探针/语义不可靠，即 NO-GO：不改 `src/main`，投影若值得研究另立提案，不顺手叠加。隔离 GO 只证明隔离口径，不声称生产频度、MySQL/Tomcat/P99 收益。
4. 仅 GO 时，先写确定性护栏并在旧实现确认**预期行为红**（`passLine != null` 的答卷 SQL 谓词在库内限定 `< passLine`，不再取回不合格行；不是编译/环境红）；再只在 `MakeupService` 此路径下推低分条件，不顺手做列投影/加索引/改排序。新实现须保留现有权限、缺考并集、原因覆盖、空名单不读 users、软删姓名、无 `passLine` 路径与响应形状。按同负载复测请求/SQL/资源与语义，未达收益则撤回实现并按 NO-GO 记录。
5. 在最终已提交代码 revision 上运行仓库根 `mvnw.cmd clean test`（或 `./mvnw clean test`），记录原始命令、四计数、BUILD、退出码、revision 及 AGENTS.md 自查；按 GO/NO-GO 真值合入或不合入 delta、更新 `spec/README.md` 并归档。不得把测量 IT 的定向通过或跳过当全量门禁通过。

## Impact

- **规范**：仅 GO 且验收通过时，将 `spec-delta.md` 的补考候选低分取数条款合入 `spec/specs/absence-makeup/spec.md`；NO-GO 保留未采用草案与评估注记，不把拟实施条款写进基线。已有 `spec/specs/performance/spec.md` 的“先归因、一次只改一类”门禁不改。
- **代码**：测量/语义护栏仅 `src/test`；GO 后预计只改 `MakeupService.listEligibleStudents` 的低分查询谓词。`MakeupScoreService`、其他补考路径、Mapper SQL、schema、索引、前端、API、JVM/线程池均不改。
- **用户与部署**：不新增端点，不改变可见候选集合、原因与姓名；不写共享 dev、不开 Docker、不动数据卷。`docs/backend-optimization-candidates.md` 现为未跟踪台账，保留且不暂存；`isolate-submit-load-generator` 原样保留。

## 停止条件与证据边界

- 探针导致业务语义差异、旧行为 oracle 无法成立、受控臂未单独隔离两个因素、波动吞没收益、低分筛选非主导或没有可代表适用场景的负载：停止该候选，不为了 GO 修改判据。
- 运行时请求量与真实 MySQL 字节/耗时目前未知；隔离 H2/进程内结果不能外推生产瓶颈或 P99。即使技术收益成立，若没有获准的生产/真实环境观测，也只能写“隔离负载技术收益”，不能写生产价值已证实。