# agent-harness 规范增量（spec-delta）

## 域：agent-harness

### Requirement: 规范台账冷热分离

WHERE 规范系统的已归档变更条目随项目演进持续增长,
规范根入口 `spec/README.md` SHALL 仅保留进行中的变更、能力地图基线索引与近期归档速览，并将历史全量归档清单剥离至独立的冷存储文件 `spec/CHANGES_ARCHIVE.md`。

#### Scenario: Agent 读取 spec 根入口

GIVEN 任何新接手或日常工作的 Agent 读取 `spec/README.md` 获取当前项目状态
WHEN 它读取该文件
THEN 文件的文本体积不得超过 25 KB
AND 它能直接查阅当前正在进行的变更与各能力域的最新落点
AND 它无需在同一会话中被迫读入数十万字符的历史已归档变更文本

#### Scenario: 查阅历史已归档变更

GIVEN 用户或 Agent 需要溯源某个已归档历史变更的详细实施笔、复核依据与覆盖范围
WHEN 它查阅规范台账
THEN 它能通过 `spec/README.md` 中的指针跳转至 `spec/CHANGES_ARCHIVE.md`
AND `spec/CHANGES_ARCHIVE.md` 完整保留历史变更的全部归档元数据与实施结论

---

### Requirement: 派工协议轻量化与分级流转

WHEN 主 Agent 为子 Agent 编制任务提示词或规划执行流程,
系统 SHALL 依据改动范围实行分级流转，且单次派工提示词不得在会话正文中复制仓库既有规则的全文副本。

#### Scenario: S 级轻量任务一票到底

GIVEN 一个仅涉及文档整理、配置微调或改动少于 5 个文件且无破坏性业务逻辑的轻量任务（S 级）
WHEN 主 Agent 规划该任务
THEN 允许实施 Agent 在完成先红后绿自测与验证后直接合并收口
AND 无需强制拆分为实施、独立复核、独立收口三个独立的子 Agent 会话

#### Scenario: 派工提示词不得粘贴全量规则副本

GIVEN 主 Agent 向子 Agent 发出派工提示词
WHEN 派工卡成文
THEN 其字数限制在 300 字以内，仅声明目标变更路径、执行 step、受控白名单与关键验收断言
AND 不得在 Prompt 中整段复述 `AGENTS.md` 四条硬约定、变异五步法或八段式报告全文
AND 约束规则统一由子 Agent 直接读取版本库对应文件获取

---

### Requirement: 评审产物与历史过程文档隔离

WHERE 持续交付过程中产生独立复核判定文件（VERDICT）与历史过程性交接文档,
系统 SHALL 将其隔离至专用的评审归档目录，严禁长期散落于仓库根目录污染工作树。

#### Scenario: 评审 VERDICT 文件不得污染工作树根目录

GIVEN 复核子 Agent 或自动化流程生成 `review-*-VERDICT.md`
WHEN 评审产物落盘
THEN 文件应输出至 `_review/verdicts/` 或变更 evidence 目录下
AND 仓库根目录在 `git status` 下不得散落未跟踪的 VERDICT 判定文件

#### Scenario: 历史过程交接文档冷归档

GIVEN 过去某日期（如特定月份或开发冲刺）的过程性交接日志
WHEN 阶段已收口且结论已合入基线
THEN 该过程文档应归入 `docs/archive/historical-handover/`
AND 不得与当前的活动规范或执行指南在同一目录下并列，避免 Agent 误读过期结论
