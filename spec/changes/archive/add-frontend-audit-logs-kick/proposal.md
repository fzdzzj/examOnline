# 提案：审计日志查询与强制下线（add-frontend-audit-logs-kick，前端台账 F-4 第二阶段）

## Why

前端台账 F-4「管理端控制台整体缺失」的第一阶段（`add-frontend-invite-code-admin`，合并笔 31dfc17）只收了邀请码三端点，并在归档里明确留下「审计日志与踢人两组端点维持零消费，需要时另立变更」。这两组端点在后端与契约里早已齐备：

- `GET /api/admin/audit-logs`（`auditLogs`，`openapi.yaml` 带 `page`/`size`/`username`/`action` 四个查询参数）——`AuditLogService` 已把登录成功/失败与账户锁定**同步**写入 `audit_log` 表（注释自陈旧实现因异步边界丢 MDC/SecurityContext 而两处静默失效，现已收口），`AdminController` 类级 `@RequireRole(ADMIN)` 独享该全局视图；前端却没有任何入口能读到这些安全事件，管理员只能去查库或翻日志。
- `POST /api/admin/users/{userId}/kick`（`kickUser`，`AuthService.kickUser` 使目标用户全部会话失效）——权限点 `user:manage` 已在后端就位，前端零消费，意味着「发现异常登录后顺手把账号下线」这条运营动作在 UI 上根本不存在。

两端点的 SDK 函数与契约类型（`AuditLogResponse` = `id/traceId/userId/username/action/status/ipAddress/details/createdTime`；`KickUserData.path.userId`）都已由 `gen:api` 生成，属「接口缺口为零、纯前端接线」的收口，故本卡一次性把两组端点接成同一个管理页面：审计台账可查、可疑账号可当场下线。

## What Changes（关键裁决：单页合一；action 用文本输入；分页走下界推断）

1. **新页 `frontend/src/pages/(dashboard)/admin/audit-logs/index.page.vue`**（路由 `/admin/audit-logs`）：
   - 列表（Table）：ID / 用户名 / 动作 / 状态 / 链路 ID / IP / 时间 / 详情 / 操作——`action`、`status` 一律按后端返回字符串直渲染（状态用 Tag 区分 SUCCESS/FAILURE/WARNING 色彩，语义仍以返回值为准），前端 SHALL NOT 维护第二套状态机或据 `username` 反推；`createdTime` 走 dayjs `YYYY-MM-DD HH:mm`；
   - 筛选：用户名 `Input` + 动作 `Input`，二者共用 300ms 防抖（形态照抄 `teacher/exams/index.page.vue` 的 `debouncedTitle` + `setTimeout` + `watch` 回第 1 页），`queryKey` 纳入 `[pageNum, pageSize, debouncedUsername, debouncedAction]`，空值不携带该参数（对齐「空描述不携带」既有口径）；
   - **动作筛选用文本输入而非 Select**（裁决依据）：`openapi.yaml` 里 `action` 是裸 `type: string`、`AuditLogsData.query.action` 为 `string | undefined`，**契约无枚举**；后端 `AuditLogService.page` 对该参数走 `eq`（精确匹配）。`AuditLog` 实体虽声明了四个 `ACTION_*` 常量，但当前只有 `LOGIN` 与 `ACCOUNT_LOCKED` 有写入点，另两个（`PERMISSION_CHANGE`/`JWT_VALIDATION`）在 `src/` 内零引用——把这份清单固化成下拉选项，等于把「常量表」伪装成「契约枚举」，一旦后端补写入点或改名，下拉就静默漏项且比文本框更难发现。故取文本输入 + placeholder 提示现存取值，精确匹配由后端裁决；
   - 服务端分页（**下界推断**）：`auditLogs` 响应是 `ApiResponse<List<AuditLogResponse>>`，信封只有 `code/message/data`、**无 `total`**。分页 `total` 照抄 `teacher/papers/index.page.vue` 的下界推断式 `(pageNum-1)*pageSize + rows.length + (rows.length === pageSize ? 1 : 0)`，**不照抄考试页的字面 `total: pageNum * pageSize`**——按 antd 的 `calculatePage = floor((total-1)/pageSize)+1`，满页时后者算出 1 页、下一页按钮根本点不到（该坑已在 papers 页注释里登记）；`size` 取 20（后端默认值，上限 100 由 `@Max` 与 `Math.min` 双兜底），`showSizeChanger: false`；
   - 查询失败：错误 Alert（`data-test="audit-logs-error"`）承载后端 message 显性呈现，Table 走 `v-else` 隐藏——失败不伪装「暂无数据」空态（对齐已合入的「查询失败与业务态分离」Requirement）；成功且空数组时空态照常呈现；
   - 行级「强制下线」：`Button danger` 且 `v-if` 仅在该行 `userId` 存在时渲染（账户锁定等系统事件行 `user_id` 为 NULL，没有可下线的对象）→ **危险确认弹窗**（形态对齐考试删除 / 邀请码作废：`v-model:open` 声明式 `Modal` + warning `Alert`，正文走 `#message` 具名插槽）文案明示「该用户所有会话立即失效，需重新登录」「会话断开后不可恢复」→ 确认调 `kickUser`（path 携带该行 `userId`）；
   - 成功：`message.success` + 关弹窗 + `queryClient.invalidateQueries({ queryKey: ['audit-logs'] })`；失败：`message.error(error instanceof Error ? error.message : 兜底)` 原文呈现，不失效查询、不拦截请求、不本地编造。
2. **ADMIN 侧边栏入口**：`frontend/src/pages/(dashboard).page.vue` 的 `admin-section` children 增补 `{ key: '/admin/audit-logs', label: '审计日志' }`，并把该路径加入 `NAVIGABLE_PATHS` 白名单（菜单项 key 与白名单两处，与「邀请码管理」同构）。
3. **`access.ts` 分区结构零改动**：`/admin/audit-logs` 落在既有 `{ prefix: '/admin', roles: ['ADMIN'] }` 分区内，路由守卫与 403 行为零改动。
4. **零后端改动**：`src/main`、`src/test`、`pom.xml`、`schema.sql`、`openapi.yaml` 全部不动，零契约变更零 `gen:api` 重生成；两端点均走生成 SDK（`auditLogs` 带 query 参数、`kickUser` 走 path 参数），不手写 URL、不手写类型（硬约定 3）。

## Impact

### 受影响的规范

- `spec/specs/frontend/spec.md` — ADDED：审计日志查询与强制下线界面 Requirement（列表按后端字段渲染 / 失败不伪装空态 / 无 total 的下界分页 / 筛选透传+防抖+回第 1 页 / 仅 userId 行呈现下线入口 / 下线知情确认明示会话失效 / 失败原文呈现）。

### 受影响的文件

- 新增 `frontend/src/pages/(dashboard)/admin/audit-logs/index.page.vue`（页面）；
- 新增 `frontend/src/pages/(dashboard)/admin/audit-logs/__tests__/auditLogsKick.spec.ts`（用例）；
- `frontend/src/pages/(dashboard).page.vue`（admin 分组 children + `NAVIGABLE_PATHS`）；
- `frontend/src/router/access.ts`、`(dashboard)/index.page.vue` 落地页入口卡与其余既有页面零改动。

### 需要迁移

- 无（零表变更、零后端、零契约变更）。

## 边界与不做

- **不接 `dlq` / `replay`**（`/api/admin/mq/dlq/*`）：沿用既有取舍——重投属运维动作，需真 broker 往返证据，不做成后台按钮（`add-dlq-observability-and-replay` 归档的遗留口径）；
- 不做用户管理 / 课程管理页（后端无对应端点，属前后端整体缺口，另立后端卡）；
- 不接审计日志导出、不做时间区间筛选（后端 `page` 注释自陈刻意不提供 from/to）；
- 不给动作筛选造枚举下拉（理由见裁决 1「契约无枚举」）；不接 `status` 筛选参数（后端 `page` 未接受该参数，加了只会得到被忽略的入参）；
- 不做「批量踢人」与「按用户聚合视图」——单行确认即可覆盖诉求；
- 不在前端判定谁能被踢（`user:manage` 权限点与目标有效性由后端裁决，越权失败原文呈现）；
- 不落地页入口卡（本卡只按台账要求补侧边栏导航，入口卡扩张属另一处体验决策）；
- 不本地编造失败文案、不缓存角色。

## 验收判据

- 先红后绿：新用例在实施前跑一次留红（红灯须恰为新用例，且跑在最终版 spec 上），实施后转绿；
- 实施笔仅含 `frontend/src`；归档笔仅含 `spec/`（`git diff --name-only <实施笔> HEAD -- src frontend pom.xml openapi.yaml` 为空）；
- 前端 `lint:check` / `type-check:check` / `test` 三项退出码 0，vitest 相对基线（62 文件 480 例 @ `ab2dcd3` 当次实测）只增不减；
- 仓库根 `JAVA_HOME=D:\develop1\jdk21` + `./mvnw.cmd clean test` 与基线（374/0/0/1 @ `ab2dcd3` 当次实测）持平——本卡零后端改动；
- 防抖 / 回第 1 页 / 下界分页 / 仅 userId 行呈现入口四类断言做变异校验（撤对应实现即红，恢复即绿）；
- `git status` 终态除 7 个白名单未跟踪文件外干净。
