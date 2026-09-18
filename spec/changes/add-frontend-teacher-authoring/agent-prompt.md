# 子 agent 提示词 —— `add-frontend-teacher-authoring`（阶段 20，前端方向②）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/add-frontend-teacher-authoring/proposal.md`、`tasks.json`、`specs/frontend/spec-delta.md`。

> **⚠️ 基线与环境更正（以此为准，覆盖下文旧内容）**：
> 1. 后端全量基线现为 `Tests run: 218, Failures: 0, Errors: 0, Skipped: 1` → BUILD SUCCESS。下文所有「210」一律读作 **218**；`Skipped: 1` 是契约导出方法受 `exportContract` 开关控制，**属设计使然，不要试图消除**。
> 2. **dev 环境启动方式见 `docs/指导Agent交接文档.md` §6.2**，不要自己摸索：MySQL 容器 `exam-mysql-master` 在宿主 **13306**（不是 3306）、口令 **root123**（不是 root）；`application-dev.yml` 的默认值指向 Windows MySQL80 服务，**会拒绝 root/root**；Redis 用宿主 6379，**不要启 `exam-redis` 容器**（端口冲突）；启动前必须设 `DB_URL`/`DB_PASSWORD`/`SLAVE_DB_URL`/`SLAVE_DB_PASSWORD` 四个环境变量。
> 3. 启动日志中 `ExamSubmitSender` → `waitForConfirmsOrDie` 的 `IllegalStateException` 是**遗留 #10**，真 broker 下才暴露、非致命；`/actuator/health` 返回 UP 即视为启动成功，**不要顺手修**。

---

## 现状

- 仓库 `D:\code\examOnline`，分支 `feature/add-performance-deepening-readwrite`。
- **前置阶段 19 `add-frontend-skeleton-auth` 必须已合入**：`frontend/` 已存在且可 `dev` / `build` / `lint:check` / `type-check:check` / `vitest` 全绿；API 层已由 `gen:api` 从 `openapi.yaml` 生成；角色布局与守卫、认证四页、令牌续期单飞已就位。**开工前先跑一遍这些门禁确认基线，记录数字**；基线不绿就停下回报，不要在坏底座上继续。
- 本阶段做**教师端题库与组卷**界面，对应后端 `question/QuestionController`、`question/TagController`、`paper/PaperController`。
- 后端全量测试基线 **210 全绿**，本阶段结束时必须仍是 210。
- 最多修复尝试 **2 次**。第 3 次仍失败停下回报。
- **你必须自己 commit**（按任务组分次：题库 / 标签 / 组卷 / 测试）。每次提交后立即 `git rev-parse HEAD` 与 `git status --short`；HEAD 若变 unborn，从 `.git/logs/HEAD` 取 sha 按仓库约定补 ref，**不要用 `git update-ref`**。
- **不要改 `spec/`**（含不要勾 `tasks.json`、不要动 `spec/README.md`）。

## 先核实，不要照抄文档（重要）

`docs/` 里的需求文档写的是**产品愿景**，与已实现代码可能不一致。**题型枚举、字段名、软删除语义、抽题入参、分值覆盖字段**这几件事，你必须先从代码核实，再写界面：

1. 读 `src/main/java/com/exam/question/**`（实体、枚举、Service、Controller）确认：**实际支持哪几种题型**、每种题型的字段（`content` / `choices` / `correct_answer` / `score` / `difficulty` / `analysis`）、软删除字段与查询语义；
2. 读 `src/main/java/com/exam/paper/**` 确认：试卷与试卷题目的字段（题号、**本卷分值是否可覆盖题目默认分值**）、**标签随机抽题的真实入参与返回**；
3. 读 `frontend/src/api/axios/**`（阶段 19 生成的客户端）确认调用签名，**以生成的类型为准**；
4. 若 `openapi.yaml` 落后于后端代码（例如后端已改但契约未重新导出），**停下回报**，不要在前端手写补丁类型。

`docs/examOnline需求规格说明书.md` 提到 9 种题型（含填空/公式/代码/听力/口语），但 v3 实施蓝本只保留 4 种（单选/多选/判断/简答）。**以代码实际支持的为准**，界面只做代码真支持的题型，并在回报里写清你核实到的题型清单与依据文件。

## 硬约定（违反即返工）

1. **本阶段不改后端**：`src/main/**`、`src/test/**`、`pom.xml`、`openapi.yaml`、`docker/**` 零改动。发现接口缺口（如抽题入参不够、缺少批量操作端点）**停下回报并说明缺什么**，不要在前端用多次请求拼凑、不要 mock 掉当作已完成、不要擅自重新导出契约。
2. **API 调用一律走阶段 19 生成的客户端**，禁止手写重复接口定义或重复类型。
3. **题型表单必须是配置驱动**：四种题型共用一套表单骨架 + 一份题型字段配置，**不许四份复制粘贴代码**。新增题型时只加配置。这条是本阶段的主要工程质量点。
4. **前端不得实现判分口径**：答案归一化（例如判断题 `T/F/正确/错误/A/B` 归一）是后端职责。**前端只做录入格式校验**，按后端约定格式提交。在相关代码处写注释说明这条取舍，防止后人「顺手在前端归一化」。
5. **随机抽题算法在后端**：前端只配置条件、展示抽中结果、允许重抽或确认入卷。**禁止**在前端实现随机抽题。
6. **软删除语义以后端返回为准**：前端不自行发明过滤规则，不缓存已删题目做「假可用」展示。
7. **不夹带砍掉项**：Excel 导入、蓝图组卷（双向细目表）、试卷模板与版本管理、AI 生成题目、题目查重、A/B 卷——**全部不做**（v3 砍掉清单）。
8. **不夹带后续阶段页面**：不建考试 / 班级 / 答题 / 批改 / 成绩任何页面。
9. **不引入新依赖**（尤其不引入 `echarts`、表单引擎、富文本编辑器）。若确有必要（如题目内容需要富文本），**停下回报并说明理由**，不要擅自装。
10. **质量门禁不许放宽**：不得大面积 `any`、不得关掉 `vue-tsc` 严格项、不得加 `eslint-disable` 批量绕过。确需调整必须逐条说明理由。
11. **严禁提交** `node_modules/`、`dist/`、`test-results/`、`.env`。

## 写入边界

允许新增 / 修改：

- `frontend/src/pages/**`（新增题库、标签、组卷、试卷相关页面）
- `frontend/src/components/**`（新增题型表单、题目卡片、选题器、分值编辑等组件）
- `frontend/src/hooks/**`、`frontend/src/utils/**`（新增本阶段所需的组合式函数与纯函数）
- `frontend/src/router/**` 或文件路由约定所需改动（**仅**新增教师端命题路由与菜单项，不动守卫逻辑本身）
- `frontend/src/store/**`（**仅**在确有需要时新增命题模块状态，不动会话/角色状态）
- `frontend/src/api/**`（**仅**当后端契约已更新时重新 `gen:api`；不许手写）
- `frontend/src/**/__tests__/**` 或 `frontend/tests/**`（新增 vitest 用例）

禁止其它路径。**特别禁止**：`src/main/**`、`src/test/**`、`pom.xml`、`openapi.yaml`、`schema.sql`、`docker/**`、`spec/**`、阶段 19 已交付的拦截器/守卫/认证页（如需复用请 import，不要改写）。

## 实施

1. **基线自检**：跑 `lint:check`、`type-check:check`、`vitest`、后端全量（命令见下），记录四组数字。
2. **核实后端契约**（见上「先核实」小节），把核实结论写进回报。
3. **题库列表页**：分页 + 按题型 / 标签 / 关键词筛选 + 批量选择；列表项展示题型、分值、难度、标签、软删状态；用 `@tanstack/vue-query` 管查询与缓存失效（新增/修改/删除后 invalidate 对应 query key）。
4. **题目编辑**：配置驱动的题型表单（单选 / 多选 / 判断 / 简答），各自校验：
   - 单选：必须且只能一个正确项；
   - 多选：正确项 ≥ 2；
   - 判断：按后端约定格式二选一；
   - 简答：无选项，参考答案 / 解析可填；
   - 公共：题干非空、分值为正数、难度在合法区间、标签可多选。
   新增与编辑共用同一表单组件。
5. **标签管理页**：标签 CRUD（按 `TagController` 现有能力，**不扩字段、不扩表**）。
6. **手动组卷页**：从题库选题入卷、题号上下移排序、**逐题分值覆盖**（若代码核实到 `paper_questions.score` 支持覆盖）、总分实时汇总（用纯函数算，便于单测）。
7. **标签随机抽题组卷**：按标签 + 题型 + 数量配置 → 调后端抽题 → 预览抽中结果 → 可重抽或确认入卷。
8. **试卷列表与只读详情**：题目内容与分值分布预览。
9. **教师端菜单与路由**：把上述页面挂到阶段 19 的布局菜单里，角色限定 `TEACHER`（与 `ADMIN`，按后端实际权限核实）。
10. **测试**（vitest，必须有）：
    - 题型表单配置：四种题型各自渲染的字段与校验规则（含「多选少于 2 项报错」「单选多选冲突报错」）；
    - 总分汇总纯函数：含分值覆盖场景；
    - 题号排序纯函数；
    - 列表筛选参数构造（题型 + 标签 + 关键词 + 分页）；
    - 抽题结果确认 / 重抽的状态流转（用 mock 的生成客户端，**mock 只用于单测，不得用于冒充联调**）。
11. **联调验证**：后端以 dev profile 跑在宿主 8080（本机 MySQL80 占 3306、Windows Redis 占 6379，**不要改端口、不要停这些服务**；后端起不来就停下回报，不要用 mock server 假装通过）。**至少走通一条真实链路**：登录 → 建标签 → 建 4 种题型各一道 → 手动组卷 → 随机抽题组卷 → 预览试卷。把每一步的真实请求路径与 HTTP 状态记进回报。
12. **门禁全绿 + 后端回归仍 210** 再提交。

## 本机 Maven 命令（必须照抄，别自己拼）

PowerShell 下**必须用数组 splatting**，否则 `-Dclassworlds.conf=...` 会被拆坏：

```powershell
$jargs = @('-classpath','D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar','-Dclassworlds.conf=D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf','-Dmaven.home=D:\develop\Maven\apache-maven-3.9.4','-Dmaven.multiModuleProjectDirectory=D:\code\examOnline','org.codehaus.plexus.classworlds.launcher.Launcher','-o','test'); & 'D:\develop\jdk177\bin\java.exe' @jargs
```

前端命令在 `frontend/` 下用 pnpm 跑（`pnpm lint:check`、`pnpm type-check:check`、`pnpm test`）。

## Commit

按任务组分次提交，中文描述、前缀 `feat(frontend)` / `test(frontend)`，例如：

```
feat(frontend): 题库列表与题型驱动的题目编辑表单
feat(frontend): 标签管理与手动组卷（题号排序/分值覆盖）
feat(frontend): 标签随机抽题组卷与试卷只读预览
test(frontend): 补题型校验、总分汇总与抽题流转单测
```

## 回报格式（按此七段，不要写散文）

1. **基线四数字**：`lint:check` / `type-check:check` / `vitest` / 后端全量（改动前）
2. **后端契约核实结论**：实际支持的题型清单 + 依据文件路径与关键代码行；题目字段清单；软删除语义；`paper_questions` 是否支持分值覆盖；**标签随机抽题的真实入参与返回结构**
3. **新增页面与组件清单**：路径 + 各自职责一句话；题型表单的配置驱动是怎么做的（贴配置结构骨架）
4. **真实联调证据**：那条端到端链路每一步的请求路径 + HTTP 状态（不得用 mock 冒充）
5. **单测清单**：用例名 + 断言什么；`vitest` 收尾三数字
6. **收尾**：`lint:check` / `type-check:check` 结果、`vitest` 三数字、后端全量三数字（应仍为 210）、每个 commit 的 `git rev-parse HEAD`、`git status --short`、`git diff --stat`（证明后端零改动）
7. **意外发现 / 接口缺口**：缺什么、你**没有**怎么绕过；以及任何「文档与代码不一致」的地方

## 禁止

- 禁止改后端任何文件（含 `openapi.yaml`）；
- 禁止手写接口定义 / 类型来替代 `gen:api` 产物；
- 禁止在前端实现答案归一化或判分逻辑；
- 禁止在前端实现随机抽题算法；
- 禁止四份复制粘贴的题型表单；
- 禁止夹带 Excel 导入 / 蓝图组卷 / 模板版本 / AI 生成 / 查重 / A-B 卷；
- 禁止夹带阶段 21–23 的页面；
- 禁止擅自新增依赖；
- 禁止用 mock server 冒充联调通过；
- 禁止放宽 lint / type-check / 断言来转绿；
- 禁止勾 `tasks.json` 或归档 `spec/`。
