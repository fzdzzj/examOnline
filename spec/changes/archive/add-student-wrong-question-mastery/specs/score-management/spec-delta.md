# 规范差异：score-management 域增加错题智能掌握消除与自适应练习卷

> **能力域**：`score-management`  
> **对应变更**：`add-student-wrong-question-mastery`

---

## ADDED Scenarios

### Requirement: 错题智能掌握消除与自适应练习卷

系统必须在既有错题重练与消灭计划机制之上，建立统一的掌握度生命周期三态分级模型，并提供纯内存自适应错题强化组卷（Mock Quiz）与批量交卷评测能力，判分与明细记录完全同源复用既有架构与表结构。

#### Scenario: 错题掌握度三态生命周期分级
- **GIVEN** 学生查询错题本列表（`GET /api/scores/my/wrong-questions`）或重练池（`GET /api/scores/my/wrong-questions/practice/pool`）
- **WHEN** 系统返回题目条目时
- **THEN** 响应中必须显式包含掌握度三态标识 `masteryStatus` 与 `masteryStatusName`：
  - 连对 0 次（`consecutiveCorrect == 0`）：`masteryStatus = 0`，`masteryStatusName = "UNTOUCHED"`（待攻克）
  - 连对 1~2 次（`consecutiveCorrect` 为 1 或 2）：`masteryStatus = 1`，`masteryStatusName = "PRACTICING"`（攻克中）
  - 连对 3 次及以上（`consecutiveCorrect >= 3`）：`masteryStatus = 2`，`masteryStatusName = "MASTERED"`（已掌握）
- **AND** 保持既有 `consecutiveCorrect` 与 `mastered` 字段向下兼容不变

#### Scenario: 自适应强化自测试卷生成
- **GIVEN** 学生拥有未消灭的客观题错题池
- **WHEN** 学生请求生成强化练习卷（`POST /api/scores/my/wrong-questions/practice/generate-quiz`），传入题量 `limit`（区间 [1, 20]，缺省 5）与策略 `strategy`
- **THEN** 系统根据指定策略从本人未消灭错题中抽取题目组卷：
  - `strategy = "RANDOM"` 时：全局随机打乱抽取至多 `limit` 道题
  - `strategy = "PRACTICING_FIRST"` 时：优先抽取处于攻克中（`PRACTICING`，`consecutiveCorrect > 0`）的题目以促成消灭，不足时再抽取待攻克（`UNTOUCHED`）题目
- **AND** 若错题池有效题目数少于 `limit`，则返回实际全部未消灭错题；若错题池为空，则返回总题数为 0 的空试卷
- **AND** 返回结果中包含无状态 UUID `quizId` 与题目列表，作答前严格不泄露正确答案与题目解析

#### Scenario: 强化自测试卷批量交卷与判分
- **GIVEN** 学生完成自测试卷的一组作答
- **WHEN** 学生提交自测答卷（`POST /api/scores/my/wrong-questions/practice/submit-quiz`），传入 `quizId` 与各题作答列表（`questionId` + `answer`）
- **THEN** 系统逐题验证归属于本人错题池，并 100% 复用既有客观判分策略（`GradingStrategyRegistry`）判定正误
- **AND** 逐题向既有 `practice_attempt` 表持久化一条练习记录（零 DDL）
- **AND** 重新计算每道题目的最新连对次数与最新三态掌握度状态
- **AND** 汇总返回本次自测的总题数、答对数、正确率（`scoreRate`）、本次测试中新达成已掌握状态的题目数（`newlyMasteredCount`）及逐题作答结果、标准答案与解析

#### Scenario: 批量提交归属越权与非法参数防御
- **GIVEN** 学生提交的自测题目列表中包含不属于该学生本人已发布考试的错题、或者包含已逻辑删除/不存在的题目
- **WHEN** 调用 `POST /api/scores/my/wrong-questions/practice/submit-quiz`
- **THEN** 系统 SHALL 抛出 `BAD_REQUEST` 异常并阻断提交，严禁非本人错题记录练习日志或修改状态
- **AND** 提交的答案列表为空或参数缺失时 SHALL 明确返回参数校验失败

#### Scenario: 练习重置与消灭回退保持单调性与幂等
- **GIVEN** 某道题目在自测前处于攻克中（`consecutiveCorrect = 2, status = PRACTICING`）
- **WHEN** 在批量自测中该题作答错误
- **THEN** 判分结果中该题答对标识为 false，连对次数清零（`consecutiveCorrect = 0`），掌握度退回 `UNTOUCHED`
- **AND** 若作答正确，连对次数递增为 3，掌握度晋级为 `MASTERED`，`newlyMasteredCount` 计数相应递增
