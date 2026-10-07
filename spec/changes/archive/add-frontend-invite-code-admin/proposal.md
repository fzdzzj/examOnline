# 提案：邀请码管理页（add-frontend-invite-code-admin，前端台账 F-4 最小闭环）

## Why

管理端控制台整体缺失（前端台账 F-4，登记于 `docs/frontend-improvement-candidates.md`）：`/admin` 路由分区自阶段 19 预留至今（`frontend/src/router/access.ts` 的 `{ prefix: '/admin', roles: ['ADMIN'] }`），而 `frontend/src/pages` 下不存在任何 admin 页面；教师注册必须输入「由管理员发放的一次性邀请码」（`register.page.vue` 校验必填），而发放渠道 `listInviteCodes`/`createInviteCode`/`invalidateInviteCode`（`openapi.yaml` 与生成 SDK 中均已存在）零前端消费——当前管理员只能直接操作数据库发码，注册流程的运营闭环断在中间。本卡取台账建议的最小集：仅「邀请码管理」（列表 / 生成 / 作废三动作，端点齐备），属「接口缺口为零、纯前端接线」的收口；审计日志查询与踢人两组端点维持零消费（F-4 范围另议）。

## What Changes（关键裁决：范围=仅邀请码三动作；端口按后端字段渲染，作废入口 fail-closed）

1. **新页 `frontend/src/pages/(dashboard)/admin/invite-codes/index.page.vue`**：
   - 列表（Table）：ID / 邀请码 / 备注 / 状态 / 已使用次数 / 创建时间 / 操作——状态按后端 `status` 字段渲染（0=有效、其余按「已作废」呈现，不本地推算），作废入口仅在有效行显示（fail-closed）；
   - 页头「生成邀请码」按钮 → Modal 表单（字段按契约 `InviteCodeCreateRequest`：仅 `note` 备注、可选）→ 提交调 `createInviteCode`（note 为空不携带该字段，对齐创建页「空描述不携带」既有口径），**成功后同一 Modal 内展示新码并可复制**（Typography `copyable`）——关闭后走列表刷新；
   - 逐行「作废」→ 声明式确认弹窗（形态对齐考试删除：`v-model:open` Modal + warning Alert 明示不可逆，正文走 `#message` 具名插槽）→ 确认调 `invalidateInviteCode`（path 携带 id）；
   - 查询失败：错误 Alert 承载后端 message 显性呈现（对齐刚合入的「查询失败与业务态分离」Requirement），失败不伪装空态；成功且无数据显示空态；
   - 生成 / 作废失败 message 一律呈现后端原文（`error instanceof Error ? error.message : 兜底`），不本地编造、不拦截请求；
   - 生成 / 作废成功 `queryClient.invalidateQueries({ queryKey: ['invite-codes'] })`。
2. **ADMIN 角色侧边栏入口**：`(dashboard).page.vue` 导航把 `/admin` 分区由 disabled 占位「管理端（未开放）」改为真实分组（角色过滤沿用 `canAccess(role, '/admin/')`），子项「邀请码管理」落 `/admin/invite-codes`；`NAVIGABLE_PATHS` 增补该路径。
3. **`access.ts` 分区结构零改动**：新页面路径 `/admin/invite-codes` 落在既有 `/admin` 前缀分区（roles: ['ADMIN']）内，路由守卫与 403 行为零改动。
4. **零后端改动**：`src/main`、`src/test`、`pom.xml`、`schema.sql`、`openapi.yaml` 均不动，零契约变更零 `gen:api`；SDK 函数名与请求 / 响应字段全部取自 `frontend/src/api/axios/types.gen.ts` 契约（`listInviteCodes` / `createInviteCode` / `invalidateInviteCode`），不手写 URL。

## Impact

### 受影响的规范
- `spec/specs/frontend/spec.md` — ADDED：邀请码管理界面 Requirement（列表按后端字段渲染 / 生成后新码一次性可见可复制 / 作废知情确认 / 查询失败不伪装空态 / 失败原文呈现不本地编造）。

### 受影响的文件
- 新增 `frontend/src/pages/(dashboard)/admin/invite-codes/index.page.vue`（页面）；
- 新增 `frontend/src/pages/(dashboard)/admin/invite-codes/__tests__/inviteCodeAdmin.spec.ts`（用例）；
- `frontend/src/pages/(dashboard).page.vue`（admin 分组真实化 + NAVIGABLE_PATHS）；
- 注册页、`api/errorMap.ts`、`router/access.ts` 与其余既有页面零改动。

### 需要迁移
- 无（零表变更、零后端、零契约变更）。

## 边界与不做

- 不接审计日志查询与踢人（F-4 另两组端点继续零消费，台账保留）；不做用户管理 / 课程管理（后端无端点）；
- 不做邀请码编辑（后端无端点）；不做分页（`listInviteCodes` 返回全量数组、无分页参数）；
- 不作废状态回滚（后端只有 0→1）；
- 不本地编造失败文案；不在前端维护第二套状态推断（status 按后端返回值渲染）;
- 注册页与 `api/errorMap.ts` 零改动。

## 验收判据

- 先红后绿：新用例实施前跑一次留红（行为断言），实施后转绿；
- 实施笔仅含 `frontend/src`；收口笔仅含 `spec/`（`git diff --name-only <实施笔> HEAD -- src frontend pom.xml openapi.yaml` 为空）；
- 前端三项退出码 0，vitest 相对基线（52 文件 421 例 @ `c762508` 当次实测）只增不减；
- 仓库根 `mvnw.cmd clean test` 与基线（368/0/0/1 @ `c762508` 当次实测）持平——本卡零后端改动；
- `git status` 终态除白名单未跟踪文件外干净。
