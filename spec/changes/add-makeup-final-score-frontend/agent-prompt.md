# 子 agent 提示词 —— `add-makeup-final-score-frontend`（补考最终成绩前端展示，P3）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/add-makeup-final-score-frontend/proposal.md`、`tasks.json`、`specs/frontend/spec-delta.md`，再读仓库根 `AGENTS.md` 与 `spec/specs/frontend/spec.md`（frontend 基线——「缺考与补考界面」Requirement 由本变更改写）。
> **属前端变更：零后端改动**（`src/main`、`src/test`、`pom.xml` 一行不动）。发现接口缺口停下回报，不得前端变通。

---

## 现状

- 仓库主工作树 `D:\code\examOnline`；你在主工作树或独立工作树干活（指导 agent 指定；开工 `git rev-parse HEAD` + `git status --short` 自检；若在独立工作树则分支 `feature/add-makeup-final-score-frontend`）。
- 事实基线（开工现场复核）：
  - 后端两个端点**已存在**：`src/main/java/com/exam/score/controller/ScoreController.java` 的 `GET /api/exams/{examId}/scores/makeup-final/{studentId}`（教师 `exam:manage`、`requireOwnedExam` 水平越权校验）与 `GET /api/scores/makeup-final?examId=`（学生本人；`reviewing=true` 时 `finalScore=null`，防「看了分数再申请」）。`openapi.yaml` 已含两条路径。
  - 前端生成层 `frontend/src/api` 对 makeup-final **零命中**（未随契约重生成）。
  - `frontend/src/pages/(dashboard)/teacher/makeups/index.page.vue` 顶部挂着「后端尚未接线（遗留 #5）」的诚实边界 Alert——**该表述在本变更完成后过期**，必须改写，不得残留。
- 参考实现风格：`frontend/src/pages/(dashboard)/student/scores/index.page.vue` 的三态映射（`mapMyScoreToView` / `isNotPublishedError`）与 `frontend/src/utils/scoreVisibility.ts`——学生侧 reviewing 语义与它**同构**，优先复用而非新写。

## 验收判据（不是常量）

1. **开工基线**：`pnpm lint:check`、`pnpm type-check:check`、`pnpm test` 各跑一次，记录 exit code 与 vitest 文件/用例数 + 短 revision；不绿就停。
2. **收尾门禁**：三项 exit=0，vitest 用例数较开工只增不减。
3. **契约无漂移**：gen:api 后 `git diff frontend/src/api`——除 makeup-final 相关新增外，任何其它形状变化都停下回报（既有惯例：契约重导出后比对，不顺手消化）。
4. **零后端改动**：`git status` 里不得出现 `src/main`、`src/test`、`pom.xml`。

## 组件坑（本仓已知，别再踩）

ant-design-vue 4 的 `Alert` 只渲染 `message`/`description` 具名插槽，**默认插槽被静默丢弃**——新增/改写任何 Alert 正文必须走具名插槽（frontend 基线「阶段 21/23 注记」有案）。写测试断言弹窗/提示正文时用 `document.body.textContent`（Modal 经 portal 渲染）。

## 实施（对应 tasks.json 四个任务）

### 任务 1：契约接入

1. `pnpm gen:api`（在 `frontend/` 下；先看 `package.json` scripts 确认命令名）。
2. `git diff` 核对漂移（判据 3）；把 makeup-final 两方法的关键签名贴进回报。
3. 权限与语义写进调用处注释。

### 任务 2：教师侧

1. 在 `teacher/makeups` 页（若你认为应放成绩页，停下来说理由——补考最终成绩查询端点按领域归属放 ScoreController，前端同理由裁决）对已建补考学生展示最终成绩（新列或查看入口，按页面信息密度自定，别过度设计）。
2. 诚实边界 Alert 改写：从「后端尚未接线」改为如实描述（合并规则在后端 `MakeupScoreService`、历史成绩保留不覆盖）。

### 任务 3：学生侧

1. `student/scores` 对补考关联考试展示最终成绩：`GET /api/scores/makeup-final?examId=`，`reviewing === true` 隐藏分数、未发布按后端错误渲染为「待发布」态——复用/扩展现有三态映射，**不新起一套推断**。

### 任务 4：测试

1. vitest 新增：教师侧渲染（mock 契约层，模式参考 `frontend/src/pages/(dashboard)/teacher/scores/__tests__/publishConfirmAlert.spec.ts` 的 harness：`vi.hoisted` 共享 queryStore、mock `@/api/axios` 与 `@/api/apiClient`、`vi.waitFor` 等 Modal/异步）、学生侧三态、不本地推算合并规则。
2. 门禁三项复跑（判据 2）。

## 写入边界

允许新增 / 修改：

- `frontend/src/api/**`（仅 gen:api 产物）
- `frontend/src/pages/(dashboard)/teacher/makeups/**`、`frontend/src/pages/(dashboard)/student/scores/**`
- `frontend/src/components/postexam/**`、`frontend/src/utils/**`、`frontend/src/constants/**`（仅本变更所需）
- `frontend/src/**/__tests__/**`（新增测试）
- `frontend/docs/**`（演示脚本更新，可选）

禁止其它路径。**特别禁止**：`src/main`、`src/test`、`pom.xml`、`openapi.yaml`、`spec/**`、`docker-compose.yml`。

## Commit

- **自己 commit**，中文描述，前缀 `feat(frontend)` / `test(frontend)`：gen:api 产物一笔、页面接入一笔（或合并）、测试一笔（粒度自定，回报里写清楚）。
- 每次提交后 `git rev-parse HEAD` + `git status --short`；HEAD 若变 unborn，从 `.git/logs/HEAD` 取 sha 补 ref，**不要用 `git update-ref`**。
- **禁止 `stash` / `reset` / `checkout -- .` / `clean`**。

## 回报格式（按此六段，不要写散文）

1. **开工基线**：三项门禁 exit code + vitest 文件/用例数 + 短 revision
2. **契约核对**：gen:api 前后 `git diff frontend/src/api` 结论（无漂移 / 漂移清单+停下回报）
3. **教师侧证据**：页面改动点、Alert 改写前后文案、渲染数据来源（只来自端点返回）
4. **学生侧证据**：三态映射复用方式、reviewing 同构证明（与 myScore 口径对照）
5. **收尾门禁**：三项 exit code + vitest 数（只增不减）+ 零后端改动的 `git status` 证明
6. **意外发现**：契约漂移、接口行为与注释不符等

## 禁止

- 禁止改后端任何文件；
- 禁止在生成层手写 makeup-final 方法（必须走 gen:api）；
- 禁止本地推算合并规则或组装补考家族树；
- 禁止 Alert 默认插槽正文（组件坑）；
- 禁止残留「后端尚未接线」过期表述；
- 禁止勾 `tasks.json` 或动 `spec/**`。
