# optimize-agent-workflow-and-specs：Agent协作流轻量化与规范文档冷热分离

## 背景与必要性

在近期协作实践（包括 TraeWork 真实多轮开发记录）与全仓资产普查中，指导主 Agent 与子 Agent 协作体系以及 Agent 知识库面临两大严重瓶颈：

1. **协作链路仪式感过重与算力浪费（Human Middleware & Redundant Execution）**：
   - 无论功能规模大小，均机械套用「实施子 Agent → 复核子 Agent → 收口子 Agent」的三段式瀑布。
   - 用户被迫沦为复制粘贴 3,000~4,000 字巨型 Prompt 与数千字长文汇报的“人肉中继”。
   - 一个微小的增量变更（如仅修改 12 个文件、增量代码 200 行），本地 `mvnw clean test`（每次 5 分钟）与前端门禁（2.5 分钟）在实施、复核阶段重复全量跑，加上 CI 远端双轮验证，单案等待测试时间超 20 分钟。
2. **文档知识库严重臃肿与信息多头冗余（Doc Sprawl & Token Bloat）**：
   - 全仓 Markdown 文件多达 401 个、超 3.1 MB。
   - `spec/README.md` 单文件膨胀至 250.4 KB（但仅 308 行），每行归档长达 1,000~2,500 字符，堆满 80+ 个历史变更。每次新 Agent 接手仅读该文件便消耗 60,000+ tokens。
   - `docs/` 下沉淀 5 份按日期命名的历史交接快照（86 KB）与 6 份未跟踪的候选草稿（145 KB，其实际内容早已被 `docs/功能待办清单.md` 勘误与吸收）；根目录散落 13 份未跟踪 `review-*-VERDICT.md`，工作区信噪比极低。
   - `docs/project-overview-for-agents.md` 与根目录 `README.md` 的真源表格存在 80% 重复，且带有“时点：2026-09-21”的陈旧过时段落。

## 现场核证基础（2026-10-14 核证）

- 规范台账：`spec/README.md` 250.4 KB，包含 80+ 笔归档历史条目（自阶段 1 至今），单行最长 2,596 字符；新 Agent 必读导致上下文浪费严重。
- 历史文档：`docs/指导主Agent交接文档-2026-*.md` 5 份（09-30 至 10-10）均为历史过程日志，无即时路由价值；未跟踪草稿 `docs/*candidates*.md` 6 份已脱离真源。
- 评审文件：根目录 13 份 `review-*-VERDICT.md` 未纳入忽略亦未归位，持续污染 `git status`。
- 入口重合：`docs/project-overview-for-agents.md` 表格 35 行与 `README.md` 表格 21 行重叠，且含未收口前端 19-23 阶段的过期陈述。
- 门禁基线：后端 470 passed, Skipped 1 / 前端 667 passed / openapiPaths 89。本案为纯文档/工作流优化，门禁与契约保持 100% 零漂移。

## 设计裁决

1. **规范台账冷热分离（Cold-Hot Storage Split）**：
   - 创建 `spec/CHANGES_ARCHIVE.md` 作为冷存储，全量迁移历史 80+ 笔归档变更详细记录。
   - 主入口 `spec/README.md`（热入口）保留：目录结构、进行中变更表、能力地图与落点定位、工作流纪律，以及近期 5 笔归档速览与指向 `spec/CHANGES_ARCHIVE.md` 的超链接指针。
   - `spec/README.md` 体积由 250 KB 降至 15 KB 以内（降幅 >94%），严格遵守 `AGENTS.md` 不含易变事实常量的约束。
2. **协作模式分级流转（Tiered Workflow）**：
   - 在 `docs/主Agent执行指南.md` 固化任务分级：
     - **S 级（轻量案）**：改动 < 5 个文件、残差修补、纯文档/配置调整，由实施 Agent 跑完自测后一票到底直接合流，免除单独派发复核与收口 Agent；
     - **M/L 级（复杂案）**：涉及并发、破坏性 DDL、安全与核心结算链路，保留实施与独立复核隔离；收口环节通过轻量脚本或标准流程一步合入，消灭独立收口 Agent。
3. **派工协议标准化与轻量化（Spec-Driven Lightweight Dispatch）**：
   - 派工提示词字数严格限制在 300 字以内，采用参数化短卡（变更 ID、目标 step、修改白名单、断言要点）。
   - 禁止在聊天窗口中全量复述 `AGENTS.md` 四条硬约定、变异五步等仓库已入库规则，改为由子 Agent 直接引用仓库文件。
4. **历史碎片归档与工作区隔离**：
   - 创建 `docs/archive/historical-handover/`，将 5 份历史日期交接文档与 `项目总览-2026-09-30.md` 归档。
   - 清理/归档未跟踪的 `docs/*candidates*.md` 草稿文件。
   - 将根目录未跟踪的 13 份 `review-*-VERDICT.md` 统一收纳到 `_review/verdicts/`，并在 `.gitignore` 中配置保护，使工作树回归整洁。
5. **消除入口重复并更新架构速查卡**：
   - 优化 `docs/project-overview-for-agents.md`，删除与 `README.md` 重复的真源大表，移除过期时点及历史未收口假象，保留高密度 L1/L2/L3 架构标准与核心业务主线。
6. **零业务破坏性保证**：
   - 零 Java 代码修改、零前端代码修改、零 DDL，保持双端门禁用例数与契约路径数不变。
