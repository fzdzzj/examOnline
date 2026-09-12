# examOnline — OpenSpec 规范驱动开发

> 组织方式对齐参考项目 `D:\code\sports\spec`：**一个变更（change）= 一个开发阶段**，阶段实现并验收后合入 `specs/`，变更目录移入 `archive/`。

## 目录结构

```
spec/
├── specs/{capability}/spec.md          # 已实现的规范基线（当前为空）
├── changes/{change-id}/                # 进行中的变更提案
│   ├── proposal.md                     # 为什么/改什么/影响/时间线/风险
│   ├── tasks.json                      # 任务清单（category 按阶段分组 + 周标记）
│   └── specs/{capability}/spec-delta.md # 规范差异（ADDED/MODIFIED/REMOVED）
└── changes/archive/{change-id}/        # 已归档的变更
```

## 当前状态

- 已合入规范（`spec/specs/`）：

| 能力域 | 状态 | 来源变更 |
|---|---|---|
| `authentication`（工程基础 + 认证能力） | 已合入 | `add-project-skeleton`（阶段 1/2）、`add-authentication`（阶段 2/2） |
| `question-bank`（题库 + 组卷，覆盖 paper-assembly 范围） | 已合入 | `add-question-bank`（阶段 3） |
| `exam-management`（考试管理） | 已合入 | `add-exam-management`（阶段 4） |
| `grading`（判分） | 已合入 | `add-grading-score`（阶段 6） |
| `score-management`（成绩管理） | 已合入 | `add-grading-score`（阶段 6） |
| `anti-cheat`（防作弊） | 已合入 | `add-anti-cheat`（阶段 7） |

- 已归档变更（`spec/changes/archive/`）：

| 变更 ID | 阶段 | 内容 | 周期 |
|---|---|---|---|
| `add-project-skeleton` | 阶段 1/2 | 工程骨架（单体工程/统一响应/数据模型/中间件编排） | W1 |
| `add-authentication` | 阶段 2/2 | 用户认证与鉴权（注册/登录/双 Token/黑名单/RBAC/越权/锁定限流/找回/初始化） | W1-W2 |

- 进行中变更：
  - `add-exam-taking`（阶段 5）：任务清单已全部勾选，压测验收留 W6 收口后合入规范；
  - `add-anti-cheat`（阶段 7）：已实施并验收（全量 118 例测试通过），规范已合入（变更目录留待统一归档）；
  - `add-grading-score`（阶段 6）：已实施并验收，规范已合入（变更目录留待统一归档）。

## 能力地图（8 个能力域，作为规范组织单位）

| # | 能力（capability） | 范围 |
|---|---|---|
| 1 | authentication | 注册/登录/双 Token/黑名单/RBAC/越权/锁定限流/找回/初始化 |
| 2 | question-bank | 题目 CRUD、标签、软删除 |
| 3 | paper-assembly | 手动组卷、标签随机抽题、快照锁定 |
| 4 | exam-management | 考试创建/发布、状态机、试卷快照 |
| 5 | exam-taking | 答题界面、倒计时、自动保存、交卷链路 |
| 6 | grading | 客观题判分、简答批改、策略模式 |
| 7 | score-management | 成绩汇总/发布/导出 |
| 8 | anti-cheat | 切屏检测、行为日志、随机抽题/选项乱序 |
| 9 | performance | 缓存三防、慢查询识别 |
| 10 | data-access | 读写分离、读己之写 |
| 11 | observability | 指标导出、自定义指标、慢 SQL |

## 开发阶段 → 变更映射（规划）

| 阶段 | 变更 ID | 能力域 | 对应周 |
|---|---|---|---|
| 1 | add-project-skeleton | authentication（工程基础） | W1 |
| 2 | add-authentication | authentication | W1-W2 |
| 3 | add-question-bank | question-bank + paper-assembly | W2 |
| 4 | add-exam-management | exam-management | W3 |
| 5 | add-exam-taking | exam-taking | W4-W6 |
| 6 | add-grading-score | grading + score-management | W7 |
| 7 | add-anti-cheat | anti-cheat | W8 |
| 8 | add-performance-deepening | performance + data-access + observability | W9-W10 |

## 工作流

1. 一个开发阶段 = 一个变更提案（`changes/{change-id}/`）。
2. 提案审批后按 `tasks.json` 逐步实施（每次处理一个 step）。
3. 阶段完成并验收后，`spec-delta.md` 的需求合入 `specs/{capability}/spec.md`，变更目录移入 `changes/archive/`。

## 参考资料（非 openspec 资产）

- `docs/examOnline需求规格说明书.md` — 完整产品愿景
- `docs/需求决策记录.md` — 80+ 项场景决策（开发逐条对照）
- `docs/面试版实施方案.md` — v3 大厂面试级实施方案（开发蓝本）
