# spec-delta：add-wrong-question-practice

涉及域：score-management、frontend。

## 域：score-management

### MODIFIED Requirement: 错题本分页查询

既有正文全部维持（考试粒度分页、每页最多 10 场、PersonalReport 现场重算、得分严格
小于满分含 0 分与部分得分判错、解析取自 `questions.analysis`、未发布考试严格隐藏），
追加：错题条目新增 `consecutiveCorrect`（当前连对次数，int）与 `mastered`（是否已
消灭，boolean）字段；列表默认过滤 `mastered=true` 条目，可选查询参数 `includeMastered`
（默认 false）翻回全量；既有判定口径、分页行为、字段映射与解析语义逐字零漂移。

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

#### Scenario: 多选部分对不判答对

GIVEN 一道多选题漏选得部分分（考试判分口径为部分得分）

WHEN 学生以同样答案提交重练

THEN correct=false（答对口径＝满分，与错题判定 score<max 互补）

AND 与既有客观判分核心对该题该答案的判分结果一致（不另造第二套口径）

#### Scenario: 仅本人数据

GIVEN 学生 A 的错题与练习记录

WHEN 学生 B（或未登录态）请求 pool 或提交 A 的 questionId

THEN 仅能访问本人数据，不泄露他人错题与练习状态

---

## 域：frontend

### Requirement: 错题重练界面

WHEN 学生在错题本发起重练,

系统 SHALL 在错题本页提供重练入口与逐题连对进度（x/3）展示；重练模式 SHALL 逐题呈现题干/选项（或填空输入）、提交后即时反馈对错、正确答案与解析、连对进度与消灭态提示；SHALL 以可选参数翻回已消灭条目查看。零新依赖，对齐既有 UI 风格。

#### Scenario: 重练入口与进度展示

GIVEN 错题本页存在未消灭错题

WHEN 学生查看列表

THEN 每条错题显示当前连对进度（x/3）且提供重练入口

#### Scenario: 逐题作答即时反馈

GIVEN 学生进入重练模式并提交一题答案

THEN 即时显示对错、正确答案、题目解析与连对进度

AND 连对第 3 次时显示消灭提示且该题退出后续重练

#### Scenario: 已消灭条目翻回

GIVEN 存在已消灭错题

WHEN 学生选择查看已消灭条目

THEN 列表以 includeMastered 翻回显示且带消灭标识
