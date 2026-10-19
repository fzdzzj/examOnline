# adaptive-testing 能力域规范基线

> 能力域：自适应测验（数据智能演进，新建于 add-irt-adaptive-testing）。
> 来源：`spec/changes/archive/add-irt-adaptive-testing` 合入（IRT 三参数标定 + CAT 自适应测验会话，2026-10-19 归档）。

---

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

---

> 合入注记（2026-10-19，`add-irt-adaptive-testing`，M 级，**GO**）：数据层——`question_irt_params`（`uk_irt_question` 唯一）与 `cat_quiz_sessions` 两表 + 迁移脚本 `2026-W43-add-irt-adaptive-testing.sql` 入库并登记 APPLIED.md（**未应用待人工**——脚本入库≠迁移已生效，存量库须人工应用后回填台账）；`IrtParamService` CTT→3PL 经验转换（a=0.5+2D 钳制 [0.5,2.5]、b=logit(P) 中值修正钳 [0.05,0.95]、c 按题型固定），CTT 数据源与 C3 同源（`ExamAnalysisReportService.questionMetrics`），合并 `practice_attempt` 样本，合并样本 <4 或缺 D 不入池，按场次 12h TTL 幂等；`IrtEstimator` 3PL 概率 / Fisher 信息量 / EAP 主估计（N(0,1) 先验）+ Newton-Raphson 辅助；`IrtCatService` b 近 0 开局、EAP 即时更新 θ、最大信息量选题排除已答、MAX_ITEMS / SE_CONVERGED / POOL_EXHAUSTED 三终止、判分同源 `GradingStrategyRegistry` 落 `practice_attempt`；三端点归属人隔离（越权 403 / 不存在 404 / 已终止 400），openapi.yaml 107→110 paths + 10 schemas，SDK 重生，`AdaptiveQuizPanel` 接入错题本入口。**口径收敛（指导裁决）**：proposal 原设「教师/管理触发标定端点」收敛为 CAT 开局惰性标定（`calibrateStudentPool`，按场次 TTL 幂等），不设第 4 条 HTTP 路径；TTL 幂等与样本拦截经复核实测。会话正确性落 DB，零新 Redis 键族。测试与门禁：后端 29 例 + 前端 5 例，backendTests 816→845、frontendTests 960→965、openapiPaths 107→110（gate-baseline 三项同笔上调）；变异 m1（抽除 θ 更新）/ m2（抽除最大信息量选题）/ m3（抽除终止规则）全部断言级击红并回绿零残留（复原 SHA256 逐字节一致）。独立复核 7 PASS / 1 FAIL（evidence 清单 CRLF 入库致 `sha256sum -c` 失败，收口前已转 LF 并复验 20/20 OK）。提交链：8250b71 立案 → 85365ae 数据层 → 1168808 引擎 → b017744 用例 → 2f6f6b7 契约/前端 → ee79764 证据/基线。
