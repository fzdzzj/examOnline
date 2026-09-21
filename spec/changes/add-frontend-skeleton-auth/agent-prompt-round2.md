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

### 2. **先把工作区改动提交进去，再评估**（本轮已改用无风险流程）

> **重要变更**：上一版提示词让你用 `git stash` 在纯净 HEAD 上跑门禁。**已发生过一次事故**：stash 之后没有 pop，反而执行了 `git reset`，11 个文件的改动全部从工作区消失，指导 agent 是靠 `git fsck` 找到 dangling stash commit（`caa96bc`，message `round2-reconcile`）才 `git stash apply` 恢复回来。**本轮禁止再用 stash，也禁止任何 reset。**

新流程（零丢失风险）：

1. **立即先提交**这 11 个文件，把改动固化进 git 对象库，之后无论怎么操作都丢不了：

```powershell
git add frontend/src/api/__tests__/apiClient.spec.ts frontend/src/api/apiClient.ts "frontend/src/pages/(dashboard).page.vue" "frontend/src/pages/(dashboard)/index.page.vue" frontend/src/pages/change-password.page.vue frontend/src/pages/forgot-password.page.vue frontend/src/pages/login.page.vue frontend/src/pages/register.page.vue frontend/src/router/__tests__/access.spec.ts frontend/src/router/access.ts frontend/src/store/index.ts
git commit -m "fix(frontend): 补齐首轮遗漏未提交的守卫与认证页改动"
```

（**逐个列出，禁止 `git add -A` / `git add .`**。注意 `(dashboard)` 两个路径带括号，PowerShell 下要加引号。若你判断这些改动性质是功能补全而非修复，改用 `feat(frontend)` 并在 message 写清补的是什么。）

2. 提交后 `git status --short` 必须**不含任何 frontend 文件**（只可能剩指导 agent 自己的文档改动）。
3. **在这个已提交状态上跑四项门禁**（见第 4 步）。这次跑的就是 HEAD 上的代码，结论才算数。
4. **如果你还想知道「首轮 commit（`a6e487f`）里的版本是否本来就是坏的」**，用只读 worktree 检查，**不要动当前工作区**：

```powershell
git worktree add ..\examonline-headcheck a6e487f
```

在那个目录里单独 `pnpm install` + 跑门禁，记录结果，完事 `git worktree remove ..\examonline-headcheck`。**这一步是可选的诊断**，目的是在回报里说清「首轮到底提交了个什么状态」；不做也不影响本轮验收。

### 3. （已并入第 2 步）

提交动作已在第 2 步完成，本步跳过。

### 4. 在**已提交状态**上重跑全部门禁

提交后 `git status --short` 必须为空（除指导 agent 自己的文档改动外，不许再有你的 `M`）。然后重跑：

- `pnpm type-check:check`
- `pnpm lint:check`
- `pnpm test`（vitest，给出 files / passed 数字）
- `pnpm build`

**四项必须全绿**，且这次跑的就是 HEAD 上的代码。把四项输出摘要贴进回报。

### 5. 后端回归

跑一次后端全量门禁，按**判据**判定，不对任何写死的数字负责（历史文档里的常量都是过期副本）：收尾那次执行必须 `Failures=0` 且 `Errors=0`、`Skipped` 保持 1（`Skipped: 1` 是契约导出方法受 `exportContract` 开关控制，属设计使然，不要消除）、用例总数不得少于**本轮开工时你自己实测并记录在案的数值**（本轮的开工值就是下面第 2 步之前先跑一次记下的数）。不满足就停下回报，**不得增删用例去凑数**。命令见「后端门禁命令」。

### 6. e2e：**用户已授权安装浏览器，本轮必须实跑**

第 1 轮你正确判断了「不擅自执行 `playwright install`」。**现在用户已明确授权**，本轮请：

1. `cd frontend; pnpm exec playwright install chromium`（**只装 chromium，不要装 firefox/webkit，不要加 `--with-deps`**）；
2. 装完实跑 `pnpm test:e2e`，三条用例（登录 → 首页 → 登出 等）**必须真实通过**；
3. e2e 需要后端在应用端口可用：按 `docs/指导Agent交接文档.md` §6.2 起 dev 实例（**宿主端口与凭据现场读该节 / `docker-compose.yml` / `application-dev.yml`，本文件不复制数值**；不变的规则是**不要起 compose 里的 Redis 容器**，宿主已有一套 Redis 会抢端口），跑完**必须停掉应用**并确认该端口无监听（停法见 §6.2；曾有残留实例导致后续验证失真）；
4. **不要为了让 e2e 通过而放宽断言**、不要改成对 mock server 跑、不要跳过代理断言（`expect(loginRes.url()).toContain('/api/auth/login')` 必须保留，它是「代理不 rewrite」的回归护栏）；
5. 若安装或实跑失败，**停下回报**并贴完整原始报错，**不要声称通过**。

`@playwright/test` 版本**仍不要改**（`package.json` 依赖版本冻结）；只装浏览器。若装完仍报版本不匹配，停下回报。

---

## 硬约定（与第 1 轮一致，重申关键点）

1. **不改后端**：`src/main/**`、`src/test/**`、`pom.xml`、`openapi.yaml`、`docker/**`、`spec/**` 零改动。
2. **不引入新依赖**（不改 `package.json` 依赖版本）。**唯一例外**：用户已授权执行 `pnpm exec playwright install chromium` 下载浏览器（这不改依赖声明）。
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

## 后端门禁命令

仓库自有的**唯一门禁命令待 `update-agent-gate-single-source`（E2）B-2 回填**。本文件不再照抄机器绑定的命令行。

回填前按 `docs/指导Agent交接文档.md` 六（§6.1 上下文）的工具链小节自行拼装：那是**特定 shell / 本机环境下的历史绕行办法**（含仓库外 JDK/Maven 绝对路径），不是被指定的唯一命令，换环境先自检 `mvn -version` 用的是哪个 JDK 再拼。**必须带 `clean`**，并在回报里给出实际执行的命令与原始输出。

后端若需起 dev 实例（本轮**通常不需要**）：环境事实（宿主端口 / 凭据 / 是否要设环境变量）现场读 `docs/指导Agent交接文档.md` §6.2 与 `docker-compose.yml`、`application-dev.yml`，本文件不复制数值；**不要起 compose 里的 Redis 容器**（宿主已有一套 Redis 会抢端口）。**契约导出只能走路 B**，路 A（`-DexportContract=true`）有编码缺陷会损坏 `openapi.yaml`（遗留 #12）——本轮不涉及导出，别去碰它。

前端命令在 `frontend/` 下用 pnpm 跑。

---

## 回报格式（按此六段，不要写散文）

1. **11 个文件逐个说明**：改了什么 + 为什么第 1 轮没提交进去（这是本轮重点，不许含糊）
2. **提交结果**：`git add` 的文件清单、commit message、`git rev-parse HEAD`、`git status --short`（应不含任何 frontend 文件）；若做了可选的 worktree 诊断，写明 `a6e487f` 上四项门禁的结果
3. **首轮遗漏的根因**：为什么第 1 轮 `git add` 漏了这 11 个文件（命令写错？提交后又改？还是别的原因），以及你本轮用了什么做法防止再发生
4. **已提交状态上的四项门禁**：`type-check:check` / `lint:check` / `test`（files + passed 数字）/ `build` 各自输出摘要
5. **后端回归**：实际执行的命令 + 原始输出 `Tests run / Failures / Errors / Skipped` 四数字 + 当时短 revision，并按判据自评（`Failures=0`、`Errors=0`、`Skipped=1`、总数不少于本轮开工时记录值；不满足即不通过，**不得改测试凑数**）
6. **e2e 实跑结果**：安装命令与输出摘要、`pnpm test:e2e` 的通过数字、后端 dev 实例起停证据（起时 health UP、停后该端口无监听）；以及**意外发现**

## 禁止

- 禁止在没搞清「HEAD 版本 vs 工作区版本哪个正确」之前就提交或丢弃任何一方；
- **禁止 `git stash`、禁止 `git reset`（含 `--hard`）、禁止 `git checkout -- .`、禁止 `git clean`**（已因此丢过一次工作，靠 dangling stash 才恢复）；需要检查历史版本只能用 `git worktree add`；
- 禁止 `git add -A` / `git add .`；
- 禁止改 `@playwright/test` 版本或安装 chromium 以外的浏览器；
- 禁止改后端任何文件；
- 禁止放宽 lint / type-check / 断言来转绿；
- 禁止对 mock server 跑 e2e 冒充联调；
- 禁止在 e2e 未实跑或未通过时声称已通过；
- 禁止勾 `tasks.json` 或归档 `spec/`。
