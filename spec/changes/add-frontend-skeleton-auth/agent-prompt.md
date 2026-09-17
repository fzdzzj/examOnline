# 子 agent 提示词 —— `add-frontend-skeleton-auth`（阶段 19，前端方向①）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/add-frontend-skeleton-auth/proposal.md`、`tasks.json`、`specs/frontend/spec-delta.md`。

> **⚠️ 基线更正（以此为准，覆盖下文所有旧数字）**：阶段 18 已归档，后端全量基线现为 `Tests run: 213, Failures: 0, Errors: 0, Skipped: 1` → BUILD SUCCESS。下文出现的「210」一律读作 **213**；`Skipped: 1` 是契约导出方法受 `exportContract` 开关控制，**属设计使然，不是被禁用的断言，不要试图消除它**。本阶段结束时后端必须仍是 213 全绿。

---

## 现状

- 仓库 `D:\code\examOnline`，分支 `feature/add-performance-deepening-readwrite`。
- 后端是**已完成的纯后端单体**（Java 17 + Spring Boot 3.5.5，14 个 Controller 全挂 `/api/**`，全量测试基线 **210 全绿**）。**本仓库当前没有前端**，你要从零建 `frontend/`。
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

1. **本阶段不改后端**：`src/main/**`、`src/test/**`、`pom.xml`、`openapi.yaml`、`docker/**` 一律**零改动**。后端测试基线 210 必须不变。发现接口缺口（如缺字段、缺端点）**停下回报**，不要在前端用多次请求拼凑绕过、不要 mock 掉当作已完成。
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
3. **代理**：`/api` → `http://localhost:8080`，**不 rewrite**。写完立刻用真实后端验证一次登录。**后端启动方式见下面「本机 dev 环境（已实测）」，不要自己摸索**；若照做仍起不来，停下回报，**不要用 mock server 假装通过**。
4. **API 层**：`gen:api` 生成到 `src/api/axios`（`@hey-api/client-axios` + `sdk` + `typescript`，对齐参考项目的 `openapi-ts.config.ts`）。手写薄封装：
   - 请求拦截：注入 `Authorization: Bearer <access>`；
   - 响应拦截：统一解包后端 `ApiResponse`（`code`/`message`/`data` 结构，按生成类型来），业务错误码映射成可读提示（含登录锁定、限流降级、令牌黑名单等）；
   - 401 → refresh 单飞 → 重放；refresh 失败 → 清本地 + 跳登录。
5. **会话与状态**：Access / Refresh 落 `localStorage`；vuex store 持当前用户与角色；登出调后端登出接口（令牌进黑名单）再清本地。
6. **布局 + 文件路由 + 守卫**：`src/pages` 约定路由（`.page.vue`）；布局壳按角色（`ADMIN` > `TEACHER` > `STUDENT`）渲染侧边菜单；守卫处理未登录 → 登录页、越权 → 403 页；`must_change_password` 为真时**任何业务路由都重定向到改密页**。
7. **认证四页**：登录、注册（含邀请码）、找回密码（发码 + 重置）、修改密码。表单校验用 AntD 规则，错误提示覆盖上述业务错误码。
8. **测试**：
   - vitest：refresh 单飞（并发 3 个 401 只刷一次）、响应解包与错误映射、角色守卫（三种角色 + 越权）、`must_change_password` 重定向；
   - playwright：一条 `登录 → 首页 → 登出` 冒烟。**若 playwright 浏览器未安装导致跑不起来，停下回报**，不要擅自执行 `playwright install` 下载（涉及网络与磁盘，需授权）；此时改为交付用例代码 + 说明未执行原因，**并在回报里明确标注「e2e 未实跑」，不得声称通过**。
9. **质量门禁**：`lint:check`、`type-check:check`、`vitest` 三者全绿再提交。
10. **回归确认**：跑一次后端全量（命令见下），确认仍是 **210 全绿**——证明你没碰后端。

## 本机 dev 环境（指导 agent 已实测可用，照做即可）

**`application-dev.yml` 的默认值连不上**：默认 `127.0.0.1:3306` + `root/root` 指向 Windows `MySQL80` 服务，该服务**拒绝 root/root**（阶段 18 第 1 轮子 agent 就卡在这里，误报成「环境坏了」）。真实可用的是 Docker 容器：

| 组件 | 宿主端口 | 凭证 |
|---|---|---|
| `exam-mysql-master` | **13306** | `root/root123`，库 `exam_online` |
| `exam-mysql-slave` | **3307** | `root/root123` |
| `exam-rabbitmq` | 5672 | — |
| Redis | 6379 | 宿主 Windows Redis 服务；**不要启 `exam-redis` 容器，会端口冲突** |

容器若 exited：`docker start exam-mysql-master exam-mysql-slave exam-rabbitmq`（**不要启 `exam-redis`**）。启动应用（PowerShell，必须先设环境变量，java 参数必须数组 splatting）：

```powershell
$env:DB_URL='jdbc:mysql://127.0.0.1:13306/exam_online?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true&useSSL=false&allowPublicKeyRetrieval=true'
$env:DB_USERNAME='root'
$env:DB_PASSWORD='root123'
$env:SLAVE_DB_URL='jdbc:mysql://127.0.0.1:3307/exam_online?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true&useSSL=false&allowPublicKeyRetrieval=true'
$env:SLAVE_DB_USERNAME='root'
$env:SLAVE_DB_PASSWORD='root123'
$jargs = @('-classpath','D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar','-Dclassworlds.conf=D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf','-Dmaven.home=D:\develop\Maven\apache-maven-3.9.4','-Dmaven.multiModuleProjectDirectory=D:\code\examOnline','org.codehaus.plexus.classworlds.launcher.Launcher','-o','spring-boot:run','-Dspring-boot.run.profiles=dev')
& 'D:\develop\jdk177\bin\java.exe' @jargs
```

启动成功的判据是 `Invoke-WebRequest http://localhost:8080/actuator/health` 返回 200 且 `status":"UP"`（db / rabbit 3.13.7 / redis 全 UP）。

**启动日志里的已知异常，不要误判为启动失败、更不要顺手修**：`ExamSubmitSender.send` → `RabbitTemplate.waitForConfirmsOrDie` 抛 `IllegalStateException: This operation is only available within the scope of an invoke operation`，伴随 `答案补发对账: 待补=2 已补=0`。这是遗留 #10（真 broker 下才暴露、非致命），health UP 即视为启动成功。

登录验证可用的账号：dev 库 `exam_online` 已有历史数据（26 张表，含垃圾表 `rep_test`，**不要删表、不要动数据**）。若没有可用测试账号，用 `/api/auth/register` + 管理员邀请码正常注册一个，**不要直接改数据库**。

## 本机 Maven 命令（用于确认后端基线未被破坏，必须照抄）

PowerShell 下**必须用数组 splatting**，否则 `-Dclassworlds.conf=...` 会被拆坏：

```powershell
$jargs = @('-classpath','D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar','-Dclassworlds.conf=D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf','-Dmaven.home=D:\develop\Maven\apache-maven-3.9.4','-Dmaven.multiModuleProjectDirectory=D:\code\examOnline','org.codehaus.plexus.classworlds.launcher.Launcher','-o','test'); & 'D:\develop\jdk177\bin\java.exe' @jargs
```

不要用 PATH 里的 `mvn`；不要把交接文档里的多行反引号版本压成一行。

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
5. **会话与守卫**：refresh 单飞实现要点；角色菜单与守卫规则；`must_change_password` 处理；**哪些单测覆盖了它们**
6. **质量门禁三结果**：`lint:check` / `type-check:check` / `vitest` 各自输出摘要（vitest 给出用例数三数字）；playwright **是否实跑**，未实跑要写明原因
7. **收尾**：`git rev-parse HEAD`（每个 commit 都列）、`git status --short`、后端全量回归三数字（应仍为 210）、`git diff --stat` 证明后端零改动
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
