# 提案：错题智能掌握消除与自适应练习卷（add-student-wrong-question-mastery）

> **变更 ID**：`add-student-wrong-question-mastery`  
> **能力域**：`score-management`（含前端增强）  
> **级别**：M 级学生闭环与业务创新特性（掌握度三态化 + 错题强化自测组卷与判分 + 零 DDL）  
> **前置依赖**：`add-exam-question-discrimination`（已合入 main）  
> **影响面**：`StudentPracticeService`、`ScoreController`、`WrongQuestionItem`、`PracticePoolItem`、`PracticeSubmitResponse`、`wrong-questions/index.page.vue`、`openapi.yaml`

---

## 1. 为什么做（Why）

- **业务痛点**：
  1. **掌握度状态粗粒度二元化**：目前错题只有 `mastered: boolean`（已消灭/未消灭）与裸数字 `consecutiveCorrect`，缺乏直观、显性的掌握度生命周期阶段划分（未练习/攻克中/已掌握），学生在错题本中无法一眼看出哪些题目正在攻坚、哪些题目处于初发未练状态；
  2. **缺乏沉浸式集中自测试卷能力**：目前系统仅支持在错题本或重练池中逐题单题提交作答，无法模拟“错题自测卷”进行沉浸式限时/集中练习。学生迫切需要“一键强化自测”，按需抽取 5~20 道错题组成临时练习套卷，批量作答、集中提交、实时出分并展示本轮自测的正确率与新消灭题数；
  3. **自测组卷策略单一**：缺乏根据掌握进度自适应选题（如优先抽取攻克中 `PRACTICING` 题目促进快速消灭，或全局随机抽样巩固）。
- **治理目标**：
  建立显性的错题掌握度三态生命周期模型（`UNTOUCHED` 待攻克 / `PRACTICING` 攻克中 / `MASTERED` 已掌握），并提供纯内存无状态、零 DDL 的“一键自适应错题强化组卷与批量交卷评测”闭环，显著提升学生错题复习效率与学习成就感。

---

## 2. 改什么（What）

1. **掌握度生命周期三态分级模型（Mastery Status Lifecycle）**：
   - 明确定义三态状态机：
     - `0 / UNTOUCHED`（待攻克）：连对次数 `consecutiveCorrect == 0`（从未练习过，或最近一次练习答错重置）；
     - `1 / PRACTICING`（攻克中）：连对次数 `consecutiveCorrect` 为 1 或 2（正在向消灭目标冲刺）；
     - `2 / MASTERED`（已掌握）：连对次数 `consecutiveCorrect >= 3`（达成消灭标准出本）。
   - 在 `WrongQuestionItem`、`PracticePoolItem`、`PracticeSubmitResponse` 中扩充 `masteryStatus`（Integer: 0, 1, 2）与 `masteryStatusName`（String），向后兼容保留既有 `consecutiveCorrect` 与 `mastered` 字段。

2. **错题强化组卷自测（Mock Practice Quiz）与批量判分（零 DDL）**：
   - **自测生成端点**：`POST /api/scores/my/wrong-questions/practice/generate-quiz`
     - 请求体：`limit`（抽题量，默认 5，范围 [1, 20]），`strategy`（抽题策略：`RANDOM` 随机，`PRACTICING_FIRST` 优先攻克中）；
     - 逻辑：从本人未掌握客观错题池中按策略抽取题目，组装轻量临时试卷（附带无状态生成的 UUID `quizId`），题目信息中严格不泄露正确答案与解析；错题不足 limit 时按实际数量返回，错题池为空时返回 0 题。
   - **批量交卷判分端点**：`POST /api/scores/my/wrong-questions/practice/submit-quiz`
     - 请求体：`quizId`、`answers`（题目作答列表：`questionId` + `answer`）；
     - 逻辑：
       - 逐题验证归属于本人已发布考试错题池（`isWrongQuestionOf` 门控）；
       - 逐题同源复用既有客观判分策略（`GradingStrategyRegistry`）；
       - 逐题插入 `practice_attempt` 记录（严格复用既有表与字段，零 DDL）；
       - 重新计算各题最新连对次数与掌握度三态；
       - 返回本次自测总题数、答对数、正确率（`scoreRate`）、本次新消灭题目数（`newlyMasteredCount`）及逐题作答对错、标准答案与题目解析。

3. **前端呈现与交互增强**：
   - `frontend/src/pages/(dashboard)/student/wrong-questions/index.page.vue`：
     - 题目卡片展示三态彩标 Tag（待攻克：橙/灰，攻克中：蓝色并标注当前连对 1/3、2/3，已消灭：绿色）；
     - 顶部新增「🎯 错题强化自测」按钮，弹出配置模态框选择题量与策略；
     - 提供答题卡导航与批量提交交互，交卷后展示自测总览结果与逐题正误解析。

4. **API 契约与基线同步**：
   - `openapi.yaml` 登记两个新端点（paths 102 → 104）及相关 DTO schemas；
   - `gate-baseline.json` 同步上调 `openapiPaths`（102 → 104），`backendTests` 与 `frontendTests` 随新增测试单调上调。

---

## 3. 约束与硬红线（Guardrails）

- **零 DDL**：严禁修改 `schema.sql`，严禁新增数据库迁移脚本，自测试卷完全无状态、练习明细完全复用既有 `practice_attempt` 表；
- **判分口径唯一性**：自测判分必须 100% 严格复用既有客观判分核心策略（`GradingStrategyRegistry`），得满分才判正确，严禁另起炉灶；
- **本人数据隔离**：必须通过 `SecurityUtil.getUserId()` 严格隔离，严禁越权生成或提交非本人的错题；
- **双端门禁与变异验证**：
  - 新增后端单元测试与集成测试，覆盖组卷策略、批量判分、并发与边界、三态流转；
  - 新增前端组件交互测试；
  - 变异验证三连击（m1 抽除策略选题 / m2 抽除掌握度三态推导 / m3 抽除批量交卷归属校验）必须全部断言级击红并回绿留证。
