# score-management 规范

> 能力域：成绩管理（阶段 6，W7）。
> 来源：`spec/changes/add-grading-score` 合入（成绩汇总、发布/撤回、排名、导出）+ `spec/changes/archive/add-exam-question-discrimination` 合入（试题区分度与难度评价诊断，2026-10-18）。

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

#### Scenario: 题目统计导出选项分布列

question-stats xlsx 在既有列（题号/题型/满分/平均分/得分率/答对率/区分度/作答人数）之后新增「选项分布」单列文本（形如 A:12(30%) B:8(20%)…，判断题 T/F），与 analysis-report JSON 的 optionDistribution 同源一致；既有列零删改；无选项题型该列为空。

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

#### Scenario: listGraded 两列投影

- WHEN 服务端生成考试数据分析报告并取全场已批改（GRADED 且总分非空）答卷
- THEN 取数仅投影 student_id 与 total_score 两列，不得逐行取回 answers LONGTEXT 等未消费列
- AND 报告各字段（班级概览/逐题指标/知识点薄弱/关注名单及 passLine/excellentLine）与既有契约逐字一致，openapi.yaml 零变更

#### Scenario: 投影护栏常驻

- WHEN 任何后续改动使 listGraded 投影列集与消费列集漂移（移除投影、或新增未投影列的消费）
- THEN 常驻护栏测试（投影列集 = 消费列集断言）必须击红

#### Scenario: 缺席计数查找复杂度

- WHEN 班级概览计算缺席人数（应考名单 − 有答卷者）
- THEN 判重查找为集合语义（O(1) 查找形态），缺席计数结果与既有口径逐字一致

#### Scenario: 应考口径随预约制分流

GIVEN 考试开启「需预约」且成绩已汇总

WHEN 教师生成考试数据分析报告

THEN 班级概览中的应考与缺席维度按该考试的有效预约名单计（与缺考域 `exam_absence` 口径同源，不得出现两套缺席数）

AND 报告其余维度（及格线/优秀线派生、分数段、逐题指标、知识点薄弱、关注名单）口径零变化；未开启预约的考试报告与既有输出逐字零回归

#### Scenario: 逐题选项分布

analysis-report 逐题指标 QuestionStatItem 新增 optionDistribution 数组 [{option, count, rate}]（选项字母或 T/F、选择人数、占比）；仅 SINGLE/MULTIPLE/JUDGE 输出分布，FILL_BLANK/SHORT_ANSWER 为空数组不报错；占比分母为该题作答人数，MULTIPLE 每人可贡献多个选项、占比和可超 100%，未作答学生不计入任何选项桶；选项计数与题目统计导出复用同一聚合核心，既有指标（平均分/得分率/答对率/区分度/作答人数）数值不得因本扩展漂移。

#### Scenario: 考试数据分析报告多 Sheet Excel 导出

- **GIVEN** 教师对已汇总（GRADED 或已发布）且有成绩数据的考试请求分析报告导出
- **WHEN** 调用 `GET /api/exams/{examId}/scores/analysis-report/export`
- **THEN** 系统返回 200 状态码与 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` 字节流
- **AND** 响应头包含 `Content-Disposition: attachment; filename*=UTF-8''...`
- **AND** 工作簿包含四个工作表：
  1. 「班级概览与分数段」：包含应考/实考/缺席人数、及格线、优秀线、平均分、及格率与五段分数段分布；
  2. 「逐题指标」：包含题号、题型、满分、平均分、得分率、答对率、区分度、作答人数与选项分布；
  3. 「知识点薄弱」：包含知识点名称与得分率（无标签题目时输出提示文本「题库未打知识点标签」）；
  4. 「学生关注名单」：包含低于及格线学生的学号、姓名、总分（无低于及格线学生时输出提示「暂无低于及格线学生」）；
- **AND** 导出的各项指标与 `GET /api/exams/{examId}/scores/analysis-report` 聚合口径逐字同源
- **AND** 写入的所有文本经 `ExcelSheetWriter.sanitizeFormula` 进行公式防注入清洗

#### Scenario: 分析报告导出越权与未汇总拦截

- **GIVEN** 教师 B 尝试导出教师 A 拥有的考试分析报告，或请求不存在/未汇总的考试 ID
- **WHEN** 调用 `GET /api/exams/{examId}/scores/analysis-report/export`
- **THEN** 非归属教师请求返回 403 Forbidden
- **AND** 不存在的考试返回 404 Not Found
- **AND** 未汇总考试（状态低于 GRADED）返回 400 Bad Request

---

### Requirement: 错题本分页查询

WHEN 学生查询本人的错题列表,

系统 SHALL 以学生已参加且已发布的考试为粒度进行分页，每页最多支持 10 场考试组；仅对当前页的考试执行 PersonalReport 现场重算；对每场考试中得分严格小于满分（包括 0 分及多选/主观题部分得分）的题目判定为错题；返回考试分组、题干、选项、我的答案、正确答案、得分/满分、题目解析与判分依据；题目解析（`analysis`）SHALL 取自 `questions.analysis`，不得与客观判分依据（`detail`）或教师评语混淆。未发布考试的答卷 SHALL 严格隐藏。错题条目新增 `consecutiveCorrect`（当前连对次数，int）与 `mastered`（是否已消灭，boolean）字段；列表默认过滤 `mastered=true` 条目，可选查询参数 `includeMastered`（默认 false）翻回全量；既有判定口径、分页行为、字段映射与解析语义逐字零漂移。

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

#### Scenario: 列表默认隐藏已消灭错题

GIVEN 某错题已被学生连续 3 次练习答对消灭

WHEN 学生请求错题本列表（不带 includeMastered）

THEN 该题不出现于任何考试分组，total 相应核减

AND 带 includeMastered=true 请求时该题出现且 mastered=true、consecutiveCorrect 为当前连对值

#### Scenario: 既有口径零漂移

GIVEN 本案合入前的既有错题本列表与单场回顾用例数据

WHEN 执行既有用例（不含 includeMastered 参数）

THEN 判定口径（score<max 含部分对）、已发布门控、考试粒度分页、字段映射与解析语义与既有基线一致

AND 既有「读侧装配不得携带未使用重计算」护栏（RankCalculator 零交互、判分同源）全绿

---

### Requirement: 错题重练与消灭计划

WHEN 学生对本人错题发起重练,

系统 SHALL 以 question 维度构建重练池（`GET /api/scores/my/wrong-questions/practice/pool`）：本人全部已发布考试中判定为错题的题目、跨考试去重、仅含可机器判分题型（SINGLE/MULTIPLE/JUDGE/FILL_BLANK），SHORT_ANSWER 错题 SHALL 保留于错题本列表但不入池；已消灭题目不入池。逐题提交（`POST /api/scores/my/wrong-questions/practice/submit`，questionId+answer）SHALL 复用既有客观判分核心判分（得满分＝答对），即时返回对错、正确答案、题目解析、当前连对次数与是否消灭，并持久化一条练习记录（practice_attempt）。同一题目连续第 3 次答对 SHALL 自动消灭，任一次答错 SHALL 将连对计数归零。重练 SHALL 仅限本人（token 学生）。

#### Scenario: 重练池构建与题型过滤

GIVEN 学生在两场已发布考试中错同一题，且另有简答与已消灭错题各一

WHEN 学生请求重练池

THEN 该重复错题仅出现一次（question 维度去重）

AND 简答题不入池（其仍可见于错题本列表）

AND 已消灭题目不入池

#### Scenario: 逐题提交即时判分反馈

GIVEN 重练池中一道单选题且学生提交正确答案

WHEN 学生调用 submit

THEN 返回 correct=true、正确答案、题目解析、consecutiveCorrect 递增与是否消灭

AND 提交错误答案返回 correct=false、正确答案与解析，连对计数归零

#### Scenario: 连续三次答对自动消灭

GIVEN 某题当前连对 2 次

WHEN 学生第 3 次提交正确答案

THEN 返回 mastered=true 且 consecutiveCorrect=3

AND 该题默认从错题本列表与重练池消失（includeMastered 可翻回列表可见）

#### Scenario: 答错重置连对

GIVEN 某题当前连对 2 次

WHEN 学生提交错误答案后再次提交正确答案

THEN 错误提交后连对归零，再答对后 consecutiveCorrect=1（重新累计）

#### Scenario: 单题提交门控短路定位与热路径读收敛 (optimize-practice-pool-hot-path)

- **WHEN**: 学生提交单题重练作答，服务端执行「题目属于本人错题池」门控
- **THEN**: 门控 SHALL 以单次定位验证实现——逐场装配至命中即短路（该生某场已判分快照包含该题且判分严格小于满分即通过），SHALL NOT 全量重建错题集合后再做包含判断
- **AND**: 门控定位循环中单场成绩单装配异常 SHALL 记录 warn 日志后跳过该场（对齐同文件既有口径），SHALL NOT 静默吞
- **AND**: 提交后连对进度推导的 attempt 回读 SHALL 限制为连对窗口所需的最近记录（LIMIT 3），消灭判定（连对 ≥3）SHALL 保持不变
- **AND**: 错题判定口径（得分严格小于满分且非简答）、列表页错题池全量装配语义、原题软删后的快照回退判分链路 SHALL 零改动
（由 optimize-practice-pool-hot-path 合入；背景：单题提交门控曾每次全量重建错题池——逐场快照解析判分 O(场数×题数) 叠加在提交热路径；门控循环异常静默吞与同文件既有 log.warn 口径不一致；连对推导回读全部历史 attempt 而连对窗口仅 3）

#### Scenario: 多选部分对不判答对

GIVEN 一道多选题漏选得部分分（考试判分口径为部分得分）

WHEN 学生以同样答案提交重练

THEN correct=false（答对口径＝满分，与错题判定 score<max 互补）

AND 与既有客观判分核心对该题该答案的判分结果一致（不另造第二套口径）

#### Scenario: 仅本人数据

GIVEN 学生 A 的错题与练习记录

WHEN 学生 B（或未登录态）请求 pool 或提交 A 的 questionId

THEN 仅能访问本人数据，不泄露他人错题与练习状态

#### Scenario: 原题被软删除后从考试快照平滑回退判分

WHEN 学生提交重练题目作答（`POST /api/scores/my/wrong-questions/practice/submit`），且该题目在题库中已被教师逻辑软删除（`is_deleted=1`）

THEN 系统 SHALL 自动从该学生所属且包含该题的已发布考试快照（`ExamSnapshot`）中装配该题目完整的类型、选项与标准答案，并基于既有客观题判分策略执行同源判分

AND 判分结果正常记录 `practice_attempt` 明细、连对次数与消灭状态正常更新，响应返回该题的正确答案与解析，保证历史错题练习链路不中断（由 harden-exam-paper-and-practice-boundaries 合入）

---

#### Scenario: 错题判定结果的缓存派生（判分同源不变量保持） (optimize-wrong-question-derivation-cost)

- **WHEN**: 学生请求重练池列表，或错题判定派生点读取错题集合（getPracticePool / getStudentWrongQuestionIds / isWrongQuestionOf）
- **THEN**: 系统 SHOULD 优先读取 Redis 缓存的逐题轻量判定结果（`{examId}:{studentId}` → 逐题 score/max/type），缓存键 SHOULD 携带判分事实源版本信号（submission 更新时间与主观批改行的更新时间/版本号聚合）；未命中的场 SHALL 按现行 `QuestionScoreResolver.resolve` 现算口径回填缓存并派生错题（首次访问不劣化）
- **AND**: 缓存值 SHALL 由现算口径产出（同引擎同快照同答案），SHALL NOT 引入第二套判分逻辑；教师批改等判分事实源变更后，后续读取 SHALL NOT 采信变更前的缓存值（版本信号变化即天然失效）
- **AND**: 缓存 SHALL NOT 存储答案文本、评语、判分依据明细（仅 score/max/type 轻量元组）；**错题本列表（listWrongQuestions）为显示契约消费答案文本/评语/判分依据，保留完整装配、非缓存读路径**（内容红线优先于性能语义的契约安全例外，复核 2026-10-16 裁定）；导出路径仍走完整口径
- **AND**: Redis 不可用时读路径 SHALL 退化为现行逐场现算（可用性优先，SHALL NOT 因缓存故障阻断重练池访问）

#### Scenario: 判错装配成本收敛 (optimize-wrong-question-derivation-cost)

- **WHEN**: 学生打开重练池列表（已发布 N 场）
- **THEN**: 判分核心装配（`assembleGradingCore`）调用次数在缓存命中后 SHOULD ≤ 未命中场数（而非 N）；同一请求内同一答卷的答案 JSON 解析 SHALL 至多一次（消除双重 parse）

---

### Requirement: 读侧装配不得携带消费路径未使用的重计算

WHEN 服务端装配读侧数据而消费路径不使用该计算结果时,

系统 SHALL 跳过消费路径未使用的重计算（含全场排名计算与未使用的用户取数），响应体与既有契约逐字一致；判分唯一事实源 SHALL 仍为现场重算，轻量入口与完整入口共用同一判分核心装配，绝不另造第二套判分口径；且 SHALL 常驻护栏测试，使任何重新引入未使用重计算或偏离判分口径的后续改动击红。

#### Scenario: 错题本列表不计算班级排名

- WHEN 学生查询错题本列表（每页至多 10 场已发布考试）
- THEN 服务端装配逐题数据时不得执行全场排名计算（`rankBySubmissionId` 的全班投影查询与 `RankCalculator` 调用），亦不得执行列表响应未使用的用户取数（`userMapper.selectById`）
- AND 响应体（total/page/size/groups 及错题条目各字段）与既有契约逐字一致，`openapi.yaml` 零变更

#### Scenario: 判分口径同源保持

- WHEN 错题本列表或单场回顾装配逐题得分/答案
- THEN 判分唯一事实源仍为 ScoreExportService 现场重算（轻量入口与完整入口共用同一判分核心装配，绝不另造第二套判分口径）
- AND 单场回顾（含名次与用户字段）与个人成绩单 PDF、逐题明细两处导出路径零改动

#### Scenario: 护栏常驻

- WHEN 任何后续改动使错题本列表路径重新引入全班排名计算，或使轻量入口判分口径偏离完整口径
- THEN 常驻纯 JUnit 护栏测试（轻量入口对 RankCalculator 零交互断言 + 判分三要素与完整口径一致断言）必须击红

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

### Requirement: 成绩服务读写分置

成绩能力的服务层 SHALL 按「读侧 / 写侧」分置：读侧（发布前预览、学生查分）唯一实现归属 `ScoreQueryService`，写侧（汇总、发布、撤回及其私有链、审计与 `SummarizeStats` record）唯一实现归属 `ScoreService`；两侧互不共享事务上下文。此类拆分为纯重构：读侧方法体 SHALL 逐字迁移（含全部注释与 javadoc），SHALL NOT 顺手重构、改写语义或删改注释。

#### Scenario: 读侧归属

GIVEN 发布前预览与学生查分两个强一致读方法

WHEN 查看服务层归属

THEN publishPreview 与 myScore 的唯一实现位于 ScoreQueryService，其构造器依赖恰为 ExamMapper、GradingSubmissionMapper、UserMapper、RankCalculator、ScoreReviewService 五个

AND ScoreService 不再持有读侧方法，构造器依赖降为纯写侧集合（不含 userMapper、rankCalculator、scoreReviewService）

#### Scenario: 写侧归属

GIVEN 汇总、发布、撤回三个写方法及其私有链

WHEN 查看服务层归属

THEN 写侧实现与 SummarizeStats record 原地保留在 ScoreService，方法体零改动

AND Controller 的 summarize/publish/revoke 端点签名与注解零改动，publishPreview/myScore 两端点改调 ScoreQueryService

#### Scenario: 强一致读护栏随类迁移

GIVEN myScore 的强一致读护栏（类/方法/Mapper 禁 @DS 从库路由）

WHEN 读侧拆出新类

THEN 反射护栏同时覆盖 ScoreService 与 ScoreQueryService 的类级与方法级（ScoreService 既有断言原样保留）

AND M5 列投影护栏锚点随迁至 ScoreQueryService#publishPreview，冻结列集与断言阈值不变

#### Scenario: 外部契约零变化

GIVEN openapi.yaml 契约与端点路径、schema 名、错误码、响应结构

WHEN 拆分合入

THEN 契约文件零改动（SummarizeStats record 仍嵌套于 ScoreService，契约 schema 的 simple name 形态稳定）

AND OpenApiContractTest 全绿

#### Scenario: 历史约束边界裁决

GIVEN MakeupScoreService javadoc 载有「补考成绩合并逻辑不得修改 ScoreService 现有方法体」的历史约束

WHEN 执行用户显式立项的读侧拆出变更卡

THEN 该单卡写侧边界系 add-makeup-final-score 的防顺手改边界、已随其归档完成使命，不适用于本案读侧方法迁移

AND MakeupScoreService 自身独立性不受影响（本案零触碰该类）

### Requirement: 分析报告及格线按试卷总分比例派生

WHEN 分析报告计算及格率、分数段分布或学生关注名单,

系统 SHALL 以试卷总分乘固定比例派生及格线与优秀线（60%/90%，HALF_UP 取整），SHALL 经响应字段下发派生值，AND SHALL 保持 100 分制试卷的既有输出零变化。

#### Scenario: 比例派生

GIVEN 任意总分的试卷

WHEN 报告生成

THEN 及格线为总分 60%、优秀线为总分 90%（HALF_UP 取整到整数分）

AND 及格率、分数段切分、学生关注名单三处消费统一使用该派生值

#### Scenario: 100 分制零回归

GIVEN 总分为 100 的试卷

WHEN 报告生成

THEN 及格线为 60、优秀线为 90

AND 及格率、分数段分布、关注名单与既有行为逐值一致

#### Scenario: 非常规总分

GIVEN 总分为 150（或 50）的试卷

WHEN 报告生成

THEN 及格线为 90（或 30）、优秀线为 135（或 45）

AND 三段分布按派生值切分而非绝对 60/90

#### Scenario: 总分不可得即拒绝

GIVEN 报告所属考试无法取到试卷总分

WHEN 报告请求

THEN 接口明确失败（fail-fast）

AND 不回退任何默认总分值

#### Scenario: 前端单源渲染

GIVEN 前端分析报告组件

WHEN 渲染关注名单文案与分数段标签

THEN 一切边界数值来自响应下发的 passLine/excellentLine 字段

AND 前端不含及格线/优秀线硬编码

#### Scenario: 契约与用例同步

GIVEN 响应新增两字段

WHEN 实施合入

THEN openapi 契约经重导出与真实实例字节级一致、前端类型同步

AND 双端用例总数只增不减且 gate-baseline 同笔上调

---

### Requirement: 成绩导出与报表生成

> 合入注记（2026-10-15，变更 `harden-data-sanitization-and-ui-states`，M-01 收口）：
> `ExcelSheetWriter.sanitizeFormula` 统一对 `= + - @ \t \r` 敏感字符前置单引号转义，防止 CSV / Formula Injection。

#### Scenario: Excel 导出文本单元格公式防注入清洗 (ADDED)
- **WHEN**: 导出服务调用 `ExcelSheetWriter` 写出包含用户可控数据的单元格，且文本以敏感公式字符（`=`, `+`, `-`, `@`, `\t`, `\r`）开头
- **THEN**: 系统 SHALL 在生成单元格值时自动前置单引号 `'`，强制电子表格软件将其作为纯文本解析，防止触发公式解析或 DDE 命令执行（Formula / CSV Injection）
- **AND**: 对于不以敏感字符开头的正常文本、数值或空字符串，系统 SHALL 保持原样输出，不产生冗余字符

---

### Requirement: 成绩单 PDF 导出

> 合入注记（2026-10-15，变更 `harden-review-flow-and-resource-limits`，L-02 收口）：
> 个人成绩单 PDF 导出时针对考试标题、试卷题干等文本执行 Unicode 增补平面、游离代理项及不可见控制字符清洗，避免 iText 编码异常。

#### Scenario: 个人成绩单 PDF 导出生僻特殊字符容错 (ADDED)
- **WHEN**: 导出个人成绩单 PDF 且考试标题或试题题干中含有超出基础中文字符集（`UniGB-UCS2-H`）编码范围的生僻字符或特殊数学符号
- **THEN**: 系统 SHALL 在写入 PDF 段落与表格单元格前进行字符安全过滤与替代，避免抛出 iText 内部不支持字符异常中断导出
- **AND**: PDF 文件 SHALL 依然能够正常生成并成功下载

---

### Requirement: 考务成绩生命周期通知

> 合入注记（2026-10-15，变更 `add-exam-lifecycle-notifications` 收口）：
> 成绩发布时系统旁路为该考试所有实考考生投递【成绩发布通知】；成绩复核处理完成（同意/驳回）后系统旁路为申诉学生投递【成绩复核结果通知】。

#### Scenario: 成绩发布向实考考生旁路推送通知 (ADDED)
- **WHEN**: 教师发布一场考试成绩（`POST /api/scores/publish`，状态由已批改迁为已发布）
- **THEN**: 系统 SHALL 旁路获取该考试的全部实考考生 ID（`exam_submissions` 中 status=GRADED 的学生）
- **AND**: 系统 SHALL 为每位实考考生生成一条站内消息（title="成绩发布通知"，content 包含试卷标题与成绩查询指引，examId 为该考试 ID，read_flag=0）
- **AND**: 消息生成过程中的任何异常 SHALL 被旁路捕获并降级记录，绝不影响或回滚成绩发布事务

#### Scenario: 成绩复核处理完成向申请学生旁路推送通知 (ADDED)
- **WHEN**: 教师处理完成学生的成绩复核申请（`POST /api/scores/reviews/{id}/handle`，动作可为 AGREE 或 REJECT）
- **THEN**: 系统 SHALL 为申请该复核的学生生成一条站内消息（title="成绩复核结果通知"，content 包含处理结果已同意/已驳回，若同意且调分包含调整后总分信息，examId 为该考试 ID，read_flag=0）
- **AND**: 消息生成过程中的任何异常 SHALL 被旁路捕获并降级记录，绝不影响或回滚复核处理事务

---

### Requirement: 跨考试学情分析与个性化推荐

> 合入注记（2026-10-15，变更 `add-student-learning-analytics` 收口）：
> 系统为学生提供跨考试学情画像（均分/走势/知识点掌握度）与薄弱知识点靶向练习推荐；为教师提供班级跨考试学情综合概览与越权防护。

#### Scenario: 学生查询个人跨考试学情画像 (ADDED)
- **WHEN**: 学生调用 `GET /api/scores/my/analytics` 查询个人学情
- **THEN**: 系统 SHALL 聚合该学生已交卷且已发布的所有考试（`status >= STATUS_PUBLISHED` 且 `published=1`）
- **AND**: 系统 SHALL 计算历史总览指标（参测场次、平均分、最高分、最低分、及格率）
- **AND**: 系统 SHALL 提取按时间排序的考试成绩趋势列表
- **AND**: 系统 SHALL 汇总各题目关联的知识点（Tag），统计各知识点的总分值、学生累计得分与掌握度（百分比），并将掌握度低于 75% 的标签标记为薄弱点（weakTags）
- **AND**: 若学生无已发布考试或无答卷记录，系统 SHALL 返回参测场次为 0 的空态响应，不得抛出 500 或 404 错误

#### Scenario: 靶向练习推荐生成 (ADDED)
- **WHEN**: 学生调用 `GET /api/scores/my/recommendations?limit={limit}`
- **THEN**: 系统 SHALL 获取该学生掌握度最低的薄弱知识点标签
- **AND**: 系统 SHALL 从题库中检索属于该知识点、处于启用状态（status=1）、未删除（is_deleted=0）且属于机判题型（SINGLE/MULTIPLE/JUDGE/FILL_BLANK）的题目
- **AND**: 系统 SHALL 排除学生在错题重练中已消灭的题目
- **AND**: 系统 SHALL 返回带推荐原因（包含知识点名称与当前薄弱掌握度）的推荐题目列表，返回条数不超过 limit（默认 5，上限 10）
- **AND**: 若学生无可推荐题目或题库无匹配题目，系统 SHALL 优雅返回空列表

#### Scenario: 教师查看班级学情与越权防护 (ADDED)
- **WHEN**: 教师调用 `GET /api/scores/classes/{classId}/analytics`
- **THEN**: 系统 SHALL 校验当前教师是否拥有该班级的所有权，非拥有者 SHALL 拒绝访问并抛出 403 错误
- **AND**: 系统 SHALL 聚合该班级所有已发布的考试，计算班级历史平均分趋势与班级各知识点平均掌握度
- **AND**: 系统 SHALL 识别全班掌握度最低的 Top 5 知识点并列入 weakTags
 
+---
+
+### Requirement: 试题区分度与难度评价诊断
+
+系统必须在考试数据分析报告及多 Sheet Excel 导出中，全面引入经典教育测量学（Classical Test Theory）标准模型，将试题难度与基于前 27% 高分组 vs 后 27% 低分组的区分度数值，自动派生为定性等级评价与命题质量诊断建议。
+
+#### Scenario: 试题区分度多档定性评级
+- **GIVEN** 考试已汇总成绩且实考学生数充足（实考人数 ≥ 4 且高低分组各 ≥ 1 人）
+- **WHEN** 教师查询考试分析报告（`GET /api/exams/{examId}/scores/analysis-report`）
+- **THEN** 逐题指标输出数值区分度 `discrimination`（保留 2 位小数）并自动派生 `discriminationLevel`：
+  - $D \ge 0.40$：`优良`
+  - $0.30 \le D < 0.40$：`良好`
+  - $0.20 \le D < 0.30$：`尚可`
+  - $0 \le D < 0.20$：`劣质`
+- **AND** 派生对应的 `qualityEvaluation` 诊断文本（如优良试题推荐入库、区分度偏低建议优化干扰项等）
+
+#### Scenario: 负区分度严重反常预警
+- **GIVEN** 试题出现高分组平均分低于低分组平均分（$D < 0$）
+- **WHEN** 系统生成逐题分析指标
+- **THEN** 区分度评级 `discriminationLevel` 标记为 `反常预警`
+- **AND** `qualityEvaluation` 明确包含警示文案，提示出题人检查题干严重歧义或参考答案错误
+
+#### Scenario: 试题难度系数与分级计算
+- **GIVEN** 试题存在作答人次（`answeredCount > 0`）且满分大于 0
+- **WHEN** 计算逐题指标
+- **THEN** 系统计算难度系数 `difficulty` = 平均分 / 满分（保留 2 位小数）
+- **AND** 派生 `difficultyLevel`：$P \ge 0.70$ 为 `容易`；$0.40 \le P < 0.70$ 为 `中等`；$P < 0.40$ 为 `较难`
+
+#### Scenario: 样本不足时优雅回退
+- **GIVEN** 实考人数少于 4 人或高低分组大小小于 1
+- **WHEN** 生成分析报告或导出 Excel
+- **THEN** `discrimination` 返回 null，`discriminationLevel` 明确标识为 `样本不足`
+- **AND** `qualityEvaluation` 提示样本不足（需至少 4 人实考），系统不产生 NPE 或 500 异常
+
+#### Scenario: 多 Sheet Excel 导出完整包含测量学诊断指标
+- **GIVEN** 归属教师请求导出多 Sheet 分析报告 Excel
+- **WHEN** 触发 `GET /api/exams/{examId}/scores/analysis-report/export`
+- **THEN** 「逐题指标」工作表表头新增难度系数、难度等级、区分度等级与命题建议列
+- **AND** 诊断建议文本严格通过 `ExcelSheetWriter.sanitizeFormula` 处理，防止公式注入




