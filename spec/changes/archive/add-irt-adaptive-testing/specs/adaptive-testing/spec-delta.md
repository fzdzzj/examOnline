# 规范差异：adaptive-testing 域新建 IRT 参数标定与 CAT 自适应测验

> **能力域**：`adaptive-testing`
> **对应变更**：`add-irt-adaptive-testing`

---

## ADDED Requirements

### Requirement: IRT 题目参数标定

系统 SHALL 以三参数 Logistic 模型（3PL）为题目维护 IRT 参数（`a` 区分度、`b` 难度、`c` 猜测系数）；参数 SHALL 由 CTT 口径指标（难度系数 P、区分度 D）经验转换初始化并可基于历史作答刷新；标定结果 SHALL 持久化且 SHALL NOT 改变既有判分、掌握度与错题本口径。

#### Scenario: CTT→IRT 参数转换与持久化

- **GIVEN** 某题已有基于历史作答的难度系数 P 与区分度 D（样本充足）
- **WHEN** 学生发起 CAT 测验开局，触发该学生已判分考试的惰性标定（`calibrateStudentPool`，按场次 12h TTL 幂等；不设独立管理端点——指导裁决收敛）
- **THEN** 系统按经验映射产出 `a`/`b`/`c` 三参数并持久化（含标定时间、样本量与来源场次）
- **AND** 单选题 `c` 固定 0.25、判断题固定 0.50、其余题型固定 0
- **AND** 样本不足的题目被标记为不可用，不进入 CAT 题池

#### Scenario: 标定不侵入既有口径

- **GIVEN** IRT 参数标定已完成
- **WHEN** 学生正常练习、考试与错题回顾
- **THEN** 既有判分结果、掌握度三态推导与错题本口径逐字零改动

---

### Requirement: CAT 自适应测验会话

CAT 会话 SHALL 以能力值 θ 驱动逐题施测：开局取 `b` 最接近 0 的题；每次作答后 SHALL 即时更新 θ（EAP / Newton-Raphson）；下一题 SHALL 为未作答题中 Fisher 信息量 `I(θ)` 最大者；会话 SHALL 在 `SE(θ)` 收敛、达最大题数或可用题耗尽时终止并产出能力报告。

#### Scenario: 会话开局与首题选取

- **GIVEN** 学生发起 CAT 测验且已标定题池非空
- **WHEN** 调用 `POST /api/scores/my/adaptive-quiz/start`
- **THEN** 建立会话并返回 `b` 最接近 0 的中等难度首题
- **AND** 响应不泄露正确答案与解析，会话仅归属人可用

#### Scenario: 作答后能力更新与动态选题

- **GIVEN** 会话进行中，当前能力估计为 $\theta_t$
- **WHEN** 学生经 `POST /api/scores/my/adaptive-quiz/{sessionId}/answer` 提交本题作答
- **THEN** 系统即时更新 θ 并以最大 Fisher 信息量选题返回下一题（排除本会话已答题）
- **AND** 答对情形下的下一题期望难度不低于答错情形（θ 上调使选题向更高 `b` 移动）
- **AND** 逐题作答同源复用 `GradingStrategyRegistry` 判分并落 `practice_attempt`

#### Scenario: 终止规则与能力报告

- **GIVEN** 会话进行中
- **WHEN** `SE(θ) < 0.30`（缺省，可配）或已达最大题数（缺省 10，可配 1~20）或可用题耗尽
- **THEN** 会话终止，作答端点返回终局信号
- **AND** `GET /api/scores/my/adaptive-quiz/{sessionId}/report` 返回能力报告（θ、SE、正确率、终止原因、逐题轨迹）
- **AND** 报告端点仅会话归属人可查，越权按既有口径拒绝
