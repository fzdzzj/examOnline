# 提案：前端骨架与认证（阶段 19，方向①）

## Why

后端阶段 1–17 已完成纯后端能力（14 个 Controller，210 测试全绿），但**本仓库无前端**——面试演示只能靠 curl/Postman，无法回答「学生怎么答题、教师怎么批改」的体验层追问。

方向①负责把前端从零立起来并打通认证，是后续四个方向（教师端命题、教师端考务、学生端考试、考后闭环）的公共底座。**底座错了后面全返工**，所以本阶段只做骨架 + 认证，不碰业务页面。

**技术栈对齐参考项目 `D:\code\crm\font\crm-front`（已核实 package.json / vite.config.ts）**：Vue 3.5 + TypeScript + Vite 7 + Ant Design Vue 4.2 + Tailwind 4 + 文件路由（unplugin-vue-router，`.page.vue`）+ @tanstack/vue-query + axios + @hey-api 生成客户端 + vuex + dayjs；工程侧 eslint 9 + prettier + vue-tsc + vitest + playwright，包管理 pnpm。

**前置依赖**：阶段 18 `add-backend-openapi`（openapi.yaml 契约来源）必须先合入。

## What Changes

新建 `frontend/` 目录（同仓库，非独立仓库）：

1. **工程骨架**：Vite + Vue3 + TS，pnpm；tsconfig 分层（app/config/node）；`@` → `src` 别名；eslint + prettier + vue-tsc type-check + vitest 可跑；
2. **dev 代理**：`/api` → `http://localhost:8080`。**注意与 CRM 的差异：examOnline 后端路径本身带 `/api` 前缀，代理不得 rewrite 掉 `/api`**（CRM 的 `rewrite: path.replace(/^\/api/,'')` 在此会 404）；
3. **API 层**：`gen:api` 从 `openapi.yaml` 生成到 `src/api/axios`（@hey-api/client-axios + sdk + typescript）；手写薄封装：请求拦截注入 Bearer、响应拦截统一解包 `ApiResponse`、401 触发 refresh 单飞（并发请求只刷一次）、refresh 失败登出；
4. **Token 存储与会话**：Access/Refresh 落 localStorage；vuex store 持当前用户与角色；登出调后端黑名单接口并清本地；
5. **文件路由 + 布局**：`src/pages` 约定路由；按角色（ADMIN > TEACHER > STUDENT）渲染菜单与路由守卫，越权路由跳 403 页；
6. **认证页面**：登录、注册（含邀请码）、找回密码（发码 + 重置）、修改密码；错误提示覆盖锁定/限流降级/黑名单等业务错误码；
7. **冒烟 e2e**：playwright 一条「登录 → 进入首页 → 登出」链路。

## Impact

### 受影响的规范
- `spec/specs/frontend/spec.md` — ADDED（新能力域）：前端工程底座、会话与鉴权集成、角色路由守卫。

### 受影响的文件
- `frontend/**`（全新目录，不触碰 `src/main`、`src/test`、`pom.xml`）
- `docs/指导Agent交接文档.md`（前端启动方式与代理约定）

### 需要迁移
- [ ] 数据库迁移
- [ ] 业务 API（本阶段不改后端）

## 时间线评估

中：约 2–3 天（含工程脚手架、拦截器与守卫、认证四页、e2e）。

## 风险

- **代理 rewrite 差异**：见上，必须保留 `/api` 前缀，实施后先验证一条真实登录请求再往下做；
- **生成客户端与后端 DTO 漂移**：契约来自阶段 18 导出的 `openapi.yaml`，后端改接口必须重新导出；本阶段不改后端；
- **Refresh 单飞**：并发 401 时若各自刷新会触发「Refresh 复用检测」导致全端下线——必须做单飞队列，且有单测覆盖；
- **角色层级**：后端 `@RequireRole` 是权威，前端守卫只为体验，不得作为安全边界，代码注释写明；
- **不引入**：echarts 图表、考试相关页面、任何业务模块——留到后续方向，避免底座阶段膨胀；
- **前端测试基线独立**：`frontend` 的 vitest/playwright 不并入 Maven surefire 计数（后端 210 基线不受影响），CI 分开跑。
