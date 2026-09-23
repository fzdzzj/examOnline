# 子 agent 提示词 —— `add-frontend-exam-admin` **缺口补齐**（阶段 21 返修，非首轮）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/add-frontend-exam-admin/proposal.md`、`tasks.json`、`specs/frontend/spec-delta.md`，
> 再读 `spec/README.md` 的遗留 #13（本提示词的全部前提来自那里）。

## 为什么是"补齐"而不是"重做"

阶段 21 的**已有入库部分不要动**：`(dashboard)/teacher/classes/index.page.vue`（班级 CRUD + 学生列表）、
`(dashboard)/teacher/exams/{index,create}.page.vue`（考试列表/创建/发布/force-end）、
`src/constants/examStatus.ts`（4 个引用方）。提交为 `2789f4b` → `39c7fbf` → `4861847`。

2026-09-21 指导 agent 逐条复算确认**五项无代码**，本提示词只做这五项：

| # | 缺口 | 一手证据（可自行复跑） |
|---|---|---|
| 1 | 学生入班 / 转班 / 移出 | `classes/index.page.vue` 内有"本阶段不展开"注释；`git grep -n transfer -- frontend/src` 在生成式 SDK 之外零调用者 |
| 2 | 考试详情（快照预览 + 考生名单 + 提交进度） | 无 `exams/[id].page.vue`；`git grep -n -e getSnapshot -e generateSnapshot -- frontend/src` 手写代码零调用 |
| 3 | 监考视图（进度） | `git grep -n overview -- frontend/src` 零调用；`src/constants/monitor.ts` **零 importer** |
| 4 | 行为日志时间线 | `timeline` / `page4` 零调用；`src/constants/severity.ts` **零 importer** |
| 5 | Grafana 只读入口 | `git grep -i grafana -- frontend` 命中 0 |

外加一项硬要求：**阶段 21 目前 0 个测试文件**，而其 `tasks.json` 任务 #4 明确要求测试。

## 开工基线（先跑，再动手；这三个数就是本阶段的判据基准）

```
npm run lint:check          → exit=0
npm run type-check:check    → exit=0（注意：此门禁在 2026-09-21 之前是哑的，见 dab74cf）
npm run test（vitest run）   → Test Files 16 passed / Tests 126 passed
后端全量 mvn -o clean test   → Tests run 276 / Failures 0 / Errors 0 / Skipped 1（BUILD SUCCESS）
```
把你在**开工时**实际跑出的四个数字连同命令与当时短 revision 写进回报。**不要引用上面别人的数字**——跑你自己的。
收尾判据：`lint:check` 与 `type-check:check` 仍 exit=0、vitest 用例数**不得少于**你开工时记录值、后端计数不降。

## 硬边界（违反即返工）

1. **不改后端**：`src/main/**`、`src/test/**`、`pom.xml`、`openapi.yaml`、`docker/**` 零改动。
   发现接口缺口（缺端点、缺字段、返回结构不够用）**停下回报**，不要在前端拼凑绕过、不要 mock 掉当作已完成。
   特别注意：契约类型存在于 `src/api/axios/*.gen.ts`，但 `openapi.yaml` 落后于后端时**停下回报**，不要手写补丁类型。
2. **不改 `frontend/package.json` 的 scripts**，不改 `vitest.config.ts`，不改 `src/router/*` 以外的既有路由语义。
   新页面走文件路由（`(dashboard)/teacher/exams/[id].page.vue` 形态），由 `unplugin-vue-router` 自动生成路由，
   **不要手改 `typed-router.d.ts`**（它是生成物）。
3. **两个零引用常量文件（`monitor.ts` / `severity.ts`）优先接上而不是删掉**：它们本来就是为监考视图与
   行为日志时间线准备的。只有在确认其内容与实际后端返回不符时才改写，并在回报里说明改了什么、依据哪个端点。
4. **Grafana 入口只做只读跳转**，不内嵌假数据、不伪造面板 URL；宿主端口等环境事实**取指针**
   （`docs/指导Agent交接文档.md` §6 与 `docker-compose.yml`），本提示词不复制端口数字，你也不要往代码里写死端口。
   注意 compose 里 `exam-grafana` 的 profile 是 `no`（不随 `up` 启动），入口要能在 Grafana 未起时给出可读的降级提示，
   **不要把"打不开"渲染成"无数据"**。
5. 状态与判定一律取后端返回：考试状态机（`status` + `published`）、提交进度、行为日志 severity 分级都不得前端自造。
6. 不要 commit、不要勾 `tasks.json`。不要改 `spec/**` 与 `docs/**`。

## 交付要求

- 五项缺口各自可演示：入班/转班能真跑一次；考试详情页能显示快照、考生名单、提交进度；
  监考视图能看到进度并按后端状态渲染；行为日志时间线能按 severity 上色并来自后端；
  Grafana 入口是只读链接且有未起时的降级提示。
- **每项都要有 vitest 用例**，至少覆盖：转班（含"学生不属于原班级"的失败分支）、
  考试详情的空态与错误态、监考进度轮询取到的数据以后端为准（不要断言前端推算值）、
  severity 未知值时的渲染兜底。
- 真机验证至少各走一条：登录（教师）→ 班级 → 学生入班 → 转班 → 移出；建考试 → 发布 → 进详情 →
  看名单与进度 → 看监考视图 → 看行为日志时间线 → 点 Grafana 入口（未起时看到降级提示）。
  **把每步真实请求路径与 HTTP 状态记进回报**；后端起不来就停下回报，不要用 mock 冒充真机通过。
- 回报里明确列出：你**没有**做到什么、哪些接口缺口逼你停下、哪些断言你只做到了组件级而没做到真机级。

## 停止条件

- 基线不绿（lint / type-check / vitest / 后端任一不满足上面判据）→ 停下回报，不要在坏底座上继续。
- 需要新增或修改后端端点才能完成某一项 → 停下回报该项，其余继续做，**不要在前端伪造**。
- 同一问题修复尝试满 2 次仍失败 → 停下回报该 item。
