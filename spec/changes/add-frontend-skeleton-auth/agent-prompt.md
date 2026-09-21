# 子 agent 提示词 —— `add-frontend-skeleton-auth`（阶段 19，前端方向①）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/add-frontend-skeleton-auth/proposal.md`、`tasks.json`、`specs/frontend/spec-delta.md`。

> **验收判据（不是常量，覆盖下文所有旧数字）**：本阶段对后端的唯一要求是「跑门禁命令后满足下列条件」，不是「用例数等于某个数」——仓库历史文档里出现过的任何写死用例数都是过期副本，看到它就按本判据办，**不要去凑它、也不要为了对上它去动测试**。
> 1. **开工时**跑一次后端全量门禁，把该次 `Tests run / Failures / Errors / Skipped` 与产生它的命令、当时的短 revision 一起记录在案（写进你的回报）；这个数字就是本阶段的基线。
> 2. **收尾时**再跑一次同一条命令，必须同时满足：`Failures=0` 且 `Errors=0`、`Skipped` 保持 1、用例总数**不得少于**开工时记录在案的数值。
> 3. `Skipped: 1` 是契约导出方法受 `exportContract` 开关控制，**属设计使然，不是被禁用的断言，不要试图消除它**。
> 4. 门禁命令：仓库自有的唯一门禁命令**待 `update-agent-gate-single-source`（E2）B-2 回填**；回填前按 `docs/指导Agent交接文档.md` 六的工具链小节自行拼装，并在回报里给出**实际执行的命令与原始输出**（不得引用文档里的数字当结果）。该小节是特定 shell / 本机环境下的历史绕行办法，不是被指定的唯一命令。
> 5. **基线不绿（`Failures>0` 或 `Errors>0`，或 `Skipped≠1`，或用例总数少于开工记录值）就停下回报**——不得为了转绿删用例、禁用例或放宽断言。

---

## 现状

- 仓库 `D:\code\examOnline`，分支 `feature/add-performance-deepening-readwrite`。
- 后端是**已完成的纯后端单体**（Java 17 + Spring Boot 3.5.5，14 个 Controller 全挂 `/api/**`，全量测试基线为**绿**：按顶部判据，`Failures=0` 且 `Errors=0`、`Skipped` 保持 1）。**本仓库当前没有前端**，你要从零建 `frontend/`。
- **前置阶段 18 `add-backend-openapi` 必须已合入**：仓库根应存在真实导出的 `openapi.yaml`，后端应能响应 `/v3/api-docs`。**开工前先确认 `openapi.yaml` 存在且非空**；不存在就停下回报，**不要自己手写一份契约**。
- **技术栈必须对齐参考项目 `D:\code\crm\font\crm-front`**（这是用户指定的模仿对象，已核实其 `package.json` / `vite.config.ts`）。开工前先读它的：`package.json`、`vite.config.ts`、`tsconfig*.json`、`eslint.config.mjs`、`.prettierrc.json`、`openapi-ts.config.ts`、`src/api/apiClient.ts`、`src/api/config.ts`、`src/api/queryClient.ts`、`src/utils/token.ts`、`src/layout/**`、`src/pages/login.page.vue`、`src/router/**`、`src/store 或 vuex 用法`。**照它的做法，不要自己发明一套**。
- 参考项目已核实的关键选型（你要用同一套）：
  - Vue `^3.5`、TypeScript、Vite `^7`、`@vitejs/plugin-vue`
  - `ant-design-vue ~4.2`、`@ant-design/icons-vue`
  - `tailwindcss ^4` + `@tailwindcss/vite`
  - `unplugin-vue-router`（文件路由，`routesFolder: ./src/pages`，`extensions: ['.page.vue']`，`dts: ./typed-router.d.ts`）
  - `@tanstack/vue-query ^5`、`axios`、`@hey-api/client-axios`、`@hey-api/openapi-ts`（`gen:api`）
  - `vuex ^4`、`dayjs`、`vue-types`
  - 工程：`eslint ^9` + `eslint-plugin-vue` + `@vue/eslint-config-typescript` + `eslint-config-prettier`、`prettier`、`vue-tsc`（`type-check:app` / `type-check:config` 分开）、`vitest`、`@playwright/test`、`jsdom`
  - 包管理 **pnpm**（有 `pnpm-lock.yaml`）
- 最多修复尝试 **2 次**。第 3 次仍失败停下回报。
- **你必须自己 commit**（可分 2–3 次，按任务组：骨架 / API 层与会话 / 布局守卫与认证页 / e2e）。每次提交后立即 `git rev-parse HEAD` 与 `git status --short`；HEAD 若变 unborn，从 `.git/logs/HEAD` 取 sha 按仓库约定补 ref，**不要用 `git update-ref`**。
- **不要改 `spec/`**（含不要勾 `tasks.json`、不要动 `spec/README.md`）。归档由指导 agent 做。

## 硬约定（违反即返工）

1. **本阶段不改后端**：`src/main/**`、`src/test/**`、`pom.xml`、`openapi.yaml`、`docker/**` 一律**零改动**。后端测试基线**不得变差**——判据见顶部：收尾那次执行 `Failures=0` 且 `Errors=0`、`Skipped` 保持 1、用例总数不得少于开工时记录在案的数值。发现接口缺口（如缺字段、缺端点）**停下回报**，不要在前端用多次请求拼凑绕过、不要 mock 掉当作已完成。
2. **dev 代理不得剥掉 `/api` 前缀**。参考项目的 `vite.config.ts` 里有 `rewrite: (path) => path.replace(/^\/api/,'')`——**examOnline 后端路径本身带 `/api`，照抄这句会全部 404**。你的代理只转发不 rewrite。这条必须用一次真实登录请求验证过再往下做。
3. **API 客户端必须由 `gen:api` 从 `openapi.yaml` 生成**，禁止手写与契约重复的接口定义 / 类型。手写的只允许是薄封装（拦截器、错误映射、query 封装）。
4. **前端守卫不是安全边界**。后端 `@RequireRole` / `assertTeacherOwns*` 才是权威。路由守卫代码里必须写明这句取舍注释，不许给人「前端做了权限」的错觉。
5. **Refresh 必须单飞**：并发 401 只允许刷新一次，其余请求排队等结果后重放。**这一条必须有 vitest 单测覆盖**，因为后端有「Refresh 轮换 + 复用检测」，并发各自刷新会导致**全端下线**（这是真实事故路径，不是理论风险）。
6. **不得用前端本地标记代替后端结果**（例如「本地记住已登录」而不调后端校验）。
7. **本阶段不夹带业务页面**：不建题库 / 组卷 / 考试 / 答题 / 批改任何页面，不引入 `echarts`。这些属于阶段 20–23。
8. **不引入**：Service Worker、IndexedDB、离线能力、任何 CDN/构建产物提交（`dist/`、`node_modules/`、`test-results/` 必须进 `.gitignore`，**严禁提交**）。
9. **前端测试基线独立**：vitest / playwright **不并入** Maven surefire 计数，不要去改后端 `pom.xml` 挂前端插件。

## 写入边界

允许新增 / 修改：

- `frontend/**`（全新目录：`package.json`、`pnpm-lock.yaml`、`vite.config.ts`、`tsconfig*.json`、`eslint.config.mjs`、`.prettierrc.json`、`openapi-ts.config.ts`、`index.html`、`.gitignore`、`.env.example`、`src/**`、`e2e/**` 或 `tests/**`、`playwright.config.ts`）
- `.gitignore`（仓库根，**仅追加** `frontend/node_modules`、`frontend/dist` 等条目，不删既有行）
- `docs/指导Agent交接文档.md`（**仅追加**前端启动方式与代理约定一小节，不重写既有内容）

禁止其它路径。**特别禁止**：`src/main/**`、`src/test/**`、`pom.xml`、`openapi.yaml`、`schema.sql`、`docker/**`、`spec/**`。

## 实施

1. **环境自检先行**：`node -v`、`pnpm -v`。**若 pnpm 不存在，停下回报**，不要擅自 `npm i -g pnpm` 或改用 npm/yarn（会产出错误的 lockfile，且安装全局包需用户授权）。若需要装依赖而离线不可达，同样停下回报。
2. **脚手架**：按参考项目的配置文件逐一对齐（Vite7 + Vue3.5 + TS + AntD4 + Tailwind4 + 文件路由）。`@` → `src` 别名。`package.json` scripts 至少含：`dev`、`build`、`preview`、`gen:api`、`lint`、`lint:check`、`format`、`type-check`、`type-check:check`、`test`（vitest）、`test:e2e`（playwright）。
3. **代理**：`/api` → `http://localhost:8080`，**不 rewrite**。写完立刻用真实后端验证一次登录。**后端启动方式见下面「本机 dev 环境（只给指针）」一节，不要自己摸索、也不要信本文件之外的端口/口令数值**；若照做仍起不来，停下回报，**不要用 mock server 假装通过**。
4. **API 层**：`gen:api` 生成到 `src/api/axios`（`@hey-api/client-axios` + `sdk` + `typescript`，对齐参考项目的 `openapi-ts.config.ts`）。手写薄封装：
   - 请求拦截：注入 `Authorization: Bearer <access>`；
   - 响应拦截：统一解包后端 `ApiResponse`（`code`/`message`/`data` 结构，按生成类型来），业务错误码映射成可读提示（含登录锁定、限流降级、令牌黑名单等）；
   - 401 → refresh 单飞 → 重放；refresh 失败 → 清本地 + 跳登录。
5. **会话与状态**：Access / Refresh 落 `localStorage`；vuex store 持当前用户与角色；登出调后端登出接口（令牌进黑名单）再清本地。
6. **布局 + 文件路由 + 守卫**：`src/pages` 约定路由（`.page.vue`）；布局壳按角色（`ADMIN` > `TEACHER` > `STUDENT`）渲染侧边菜单；守卫处理未登录 → 登录页、越权 → 403 页。**不做强制改密前置**（`must_change_password` 后端无读写路径、契约里也没有该字段，已移出本阶段，另行立项 `add-auth-must-change-password`）；守卫入口请收敛成单一函数，便于日后接入。
7. **认证四页**：登录、注册（含邀请码）、找回密码（发码 + 重置）、修改密码。表单校验用 AntD 规则，错误提示覆盖上述业务错误码。
8. **测试**：
   - vitest：refresh 单飞（并发 3 个 401 只刷一次）、响应解包与错误映射、角色守卫（三种角色 + 越权）；
   - playwright：一条 `登录 → 首页 → 登出` 冒烟。**若 playwright 浏览器未安装导致跑不起来，停下回报**，不要擅自执行 `playwright install` 下载（涉及网络与磁盘，需授权）；此时改为交付用例代码 + 说明未执行原因，**并在回报里明确标注「e2e 未实跑」，不得声称通过**。
9. **质量门禁**：`lint:check`、`type-check:check`、`vitest` 三者全绿再提交。
10. **回归确认**：跑一次后端全量门禁（命令见「后端门禁命令」小节），按顶部判据确认——`Failures=0` 且 `Errors=0`、`Skipped` 保持 1、用例总数**不少于**开工时记录在案的数值——证明你没碰后端。不满足就停下回报。

## 本机 dev 环境（只给指针，不写端口 / 口令 / 表数）

联调必须打真实后端。**宿主端口、凭据、需要哪些环境变量、容器名、已知启动异常**这些事实的唯一出处是 `docs/指导Agent交接文档.md` §6.2，其底层真源是 `docker-compose.yml`（宿主端口以它的实际映射为准）与 `src/main/resources/application-dev.yml`（默认连接参数）。**本提示词故意不复制这些值**：它们已在本文件旧版本里错过一次（写成与 compose 不一致的宿主端口与口令），照抄会白耗一轮。需要时现场读文件，别信任何文档里的数值。

几条不随环境变化的硬规则（这些才是要照做的部分）：

- 依赖容器（MySQL 主/从、RabbitMQ）若为 exited 状态要先起起来（容器名见 `docker-compose.yml`），**不要起 compose 里的 Redis 容器**——宿主已有一套 Redis 在服务，两者抢同一端口；
- 启动成功与否只看健康端点：`/actuator/health` 返回 `status: UP`（db / rabbit / redis 各项）即算起来，**不要凭日志里有没有栈trace判成败**；
- **已知非致命异常**：`ExamSubmitSender.send` → `RabbitTemplate.waitForConfirmsOrDie` 抛 `IllegalStateException: This operation is only available within the scope of an invoke operation`（伴随「答案补发对账」日志）。这是 `spec/README.md` 遗留 #10，真 broker 下才暴露，**不要误判为启动失败、更不要顺手修**；
- dev 库 `exam_online` 是**有历史数据的存量库**（含一张历史垃圾表，见交接文档的禁忌清单）：**不要删表、不要动数据、不要直接改数据库**；
- 没有可用测试账号时，用 `/api/auth/register` + 管理员邀请码正常注册，**不要走后门**。

## 后端门禁命令（用于确认后端基线未被破坏）

仓库自有的**唯一门禁命令待 `update-agent-gate-single-source`（E2）B-2 回填**——目前仓库里对「哪条命令算门禁」存在互斥说法，本文件不再新增一种。

回填前的做法：按 `docs/指导Agent交接文档.md` 六（§6.1 及其上下文）的工具链小节自行拼装，并在回报里给出**实际执行的命令与原始输出**（不得引用文档里的数字当结果）。注意该小节记的是**特定 shell / 本机环境下的历史绕行办法**（含仓库外的 JDK 与 Maven 绝对路径），不是被指定的仓库命令，也不代表别的 shell 下同样必要——换环境时先自检 `mvn -version` 用的是哪个 JDK，再决定怎么拼，并把结论写进回报。

**必须带 `clean`**：`target/surefire-reports` 里的残留报告会把上一轮产物混进计数，不 clean 得到的总数不可信（本项目为此记过账）。用例总数只在「同一次带 clean 的执行」之间可比。

## Commit

按任务组分次提交，message 用中文描述、前缀 `feat(frontend)`，例如：

```
feat(frontend): 搭建 Vue3+TS+Vite 工程骨架与文件路由
feat(frontend): 契约生成 API 层与令牌续期单飞
feat(frontend): 角色布局守卫与认证四页
test(frontend): 补 refresh 单飞与守卫单测及登录冒烟
```

## 回报格式（按此七段，不要写散文）

1. **环境**：`node -v`、`pnpm -v`；参考项目你实际读了哪些文件、照搬了哪些配置
2. **工程骨架**：`frontend/` 目录树（两层即可）；`package.json` 依赖与 scripts 清单；与参考项目的**差异点逐条列出并说明为什么**
3. **代理验证**：代理配置原文；**真实登录请求的证据**（请求路径、HTTP 状态、返回体关键字段），证明没 rewrite 掉 `/api`
4. **API 层**：`gen:api` 命令与生成产物路径 / 文件数；手写薄封装清单；错误码映射覆盖了哪些业务错误
5. **会话与守卫**：refresh 单飞实现要点；角色菜单与守卫规则；**哪些单测覆盖了它们**
6. **质量门禁三结果**：`lint:check` / `type-check:check` / `vitest` 各自输出摘要（vitest 给出用例数三数字）；playwright **是否实跑**，未实跑要写明原因
7. **收尾**：`git rev-parse HEAD`（每个 commit 都列）、`git status --short`、后端全量回归的**实际执行命令 + 原始输出四数字**（`Tests run / Failures / Errors / Skipped`），并按判据自评：`Failures=0`、`Errors=0`、`Skipped=1`、总数不少于开工记录值；`git diff --stat` 证明后端零改动
8. **意外发现 / 接口缺口**（若有缺口，写清缺什么、你**没有**怎么绕过）

## 禁止

- 禁止改后端任何文件（含 `openapi.yaml`）；
- 禁止手写契约或手写与契约重复的接口定义；
- 禁止代理 rewrite 掉 `/api`；
- 禁止把 refresh 做成「每个 401 各自刷新」；
- 禁止提交 `node_modules/`、`dist/`、`test-results/`、`.env`（`.env.example` 可以）；
- 禁止用 mock server / mock 接口冒充「联调通过」；
- 禁止夹带阶段 20–23 的业务页面或 `echarts`；
- 禁止为了让 e2e 通过而下载浏览器（未授权网络/磁盘操作），也禁止因此声称 e2e 已通过；
- 禁止放宽 lint / type-check 规则来「转绿」（如大面积 `any`、关掉 `vue-tsc` 严格项）；确需调整必须在回报里逐条说明理由；
- 禁止勾 `tasks.json` 或归档 `spec/`。
