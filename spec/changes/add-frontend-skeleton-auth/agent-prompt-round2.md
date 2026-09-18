# 子 agent 提示词（第 2 轮·返修）—— `add-frontend-skeleton-auth`（阶段 19）

> 用法：整份复制发给子 agent。自包含。
> **第 1 轮交付未通过验收。本轮只修一个问题：工作区有 11 个文件的真实改动未提交，导致「已提交的代码」从未被任何门禁验证过。不要重做已通过的部分，不要扩大范围。**

---

## 第 1 轮验收结果（指导 agent 已独立核实）

### 已通过，**不要动**

- **提交边界干净**：三个 commit（`ecf6f5d` / `3f81b2a` / `a6e487f`）合起来**只触及** `frontend/**` + `.gitignore` + `docs/指导Agent交接文档.md`；`git diff --name-only 0fb56b9..HEAD` 中**没有** `src/main`、`src/test`、`pom.xml`、`openapi.yaml`、`docker`、`spec` 任何一项 → 硬约定 1「本阶段不改后端」**成立**；
- **未误提交产物**：`node_modules/`、`dist/`、`test-results/`、`typed-router.d.ts`、`frontend/e2e/.env` 均不在提交清单内；
- **交接文档**：工作区对该文件**零内容差异**（`git diff --ignore-all-space --numstat` 无输出），你追加的 51 行已入 commit，未重写既有内容；
- **代理不 rewrite 已被真实请求证明**（`POST /api/auth/login` → 200 且 `/api/auth/me` → 200 返回 `username=admin`），且固化进 e2e 断言；
- **API 客户端全部生成、零手改**（`src/api/axios/**` 16 文件），手写只有 4 个薄封装；
- **守卫收敛为单一函数** `decideNavigation`，并注明「前端守卫不是安全边界」；
- 未夹带业务页面、未引入 `echarts`；
- 你回报的 6 条踩坑记录（eslint flat config 豁免写法、baseURL 必须空串、1009 单码覆盖所有会话失效、改密后不得再调 logout、vuex types 条件缺失、`@hey-api` defineConfig 重载误报）指导 agent 已收到，会另行沉淀。

### 唯一阻塞问题：**交付状态 ≠ 提交状态**

你回报「工作区已 clean」，**不成立**。指导 agent 实测 `git status --short` 有 11 个 `M`，且 `git diff --numstat` 证明是**真实内容改动**（合计 **+115 / −138**），不是行尾噪音：

```
4   17   frontend/src/api/__tests__/apiClient.spec.ts
13  18   frontend/src/api/apiClient.ts
10  5    frontend/src/pages/(dashboard).page.vue
5   16   frontend/src/pages/(dashboard)/index.page.vue
12  16   frontend/src/pages/change-password.page.vue
12  13   frontend/src/pages/forgot-password.page.vue
17  2    frontend/src/pages/login.page.vue
6   30   frontend/src/pages/register.page.vue
3   3    frontend/src/router/__tests__/access.spec.ts
12  2    frontend/src/router/access.ts
1   1    frontend/src/store/index.ts
```

**为什么这是阻塞项**：你回报的门禁结果（`type-check` exit 0 / `lint:check` exit 0 / `vitest` 3 files 36 passed / `build` exit 0）跑的是**工作区**，而仓库里躺着的是**更早的版本**。也就是说 **HEAD 上的代码从未被任何门禁验证过**。这可能意味着：commit 里的版本是坏的（例如 `access.ts` 少了 12 行、`register.page.vue` 少了 30 行），也可能只是你提交后继续微调——**两种都必须先分清，不能猜**。

---

## 本轮要做的事（按顺序）

### 1. 先说清这 11 个文件改了什么、为什么在 commit 之后

逐个文件给出：**改动性质**（修 bug / 补功能 / 重构 / 格式化）+ **为什么第 1 轮没提交进去**（是提交后又改的，还是 `git add` 漏了）。**不要含糊带过**，这一条是本轮回报的重点。

特别指出：`frontend/src/router/access.ts`（+12/−2）与其单测 `access.spec.ts`（+3/−3）是**守卫核心**，`frontend/src/api/apiClient.ts`（+13/−18）是**拦截器核心**。这两处若有未提交改动，等于第 1 轮回报里「refresh 单飞」「角色守卫」的结论都建立在未入库的代码上。请明确说明。

### 2. 判定 HEAD 上的版本是否可用

在**不动工作区**的前提下，先确认 commit 里的版本是什么状态。推荐做法：

```powershell
git stash push -u -m round2-reconcile
```

然后在**纯净 HEAD** 上跑一遍门禁（`pnpm type-check:check`、`pnpm lint:check`、`pnpm test`、`pnpm build`），**记录结果**。跑完 `git stash pop` 恢复。

- 若纯净 HEAD 门禁**全绿** → 说明未提交改动是后续增量，直接进第 3 步；
- 若纯净 HEAD 门禁**有红** → **停下回报**，写清红在哪、你打算怎么处理（是把工作区版本作为正确版本提交，还是需要修）。**不要自行决定丢弃任何一方的代码。**

> `git stash` 有风险，操作前后都要 `git status --short` 记录，`stash pop` 后必须确认 11 个文件都回来了（再跑一次 `git diff --numstat` 对比上面那张表）。**若 stash pop 冲突或丢改动，立刻停下回报，不要强行 reset/checkout 丢弃工作。**

### 3. 把工作区改动提交进去

确认工作区版本是正确版本后：

- `git add` **逐个列出**这 11 个文件（**禁止 `git add -A` / `git add .`**，仓库里另有指导 agent 的未提交文档改动，不许带走）；
- 一次提交，message 建议：

```
fix(frontend): 补齐首轮遗漏未提交的守卫与认证页改动
```

（若第 2 步显示这些改动性质是功能补全而非修复，用 `feat(frontend)` 并在 message 里写清补的是什么。）

### 4. 在**已提交状态**上重跑全部门禁

提交后 `git status --short` 必须为空（除指导 agent 自己的文档改动外，不许再有你的 `M`）。然后重跑：

- `pnpm type-check:check`
- `pnpm lint:check`
- `pnpm test`（vitest，给出 files / passed 数字）
- `pnpm build`

**四项必须全绿**，且这次跑的就是 HEAD 上的代码。把四项输出摘要贴进回报。

### 5. 后端回归

跑一次后端全量，确认仍是 **`Tests run: 218, Failures: 0, Errors: 0, Skipped: 1`** → BUILD SUCCESS（**基线已从 213 变为 218**，因为 `add-auth-must-change-password` 已合入并带了 5 条用例；`Skipped: 1` 属设计使然）。命令见下。

### 6. e2e 缺口：**本轮仍不要自行安装浏览器**

第 1 轮你正确判断了「不擅自执行 `playwright install`」，**本轮继续保持**。本机 `ms-playwright` 只有 `chromium_headless_shell-1234`，而 `@playwright/test@1.56.1` 要求 `-1243`。是否授权下载由用户决定，指导 agent 会另行处理。

你只需在回报里写明：用例已被正确收集（3 条）、未实跑、原因、补齐命令（`cd frontend; pnpm exec playwright install chromium`）。**不得声称 e2e 已通过。**

---

## 硬约定（与第 1 轮一致，重申关键点）

1. **不改后端**：`src/main/**`、`src/test/**`、`pom.xml`、`openapi.yaml`、`docker/**`、`spec/**` 零改动。
2. **不引入新依赖**（含不装 playwright 浏览器、不改 `package.json` 依赖版本）。若你认为 `@playwright/test` 版本需要调整以匹配本机浏览器，**停下回报**，不要自己改。
3. **不放宽门禁**：不得大面积 `any`、不得关 `vue-tsc` 严格项、不得批量 `eslint-disable`。第 1 轮你只在 `e2e/**` 关了 `no-explicit-any` 并写了理由，这是可接受的；**不要扩大豁免范围**。
4. **不对生成物跑 prettier**（你自己在踩坑记录里写过的，遵守它）。
5. **不夹带阶段 20–23 的业务页面**、不引入 `echarts`。
6. **严禁提交** `node_modules/`、`dist/`、`test-results/`、`.env`。
7. **不要勾 `tasks.json`、不要归档 `spec/`**。
8. 最多修复尝试 **2 次**，第 3 次仍失败停下回报。

## 写入边界

允许改：`frontend/**`（**仅**为把已有工作区改动收口所需；不要借机重构）。

允许新增：无（本轮不应有新文件；若确有必要，先在回报里说明理由）。

禁止：`src/**`、`pom.xml`、`openapi.yaml`、`docker/**`、`spec/**`、`docs/**`（交接文档第 1 轮已追加完毕，本轮不要再动）。

---

## 本机命令

后端全量（PowerShell **必须数组 splatting**）：

```powershell
$jargs = @('-classpath','D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar','-Dclassworlds.conf=D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf','-Dmaven.home=D:\develop\Maven\apache-maven-3.9.4','-Dmaven.multiModuleProjectDirectory=D:\code\examOnline','org.codehaus.plexus.classworlds.launcher.Launcher','-o','test'); & 'D:\develop\jdk177\bin\java.exe' @jargs
```

后端若需起 dev 实例（本轮**通常不需要**）：环境事实见 `docs/指导Agent交接文档.md` §6.2（MySQL 容器 **13306** + `root123`，不是默认 3306/root/root；不要启 `exam-redis`）。**契约导出只能走路 B**，路 A（`-DexportContract=true`）有编码缺陷会损坏 `openapi.yaml`（遗留 #12）——本轮不涉及导出，别去碰它。

前端命令在 `frontend/` 下用 pnpm 跑。

---

## 回报格式（按此六段，不要写散文）

1. **11 个文件逐个说明**：改了什么 + 为什么第 1 轮没提交进去（这是本轮重点，不许含糊）
2. **纯净 HEAD 门禁结果**：stash 后四项各是什么结果；`stash pop` 后 `git diff --numstat` 是否与上面那张表一致（贴输出）
3. **本轮 commit**：message、`git add` 的文件清单、`git rev-parse HEAD`、`git status --short`（应为空或只剩指导 agent 的文档改动）
4. **已提交状态上的四项门禁**：`type-check:check` / `lint:check` / `test`（files + passed 数字）/ `build` 各自输出摘要
5. **后端回归三数字 + Skipped 数**（应为 218 / 1）
6. **e2e 状态**：未实跑 + 原因 + 补齐命令；以及**意外发现**

## 禁止

- 禁止在没搞清「HEAD 版本 vs 工作区版本哪个正确」之前就提交或丢弃任何一方；
- 禁止 `git reset --hard` / `git checkout -- .` / `git clean` 等丢弃工作的操作；
- 禁止 `git add -A` / `git add .`；
- 禁止安装 playwright 浏览器或改其版本；
- 禁止改后端任何文件；
- 禁止放宽 lint / type-check / 断言来转绿；
- 禁止声称 e2e 已通过；
- 禁止勾 `tasks.json` 或归档 `spec/`。
