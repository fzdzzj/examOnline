# survey-frontend-candidates-r2 提案（前端第二轮只读巡查）

> 立项依据：上一轮完善台账（`docs/frontend-improvement-candidates.md`）终态于 `3b7783e`（2026-10-07），此后 main 新合入 9 个前端变更，需一次系统性只读巡查复核新变更面、能力缺口与需求对照状态。本卡以只读方式产出下一批前端候选。**零代码改动；候选 ≠ 已确认缺陷 ≠ 已批准变更。**

## Why

1. 前端 9 个新变更（`add-frontend-exam-edit-delete` / `fix-frontend-query-failure-states` / `fix-frontend-list-truncation-family` / `add-frontend-invite-code-admin` / `fix-frontend-makeup-time-and-landing` / `add-frontend-submission-rejudge` / `add-frontend-manual-score` / `add-exam-list-filtering` / `add-frontend-audit-logs-kick`）合入 main 后，能力缺口（openapi×sdk×引用）与 UX 边界（逐页五查）都需在当次 revision `608adf5` 现场复核；
2. 上一轮 20 个零引用 SDK 函数的剩余 11 个零引用项需逐个照上一轮 (a)/(c)/O-1 判定复核（判定仍成立即记「复核、结论不变」，不重报）；并核 `add-exam-list-filtering` 后端新参数（GET /api/exams title/status）是否带来契约/消费面变化；
3. 需求规格说明书 §2.1 管理员角色在 F-4 两阶段闭环后的状态需更新，上轮「部分覆盖/整体未实现」清单逐条复核。

## What Changes

- 产出（**不入库纪律文件**）：`docs/frontend-improvement-candidates-r2.md`——本轮前端完善候选台账，头注绑定 revision，候选编号延续 F-5 / U-6 / T-3 / O-2。
- 入库（单笔收口提交）：`spec/changes/survey-frontend-candidates-r2/`（proposal.md + evidence/）+ `spec/README.md` 归档登记行。
- **零改动承诺**：`frontend/src`、`src/main`、`src/test`、`pom.xml`、`schema.sql`、`openapi.yaml` 一行不改；未启动 dev server、未跑前端构建、未跑真实 Chromium、未碰 Docker/共享 dev。

## 巡查面（普查不抽查）

1. **面一·能力缺口**：`openapi.yaml` paths × `sdk.gen.ts` 函数 × 全仓引用（含测试）三层对照；确定性脚本解析 `import {…} from '@/api/axios'`（含 as 别名、多行、type 前缀）+ 词法 tokenizer 统计本地绑定全仓真实使用。零引用判定为「连测试都没引用」的强口径。剩余零引用项逐条对照上轮 (a)/(c)/O-1 复核；核 U-3 新参数消费面。
2. **面二·UX 边界**：遍历全部 27 个 `.page.vue` + 关键组件做五查（空态 / 加载态 / 错误反馈 / 表单校验 / 危险操作确认）；9 个新变更面为重点，其余既有页面网格复核。
3. **面三·需求对照**：`docs/examOnline需求规格说明书.md` §2.1/§2.2 复核；F-4 管理员角色状态更新；上轮「部分覆盖/整体未实现」清单逐条复核。
4. **性能维度**（模式级抽查）：9 个新变更面是否引入「无分页全量拉取 / 缺防抖 / 轮询泄漏 / deep watch / 逐键请求」反模式，不做六面普查。

## 本轮结论（候选 2 项 + 观察 1 项）

- **U-6**（面二）：`teacher/exams/index.page.vue:272-292` 列表/搜索查询失败无 error 反馈，静默落空态（本页 3 处 Alert 均在确认弹窗，非查询错误）；`add-exam-list-filtering` 改造后失败窗口/误导面更大，属 U-1（已收口四处）在该页残留。纯前端可闭环，风险中。
- **U-7**（面二）：`teacher/exams/create.page.vue:181-224` 编辑模式回填（detail2）失败静默、表单停默认值、全文件 0 Alert，存在默认值误写真实考试风险；`add-frontend-exam-edit-delete` 引入。纯前端可闭环，风险中。
- **O-2**（面一观察）：单题入卷端点 `addQuestion` 全仓（含测试）零 SDK 实引——`onPick` 自 `add-paper-batch-add-questions` 改走批量 `addQuestions` 后取代；第 1 轮零引用表漏记。观察项，不立实现。
- 上轮剩余 11 个零引用项（detail/detail3/getSnapshot/generateSnapshot/paper/replay/progress/export×4）全部复核判定仍成立，记「复核、结论不变」不重报；U-3 两个新参数均被消费，无缺口。

## Impact

- 运行时影响：零（纯静态只读；不启动 dev server、不跑构建、不碰 Docker/共享 dev）。
- 门禁：预期四计数与基线一致（后端 374/0/0/1、前端 63 文件 490 例），本卡零改动、计数不变；收口前复跑为凭据。
- 规范：零合入；任何候选后续实施须各自另立卡走独立 delta。

## 验收与停止条件

- **验收**（全部满足才算完成）：台账成文且头注绑定当次 revision；逐候选锚点可复验（优先 grep 锚点，行号仅辅助并绑定 revision）；三面各有「查了什么、怎么查」记录；含「已排除项」节；零改动凭据 `git diff --name-only <开工HEAD> HEAD -- src pom.xml frontend schema.sql openapi.yaml` 输出为空；门禁四计数与基线一致；evidence-sha256 覆盖全部证据文件；单笔收口提交；按固定清单回传（验收记录三要素：命令 + 当次输出 + 短 revision）。
- **停止条件**（触发即停、如实上报、不自行扩张）：①发现违反既有 spec Scenario 的行为缺陷——非巡查候选，停止上报（由指导主 agent 另立修复卡）；②门禁复跑红或四计数与基线不一致；③疑点需运行时/构建证据才能判定——登记「待测量」，不为启动构建；④工作树出现本卡之外意外改动。
- **回传固定清单**：① `git status` 终态原文；② 候选总数与三面分布；③ 台账路径与 sha256；④ 逐条候选一行摘要（编号+锚点+疑点+最小下一步）；⑤ 门禁当次完整计数与退出码（后端四计数 + 前端三项）；⑥ 收口提交短 SHA 与提交信息；⑦ evidence 文件清单。回传后由指导主 agent 独立复核裁决（子 agent 回报不是事实）。