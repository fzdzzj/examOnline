# absence-makeup 规范

> 能力域：缺考与补考（阶段 9、12）。
> 来源：`spec/changes/archive/add-class-and-post-exam-closure` 合入（缺考标记、补考独立记录、补考成绩规则）；`spec/changes/archive/add-post-exam-closure-e2e` 合入（阶段 12：两条结束路径对称、闭环端到端、建表与实体双向一致）；`spec/changes/archive/add-makeup-final-score` 合入（补考最终成绩接口接线，提案⑨，2026-09-22）。
> 实施注记：缺考口径 = 应考名单（考试 `class_id` → `user_class` 当前学生）− 有答卷者；缺考锚定「进行中→已结束」，**自然到点与 force-end 两条路径都必须 `markAbsence`**；写 `exam_absence`（唯一索引 + INSERT IGNORE 幂等）。闭环由 `PostExamClosureIntegrationTest` 整链执行；集成测试不得 `@Sql` 自建表。补考是独立考试记录（`exams.parent_exam_id` 关联主考），准入由 `exam_candidates` 名单限制。

## Requirements

### Requirement: 缺考标记

WHEN **考试结束（无论自然到点结束，还是教师提前结束）**,

系统 SHALL 将「应考名单中无答卷记录的学生」标记为缺考，缺考学生 SHALL 可被教师筛选指定补考。

**为什么要把"结束"写清**：原文只写"考试结束"，实现把它读成了"自然到点结束"。于是教师「提前结束」（`force-end`）这条真实路径上，缺考从未被标记，且因考试状态已变为「已结束」，定时扫描（只扫「进行中」）再也不会碰它——**永久漏标记且无法自愈**。规范的模糊处正是缺陷的藏身处。

**触发时机**：锚定**「进行中 → 已结束」这一状态迁移**（含自然到点与教师提前结束两条路径），而非"开考"。理由：时间窗内允许迟到，学生可能开考后才进入，开考即判缺考会在迟到窗口内误伤；只有时间窗彻底关闭时，"仍无答卷"才是终局判定。

**幂等**：应考名单 − 有答卷者 = 缺考，批量写入命中唯一约束时静默跳过；重复触发（定时扫表、启动补偿、重复提前结束）不产生重复缺考记录。

#### Scenario: 考试结束标记缺考

GIVEN 考试到达结束时间

AND 应考名单中某学生未点击开始（无答卷）

WHEN 系统识别缺考

THEN 标记该学生为缺考

AND 记录缺考状态

#### Scenario: 教师提前结束同样标记缺考

GIVEN 教师对进行中的考试执行提前结束

AND 应考名单中某学生从未进入（无答卷记录）

WHEN 考试被提前结束

THEN 该学生同样被标记为缺考

AND 后续可被筛选进入补考名单

#### Scenario: 答了但未交卷不算缺考

GIVEN 学生已进入考试（存在答卷记录）但未手动交卷

WHEN 考试结束

THEN 该学生不被标记为缺考

AND 其答卷按强制交卷链路处理

#### Scenario: 重复触发不重复标记

GIVEN 某场考试的缺考已被标记

WHEN 同一场考试再次触发结束或再次执行标记

THEN 缺考记录不重复写入

AND 已有缺考记录保持不变

#### Scenario: 缺考名单可筛选

GIVEN 教师查看缺考名单

WHEN 教师按考试查询

THEN 返回该考试全部缺考学生

AND 可勾选进入补考名单

---

### Requirement: 补考独立记录

WHEN 教师组织补考,

系统 SHALL 创建独立补考考试记录，与主考互不影响，且 SHALL 限制仅名单内学生可进入。

#### Scenario: 补考独立

GIVEN 教师为主考指定补考

WHEN 创建补考

THEN 生成独立考试记录（独立时间窗/时长/规则）

AND 与主考成绩互不影响

#### Scenario: 名单限制进入

GIVEN 补考有指定名单

WHEN 名单外学生尝试进入补考

THEN 拒绝进入

---

### Requirement: 补考成绩规则

WHEN 计算补考最终成绩,

系统 SHALL 按考试配置的规则（取最高分/取最近一次/取平均分）合并，历史成绩 SHALL 保留不覆盖，且最终成绩 SHALL 可经接口查询。

#### Scenario: 取最高分

GIVEN 补考成绩规则为取最高分

AND 学生主考与补考均有成绩

WHEN 计算最终成绩

THEN 取两者较高分

AND 历史成绩均保留

#### Scenario: 历史成绩保留

GIVEN 学生多次补考

WHEN 计算最终成绩

THEN 各次成绩记录均保留

AND 不覆盖删除

#### Scenario: 最终成绩经接口可查

GIVEN 学生主考与补考均有成绩

WHEN 经补考最终成绩接口查询

THEN 返回按考试配置规则合并的结果

AND 历史成绩仍保留

#### Scenario: 合并规则有真实调用者

GIVEN 代码库中 MakeupScoreService 的最终成绩计算入口

WHEN 检查其调用点

THEN 存在接口层真实调用

AND 不再是零调用死代码

---

### Requirement: 考后闭环跨环节一致性

WHEN 一场绑定班级的考试走完「结束 → 缺考 → 补考 → 批改发布 → 复核」全程,

系统 SHALL 保证各环节的判定同源、互不污染，使该闭环可作为整体被验证（而非仅各环节单独可用）。

#### Scenario: 缺考名单驱动补考可选名单同源

GIVEN 考试已结束且缺考已标记

WHEN 教师查询可补考学生

THEN 已标记缺考的学生出现在可选名单中且原因为缺考

AND 非缺考学生不会因此被误纳入

#### Scenario: 补考独立于主考

GIVEN 教师基于主考创建补考

WHEN 补考存在

THEN 补考是独立考试记录（自身时间窗、时长、成绩规则）

AND 主考的时间窗与答卷不受影响

#### Scenario: 名单限制在闭环中生效

GIVEN 补考已有指定名单

WHEN 名单内与名单外学生分别尝试进入

THEN 名单内学生可进入

AND 名单外学生被拒绝进入

#### Scenario: 成绩发布后复核联动成绩显示

GIVEN 成绩已发布且学生提交复核申请

WHEN 复核处于进行中

THEN 学生端成绩显示为复核中

AND 教师处理后（同意或驳回）成绩显示恢复

#### Scenario: 复核不覆盖历史

GIVEN 复核以调整总分结束

WHEN 查看该学生成绩

THEN 调整后的成绩生效

AND 复核记录与处理结果保留可查

---

### Requirement: 闭环链路的整体可执行性

WHEN 声称考后闭环的各环节已实现,

系统 SHALL 能以一条**真实链路**（真实表结构、真实 SQL、不使用测试自建表）从「建班」走到「复核处理」，且 SHALL 保证链路内每个写操作引用的列都存在于正式建表定义中。

**为什么这是一条规范而不是"测试要求"**：闭环四个端点此前零集成覆盖（`MakeupService` 11.8%、`ScoreReviewController` 22.2%），结果是**复核申请的 INSERT 引用了一个在所有环境都不存在的列**（`score_review.created_time`）却长期无人发现——它的接口从未成功执行过一次。可见"各环节单独可用"不足以支撑"闭环可用"：**没有被整体执行过的链路，等于没有被验证过**。

#### Scenario: 链路可整体执行

GIVEN 一张班级名单与一场绑定该班级的考试

WHEN 按顺序执行结束、缺考标记、补考创建、批改、汇总、发布、复核、复核处理

THEN 每一步都成功完成

AND 全程不需要测试自行建表

#### Scenario: 链路内的持久化与建表定义一致

GIVEN 闭环链路中的任一写操作

WHEN 它在真实表结构上执行

THEN 其引用的列均存在于建表定义中

AND 该性质由端到端用例证明，而非被 mock 掩盖

#### Scenario: 交付前必须先暴露失败

GIVEN 链路中存在尚未修复的缺陷

WHEN 先行运行端到端用例

THEN 用例如期失败并指出缺陷位置

AND 缺陷修复后同一条用例转绿（不得通过放宽断言转绿）

---

> 评估结论注记（NO-GO，2026-09-27，`update-makeup-eligible-score-filter-attribution` 归档）：本基线
> **未合入**该提案的任何 Requirement——其 spec-delta（补考候选低分取数把 `total_score < passLine`
> 下推进 SQL）按**未采用草案**随目录归档，补考候选名单的现行行为条款不变。该提案先归因后裁决：
> 在隔离 H2 + 进程内直调 Service（test profile、仓库正式 `schema.sql` 建表、不启 Docker、不写共享
> dev）三次独立 JVM 调用、6 形状 × 4 轮，预登记判据（`evidence/PREREGISTRATION.md`，写定于正式测量前）
> 的结论算子 `D1∧D2∧D3∧D4∧¬D5∧D6` 为 **false**——**D1 不成立**：`s200-p10-a2k` 有 4/12 个样本中
> 「答卷取回 + Java 过滤」组不是最大相位（200 人规模下低分取回非占比最高的可控因素）；
> **D2 不成立**：`s200` 下推请求级比值最小值 1.317 < 判定带 1.797（同轮 `OLD` 等价副本与前后两次
> 生产入口的漂移读数吞没收益），`s1000-p10-a20k` 最小值 2.399 < 判定带 3.518（判定带由同代码顺序漂移
> 样本 5.118→1.455ms 设定；若只用等价臂噪声带上界 1.565 则越过——如实记为「稳定性不达标」而非
> 「收益不存在」）；D3（结果等价）与 D6（可靠性）成立、D5（投影主导）不成立。方向性读数（不作 GO
> 依据）：下推中位收益 1.91/3.48/3.76/2.97 倍、并发吞吐中位比 3.69–4.96 倍，但低分占比升高时收益衰减
> （p90 中位 1.06、投影在 p90 10/12 与 p50 7/12 轮次胜出，p90 由姓名装载相位主导）。敏感性四读法中
> 三种仍 NO-GO；唯一翻绿的读法用中位数替换「每一轮」，不再检验提案要求的逐轮稳定性，按
> 「不得为追求 GO 放宽判据」不采用。按判据裁决 **NO-GO 并停止实施**：未改 `MakeupService` 与任何
> `src/main` 代码（`git diff --stat 31a5ff8 f88655a` 仅测试侧测量工具 1 文件），未做列投影/索引/排序/
> 缓存/schema/前端/API/JVM 改动，未转手实施投影。运行时边界：本端点真实请求频度、真实考试人数与
> `answers` 分布无获准来源，记为未知；隔离 H2 结果不外推生产 MySQL/Tomcat 与 P99。数字、逐轮原值、
> 反例与算子见 `spec/changes/archive/update-makeup-eligible-score-filter-attribution/evidence/`
> （`measurement-rounds.md`、`rounds-analysis.json`、`sensitivity-analysis.json`、`evidence-sha256.txt`）；
> 门禁 `mvnw.cmd clean test` @ `f88655a` → 314/0/0/1 BUILD SUCCESS 退出码 0。
