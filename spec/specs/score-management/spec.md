# score-management 规范

> 能力域：成绩管理（阶段 6，W7）。
> 来源：`spec/changes/add-grading-score` 合入（成绩汇总、发布/撤回、排名、导出）。

## Requirements

### Requirement: 成绩汇总

WHEN 教师对已结束考试执行成绩汇总,

系统 SHALL 在本次纳入汇总的答卷均已完成判分且产生客观分（包括合法零分）时，汇总客观分与已批主观分得到总分；存在未判或判分失败答卷时 SHALL 在任何成绩与考试状态写入前拒绝；未批主观题仍按既有“部分批改”规则处理。主观分 SHALL 按本场答卷一次取出，而不是每份答卷各查一次。

#### Scenario: 加权汇总

GIVEN 已交卷答卷的判分状态成功且客观分已产生

WHEN 系统汇总

THEN 总分 = 客观分 + 已批主观分

#### Scenario: 判分未运行或失败则整场拒绝

GIVEN 本次待汇总答卷中至少一份未判分、判分失败或客观分未产生

WHEN 教师请求汇总

THEN 系统返回可观察的判分前置条件错误

AND 不写任何答卷总分或部分批改标记

AND 不推进考试至已批改状态

#### Scenario: 合法客观零分允许汇总

GIVEN 一份纯主观试卷已完成判分或教师已手动裁定客观零分

AND 答卷判分状态成功、客观分明确为零

WHEN 教师汇总

THEN 允许汇总并按实际已批主观分计算总分

AND 不将合法零分误判为“未判分”

#### Scenario: 主观未批完仍可部分汇总

GIVEN 客观判分已完成且试卷中仍有未批主观题

WHEN 教师汇总

THEN 未批主观题按零分计入

AND 整体标记部分批改，保持 `docs/需求决策记录.md` §7.5 的发布与复核语义

#### Scenario: 主观分一次取出

GIVEN 同一场有多份待汇总答卷且卷面含主观题

WHEN 系统汇总

THEN 这些答卷的主观分记录在进入逐份写入之前一次取出

AND 某份答卷没有主观分行时，仍按未批口径处理，不视为已批完

---

### Requirement: 成绩发布

WHEN 成绩已批改,

系统 SHALL 支持发布前预览与批量发布，状态由已批改迁至已发布。

#### Scenario: 发布前预览

GIVEN 教师完成批改

WHEN 教师查看发布预览

THEN 系统展示各学生成绩明细

#### Scenario: 批量发布

GIVEN 教师确认发布

WHEN 教师执行发布

THEN 状态迁至已发布

AND 学生可见成绩

---

### Requirement: 成绩撤回

WHEN 教师撤回已发布成绩,

系统 SHALL 需管理员权限，撤回后学生 SHALL 不再看到成绩，并 SHALL 记录审计日志。

#### Scenario: 撤回隐藏成绩

GIVEN 成绩已发布且学生已看到

WHEN 管理员执行撤回

THEN 学生端不再显示成绩

AND 记录撤回审计日志（谁/何时/原因）

---

### Requirement: 成绩排名

WHEN 系统计算排名,

系统 SHALL 按总分排序，同分 SHALL 并列同名次。在同负载测量证明排名计算是该路径占比最高的可控因素且批准实施计算优化时，排名计算 SHALL 在维持原结果的前提下避免逐份答卷再次遍历全班的 O(n²) 比较，比较次数增长 SHALL 不超过 O(n log n)。仅凭静态复杂度 SHALL NOT 宣称端到端请求已经加速，改后须按同负载验收。学生查本人名次（myScore）SHALL 以一条聚合计数确定名次而不是取回全班答卷行；该取数形态的替换 SHALL 仅在同口径基线证明「全班取数+结果映射」为该请求占比最高的一类因素后实施，并在同一数据与负载下复测验收；归因不成立或改后无收益 SHALL NOT 实施，SHALL NOT 为凑收益顺手修改索引、缓存、JVM 或线程池。

#### Scenario: 并列同名次

GIVEN 两名学生总分相同

WHEN 计算排名

THEN 两人并列同一名次

AND 后续名次跳空（1,2,2,4）

#### Scenario: 空值与数值等价不改变口径

GIVEN 成绩列表中包含未汇总的 null 分数、数值相等但 scale 不同的 BigDecimal 分数以及不同分数

WHEN 排名计算返回与原入参等长的结果

THEN null 项名次为 0 且不影响其他名次

AND 以 BigDecimal.compareTo 的数值比较决定并列，同分后的名次按人数跳空

AND 各项名次仍对应原入参位置；空输入返回空结果

#### Scenario: 归因成立才替换计算结构

GIVEN 固定考试规模和分数分布下已记录请求延迟、吞吐、资源与 SQL/排名计算分段耗时

AND 测量确认排名计算为该路径占比最高的可控因素

WHEN 对纯排名计算实施优化

THEN 旧二重比较实现在确定性比较次数护栏下失败，新实现通过

AND 同一数据与负载下分别报告计算指标与端到端指标，不把 SQL 取数或交卷路径的变化记作本次收益

#### Scenario: 缺少归因或改后无收益

GIVEN 测量不可用、SQL 等其他因素主导，或改后同口径指标未改善

WHEN 评估是否合入本变更

THEN 不宣称排名计算已解决请求瓶颈

AND 不为变绿顺手修改 SQL、缓存、JVM 或线程池；拟实施条款不作为已实现规范合入

#### Scenario: 名次取数为聚合计数

GIVEN 学生查询本人成绩名次且本人行已在全班已汇总集合内

WHEN 系统计算名次

THEN 以一条聚合计数（严格更高分人数 + 1）得出，不取回全班答卷行

AND 名次与逐份比较的竞赛排名口径逐学生一致（含并列与跳号）

AND 该路径对答卷表的行级取数条数不随班级人数增长

#### Scenario: 404 条件显式且逐条等价

GIVEN 本人答卷行存在且考试已发布

WHEN 本人行缺失、总分未产生或答卷状态不是已批改

THEN 一律返回「暂无本人成绩记录」

AND 与既有「本人行是否落在全班 GRADED 集合」的隐式判定逐条等价，不返回排名为 0 的假成绩

#### Scenario: 取数形态替换须先归因且改后验收

GIVEN 针对学生查名次路径的取数形态提出优化

WHEN 实施取数形态替换

THEN 同口径基线已证明「全班取数+结果映射」为该请求占比最高的一类且稳定高于轮间波动、探针开销单列不可忽略时不实施

AND 改后在同一数据与负载下复测并如实报告收益或无收益

AND 隔离环境结果不外推生产数据库与应用服务器性能

> 合入注记（2026-09-29，`optimize-my-score-rank-fetch`）：myScore 名次取数由「selectList 取回全班已汇总
> 答卷行 + RankCalculator 按下标定位」改为一条聚合计数 `COUNT(exam_id=? AND status=GRADED AND
> total_score IS NOT NULL AND total_score > 本人总分) + 1`，404 判定由「本人行落在全班 GRADED 集合」
> 的隐式形式改为显式判定（无行 / totalScore 为 null / status 不是 GRADED → 「暂无本人成绩记录」），
> 条件集合逐条等价。同负载归因（隔离工作树 3a5c532 三轮）证实「取数+映射」为该请求占比最高的一类
> （serviceDirect 份额 n=3000 达 82.9–84.2%，n=50–3000 全部最高）；同口径复测（39d64d0 三轮）：
> 答卷表行级取数 51/201/1001/3001 → 2 行（不随班级人数增长）、每请求 SQL 条数保持 4、serviceDirect
> mean 比值 n=3000 14.97–24.15×、n=1000 5.79–7.60×、n=200 1.43–1.60×、n=50 落在轮间波动内（无可见
> 收益，如实登记）；E1–E8 等价测试旧实现红（E6 成批取回 3000 行、E7 实测 201 行）新实现绿。隔离 H2 +
> MockMvc 同进程结果**不外推生产 MySQL/Tomcat、不构成交卷 P99 收益**。证据见
> `spec/changes/archive/optimize-my-score-rank-fetch/evidence/`（逐轮 JSON + sha256 + measurement-rounds.md）。

---

### Requirement: 成绩导出

WHEN 教师导出成绩,

系统 SHALL 提供全班成绩单、逐题明细、题目统计、个人成绩单，且 SHALL 以流式方式防止大数据量内存溢出。

#### Scenario: 全班成绩单导出

GIVEN 教师请求导出

WHEN 导出完成

THEN 生成含每人分数与排名的 Excel

#### Scenario: 大数据量不溢出

GIVEN 数千条成绩记录

WHEN 系统导出

THEN 采用流式写入

AND 不因内存不足失败

> 评估结论注记（2026-09-27，`update-question-stats-export-memory` 归档）：本基线**未合入**该提案的
> 任何 Requirement——其 spec-delta（题目统计在保持统计口径下的逐题全量中间数据持有约束）按
> **未采用草案**随目录归档。要点：在隔离环境（H2 内存库 + 进程内直调 Service，不写共享 dev、不启
> Docker）对旧实现同负载三轮实测（被测旧业务基线 revision `8085c57`，测量工具提交 `91bf892`、
> `QuestionStatsExportAttributionMeasureIT`，类名以 IT 结尾不进全量门禁），三轮 XLSX 单元格与 zip
> 条目内容均与生产等价（oracleMatch=true）。**跨窗口量不写作请求内精确占比**：逐轮并列报告绝对 MB
> ——s3000-q60 分相位精确线程分配 pagingResolve（答卷分页+逐题解析）67.108/70.404/70.404MB、
> aggregatePairs（`pairs` 积累）8.472MB（三轮相同）、xlsxSerialize（SXSSF）9.025/9.021/9.024MB；
> s1000-q150 pagingResolve 46.951/49.698/49.698MB、aggregatePairs 6.787MB（三轮相同）、
> xlsxSerialize 14.121/14.142/14.133MB。若给比例，只用**同一 replica 内已观测相位分配之和**作分母
> （s3000-q60 相位和 89.922/93.222/93.217MB ⇒ pagingResolve 74.6–75.5%、aggregatePairs
> 9.1–9.4%、xlsxSerialize 9.7–10.0%；s1000-q150 相位和 83.442/86.228/85.987MB ⇒ pagingResolve
> 56.3–57.8%、aggregatePairs 7.9–8.1%、xlsxSerialize 16.4–16.9%），不冒充生产请求整体占比。
> `pairs` 的**持有**量 `pairsRetainedMB` 6.445MB@180000 与同轮 heapPeakMB
> 167.311/173.663/173.363MB 属不同测量窗口（GC 后差分 vs 采样上界），只并列绝对 MB、不写成
> 「占峰值 3.8%」。综合绝对量级，答卷分页与逐题解析（67.108–70.404MB）大于 `pairs` 积累（8.472MB）
> 与 SXSSF（9.025–9.024MB），`pairs` 不是占比最高的可控因素。若采用两遍扫描这一候选来消除 `pairs`，
> 会增加一次分页/解析、须计其 SQL 与 CPU 代价（分页 SQL 约 12→24、解析相位耗时约翻倍）；当前隔离
> 证据不支持 `pairs` 为占比最高的可控因素，故不因此另试新实现——**NO-GO**，`src/main` 零改动。
> 数字与依据见 `spec/changes/archive/update-question-stats-export-memory/tasks.json` 阶段 2 evidence；
> 隔离 H2 + 进程内直调不代表真实 MySQL/Tomcat 生产性能，不构成生产 P99 结论。

---

### Requirement: 考试数据分析报告

系统 SHALL 提供教师对**已汇总（GRADED 及以上）考试**的只读分析报告接口
`GET /api/exams/{examId}/scores/analysis-report`（权限 `exam:manage` + 归属越权校验），返回
班级概览 / 逐题指标 / 知识点薄弱 / 学生关注名单四块结构化数据。逐题指标（平均分/得分率/答对率/
区分度/作答人数）SHALL 与题目统计导出复用**同一聚合核心**（`ScoreExportService.aggregateQuestionStats`），
口径严禁与导出分叉。班级概览 SHALL 报告应考（班级名单）/ 实考（已汇总答卷）/ 缺席（应考−有答卷）人数、
平均分、及格率（≥60 分）与分数段分布（0-59/60-69/70-79/80-89/90-100）。知识点薄弱 SHALL 按
`question_tags × tags` 聚合得分率（升序，薄弱优先）；试卷题目均无知识点标签时 SHALL 返回空数组 +
`hasTagDimension=false`，不得报错或臆测。学生关注名单 SHALL 返回低于及格线学生的学号/姓名/总分。
考试不存在 SHALL 返回 404，非归属教师 SHALL 返回 403，未汇总考试 SHALL 返回 400。

#### Scenario: 分析报告三块结构返回

GIVEN 教师对已汇总（GRADED）且有成绩的考试发起分析报告查询

WHEN 调用 `GET /api/exams/{examId}/scores/analysis-report`

THEN 返回 200 且包含班级概览 / 逐题指标 / 知识点薄弱 / 学生关注名单四块

AND 班级概览含应考/实考/缺席人数、平均分、及格率与五段分数段分布

#### Scenario: 逐题指标与导出口径同源

GIVEN 一场已汇总考试有若干题目与已判分答卷

WHEN 分析报告返回逐题指标且题目统计导出亦被请求

THEN 两者的平均分/得分率/答对率/区分度/作答人数取自同一聚合核心，口径一致

#### Scenario: 越权与存在性

GIVEN 教师 A 拥有某考试，教师 B 未拥有，且存在不存在的考试 ID

WHEN 教师 B 查询 A 的考试，或任一教师查询不存在的考试

THEN 非归属教师查询返回 403

AND 不存在的考试返回 404

#### Scenario: 未汇总考试拒绝

GIVEN 一场考试尚未执行成绩汇总（状态低于 GRADED）

WHEN 教师查询其分析报告

THEN 返回 400 且提示先执行成绩汇总

#### Scenario: 知识点维度优雅降级

GIVEN 一场考试试卷各题均未挂知识点标签

WHEN 教师查询其分析报告

THEN 知识点块返回空数组且 `hasTagDimension=false`

AND 接口不报错、前端据该标志提示「题库未打知识点标签」

#### Scenario: 知识点薄弱聚合与排序

GIVEN 一场考试至少一题挂了知识点标签且有已汇总成绩

WHEN 教师查询其分析报告

THEN `hasTagDimension=true` 且按标签聚合得分率（携带该标签题目的得分率均值）

AND 结果按得分率升序（薄弱优先）返回

#### Scenario: 学生关注名单

GIVEN 一场已汇总考试存在总分低于及格线（60）的学生

WHEN 教师查询其分析报告

THEN 关注名单返回这些学生的学号/姓名/总分，不含高于等于及格线者

---

### Requirement: 错题本分页查询

WHEN 学生查询本人的错题列表,

系统 SHALL 以学生已参加且已发布的考试为粒度进行分页，每页最多支持 10 场考试组；仅对当前页的考试执行 PersonalReport 现场重算；对每场考试中得分严格小于满分（包括 0 分及多选/主观题部分得分）的题目判定为错题；返回考试分组、题干、选项、我的答案、正确答案、得分/满分、题目解析与判分依据；题目解析（`analysis`）SHALL 取自 `questions.analysis`，不得与客观判分依据（`detail`）或教师评语混淆。未发布考试的答卷 SHALL 严格隐藏。

#### Scenario: 考试粒度分页与同源重算

GIVEN 学生参加了多场考试且考试均已发布

WHEN 学生请求第 1 页错题本且分页大小为 5

THEN 系统仅对当前页的最多 5 场考试调用 PersonalReport 路径重算逐题得分

AND 返回的错题数据按考试分组呈现

AND 总记录数（`total`）为符合条件的已发布考试总数

#### Scenario: 错题判定包含部分对与零分

GIVEN 某场考试中学生第 1 题得 0 分（满分 5 分），第 2 题得 2 分（多选漏选部分得分，满分 4 分），第 3 题得 5 分（满分 5 分）

WHEN 系统计算该场考试的错题列表

THEN 第 1 题与第 2 题均判定为错题并进入错题列表

AND 满分的第 3 题不进入错题列表

#### Scenario: 字段完整映射且题目解析语义正确

GIVEN 某错题在题库中配置了解析文案（`questions.analysis`）

WHEN 系统组装错题数据

THEN `myAnswer` 为学生作答的真实答案且非空串非占位

AND `correctAnswer` 为题目的标准答案且非空串非占位

AND `analysis` 精确映射为题目解析文案

AND 客观题的判分依据或教师评语作为可选依据字段独立返回，不覆盖题目解析

#### Scenario: 未发布考试严格门控隐藏

GIVEN 某场考试处于草稿、进行中或已汇总待发布状态

WHEN 学生查询错题列表

THEN 该场考试的题目不得进入错题列表，不泄露任何题目得分与答案

---

### Requirement: 单场考试逐题回顾

WHEN 学生查看某场考试的逐题回顾明细,

系统 SHALL 校验发布状态与答卷归属；考试未发布时统一返回 400「成绩待发布」；答卷不存在或未批改完成时返回 404「暂无本人成绩记录」；已发布且有已批改答卷时，返回包含卷面全部题目的逐题回顾（包括题号、题型、题干、选项、学生答案、正确答案、得分、满分、批改状态、题目解析及评语）。

#### Scenario: 已发布考试逐题回顾正常返回

GIVEN 考试已发布且当前学生有已批改的答卷

WHEN 学生请求该考试的逐题回顾

THEN 系统返回该场考试的卷面全量题目回顾明细

AND 包含总分、排名、每道题的作答、标准答案、得分与题目解析

#### Scenario: 考试未发布拒绝回顾

GIVEN 考试状态不是已发布（例如 STATUS_GRADED 汇总后尚未发布）

WHEN 学生请求逐题回顾

THEN 系统返回 400 业务异常且错误消息为「成绩待发布」

#### Scenario: 无答卷记录拒绝回顾

GIVEN 考试已发布但当前学生未参加或无已汇总答卷

WHEN 学生请求逐题回顾

THEN 系统返回 404 业务异常且错误消息为「暂无本人成绩记录」

### Requirement: 班级匿名榜单

WHEN 学生查询已发布考试的班级榜单,

系统 SHALL 返回按总分降序的前 10 名（并列同名次跳号，与竞赛排名口径逐条一致）与本人位置；他人姓名 SHALL 脱敏展示（保留姓氏）且 SHALL NOT 下发他人学生标识；榜单 SHALL 仅在成绩已发布且本人可查到成绩时可见；取数 SHALL 固定为前 10 行 + 本人行，行级取数条数 SHALL NOT 随班级人数增长。

#### Scenario: 已发布考试返回匿名前 10

GIVEN 考试成绩已发布且学生为该场考生

WHEN 学生查询班级榜单

THEN 返回总分降序前 10（含名次、脱敏姓名如「张**」、总分）

AND 并列同分同名次、后续名次跳号（与 publishPreview 口径逐条一致）

#### Scenario: 本人位置的两种形态

GIVEN 学生本人已汇总成绩

WHEN 本人名次在前 10 内

THEN 榜单中本人行实名且携带 isMe 标记

WHEN 本人名次不在前 10

THEN 榜单外单独返回本人行（名次 + 总分 + isMe），不混入前 10 截断

#### Scenario: 未发布或无本人成绩不可见

GIVEN 考试成绩未发布，或本人无成绩记录（未交卷 / 未汇总 / 状态非已批改）

WHEN 学生查询班级榜单

THEN 与 myScore 同 404 语义返回「暂无本人成绩记录」

AND 不泄露榜单任何行

#### Scenario: 非本班考生不可见

GIVEN 学生不是该场考试的考生

WHEN 查询该考试班级榜单

THEN 拒绝访问（与 myScore 同准入判定）

#### Scenario: 匿名不泄露他人标识

GIVEN 榜单返回

THEN 每个非本人行仅含名次、脱敏姓名、总分

AND 不含他人 studentId / 学号等可定位标识
