# 提案：IRT 项目反应理论参数标定与 CAT 自适应测验引擎（add-irt-adaptive-testing）

> **变更 ID**：`add-irt-adaptive-testing`
> **能力域**：`adaptive-testing`（新建能力域）
> **级别**：M 级数据智能深水区特性（IRT 三参数模型 + 能力值 θ 估计 + 最大信息量选题 + 终止规则）
> **前置依赖**：`add-student-wrong-question-mastery`（已合入 main，掌握度三态与练习卷基线）
> **影响面**：新增 IRT 标定与 CAT 会话服务、`schema.sql` 新表、`docker/mysql/migrations/`、`openapi.yaml`、前端自适应测验面板

---

## 1. 为什么做（Why）

- **业务痛点**：
  1. C4 的 `generateQuiz` 只有 `RANDOM` / `PRACTICING_FIRST` 两种与考生能力无关的固定策略，"自适应"名不副实；
  2. C3 已沉淀 CTT 口径的难度系数 P 与区分度 D 值（教育测量经典理论指标），但练习侧完全未消费这些测量学资产；
  3. 自适应学习（按能力动态出题、越练越准）是教育系统的顶级产品叙事，当前缺失工程载体。
- **治理目标**：
  完成教育测量学叙事三级跳的最后一跳：**CTT 指标（C3）→ 掌握度三态（C4）→ IRT/CAT（本案）**，落地真·计算机化自适应测验（Computerized Adaptive Testing）引擎，纯算法零外部依赖。

---

## 2. 改什么（What）

### 2.1 IRT 题目参数标定（离线侧）

- 新表 `question_irt_params`：题目 IRT 三参数持久化（`a` 区分度、`b` 难度、`c` 猜测系数），含标定时间与样本量；
- **CTT→IRT 经验转换初始化**：`a` 由既有区分度 D 经验映射、`b` 由难度系数 P 经 logit 变换 `b = ln(P/(1-P))`（中值修正防发散）、`c` 按题型固定（单选 0.25 / 判断 0.50 / 其余 0）；
- 参数刷新端点（教师/管理触发，`exam:manage`）：基于历史作答（`practice_attempt` + 考试答题数据）重算 CTT 指标后转换；样本不足的题目标记不可用，不进 CAT 题池。

### 2.2 CAT 测验会话（在线侧）

- 新表 `cat_quiz_sessions`（会话归属、θ 轨迹、已答题目集、终止原因）——**会话正确性落 DB 而非 Redis**（runbook 口径：Redis 是加速器不是正确性后盾，本案零新 Redis 键族）；
- `IrtCatService` 核心引擎：
  - **θ 能力估计**：EAP（期望后验）为主、Newton-Raphson 数值迭代为辅，每次作答后即时更新；
  - **选题**：Fisher 信息量 `I(θ)` 最大化（排除本会话已答题，仅从已标定可用题池取）；
  - **终止规则**：`SE(θ) < 0.30`（缺省，可配）或达最大题数（缺省 10，可配 1~20）或可用题耗尽；
  - **开局**：`b` 最接近 0 的中等难度题。
- **判分同源**：逐题作答复用既有 `GradingStrategyRegistry` 判分并落 `practice_attempt`，零口径分叉，与掌握度三态/错题本天然联动。

### 2.3 接口契约与前端

- `POST /api/scores/my/adaptive-quiz/start`：开局返回首题 + 会话信息（不泄露答案解析）；
- `POST /api/scores/my/adaptive-quiz/{sessionId}/answer`：提交单题 → 返回判定 + 下一题（或终局信号）；
- `GET /api/scores/my/adaptive-quiz/{sessionId}/report`：能力报告（θ、SE、正确率、终止原因、逐题轨迹），仅会话归属人可查；
- `openapi.yaml` 107→110 paths 及对应 schemas；
- 前端：自适应测验面板（逐题作答流、能力值实时反馈、终止能力报告），接入学生练习入口。

---

## 3. 约束与硬红线（Guardrails）

- **AGENTS.md 四条硬约定全套适用**：`schema.sql` 唯一建表入口（`AUTO_INCREMENT` 块内显式 `PRIMARY KEY (id)`，H2/MySQL 双兼容）+ `docker/mysql/migrations/` 迁移脚本 + `APPLIED.md` 台账登记（脚本入库≠迁移已生效）；集成测试禁 `@Sql`；实体字段与建表双向一致（过 `comm -23`）；存量库改表人工应用。
- **Redis 口径**：本案设计为零新 Redis 键族；若实施中发现必须引入，须先按 `docs/redis-dependency-runbook.md` 登记键族矩阵再写代码。
- **判分同源**：CAT 作答不得另起判分口径，必须复用 `GradingStrategyRegistry`。
- **双端门禁**：TDD 先红后绿、三变异断言级击红回绿零残留、双端门禁实测全绿、`gate-baseline.json` 同笔上调。

---

## 4. 变异计划

- **m1**：抽除 θ 更新（作答后 θ 恒为初值）→ 选题序列与能力报告断言击红；
- **m2**：抽除最大信息量选题（退化为题池顺序取题）→ 选题断言击红；
- **m3**：抽除终止规则（SE 收敛/达最大题数仍不终止）→ 终止与报告断言击红。

---

## 5. 时间线

阶段 1 立案（本笔）→ 阶段 2/3 实施 + TDD + 三变异 + 双端门禁（执行 Agent）→ 阶段 4 独立复核 + 基线合入 + 归档合流（主 Agent）。
