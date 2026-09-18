# 提案：接通 must_change_password（初始密码强制修改）

## Why

`must_change_password` 目前是一个**完全死掉的列**——从来不置 1、从来读不到。

**已核实（2026-09-18，指导 agent grep）**：

- `src/main` 中 `mustChangePassword` / `must_change_password` **仅 2 处命中**：
  - `src/main/resources/schema.sql` L14 建列 `must_change_password TINYINT NOT NULL DEFAULT 0`；
  - `src/main/java/com/exam/user/entity/User.java` L39 实体字段 `private Integer mustChangePassword;`
- **无读路径**：零 getter 调用点、无 DTO 装载、`JwtUtil` 签发的 claim 不含该字段（claim 只有 `username / name / email / roles / permissions / roleLevel / sessionVersion`）；
- **无写路径**：`AdminInitializer` 与 `AuthService` **根本没有引用该字段**（否则 grep 会命中），插入时靠列默认值 0，即**永不置 1**；
- `CurrentUserResponse` 实测只有 `id / username / name / email / roles / permissions`；`openapi.yaml` 全契约无该字段。

**连带影响**：前端阶段 19 `add-frontend-skeleton-auth` 原提案里的「强制改密前置」Requirement（含 2 个 Scenario）因此在后端语义上**不可判定**——即使前端加了守卫也是永不触发的死代码，e2e 无法验证。该 Requirement 已从阶段 19 移出，移到本变更。

**为什么值得做**：这是《需求规格说明书》§1.4「管理员初始化（启动自动建 admin 账号）」与 §4.1 安全必须项的直接配套——**系统自带的初始密码若不被强制更换，等于长期留一个已知弱口令的超级账号**。当前 admin 由 `AdminInitializer` 用配置的初始密码创建，任何人都能读到该默认值，却永远不被要求改。这是一条真实的安全缺口，不是「为了点功能」。

**期望状态**：`/api/auth/me` 能返回 `mustChangePassword`；`AdminInitializer` **首次创建** admin 时置 1；`/api/auth/password/change` 成功后置 0；前端据此实现强制改密守卫（属阶段 19 之后的前端补回，不在本变更内）。

## What Changes

1. `CurrentUserResponse` 增加 `mustChangePassword`（Boolean），`AuthService.currentUser()` 从 User 装载；
2. `AdminInitializer`：**仅在首次创建 admin 时**置 `mustChangePassword = 1`；admin 已存在时**不得改写该字段**（否则每次重启都把已改过密码的 admin 打回强制改密，形成死循环）；
3. `/api/auth/password/change` 成功后置 `mustChangePassword = 0`；
4. 重新导出 `openapi.yaml`（契约必须同步，否则前端生成不到该字段）；
5. 集成测试：新建上下文里 admin 的 `me` 返回 `mustChangePassword=true` → 改密 → 再查为 `false`；并断言**重复启动不改写已存在 admin 的该标记**。

## Impact

### 受影响的规范
- `spec/specs/authentication/spec.md` — ADDED：初始密码强制修改标记。

### 受影响的文件
- `src/main/java/com/exam/auth/dto/CurrentUserResponse.java`
- `src/main/java/com/exam/auth/service/AuthService.java`（`currentUser()` 装载 + 改密成功置 0）
- `src/main/java/com/exam/.../AdminInitializer.java`（仅创建时置 1）
- `openapi.yaml`（重新导出）
- `src/test/java/...`（新增集成测试）

### 需要迁移
- [ ] **零 DDL、零数据迁移**：列已存在。存量库里已有 admin 的该值为 0（历史默认），**不追溯置 1**——追溯会把既有部署的管理员突然锁进强制改密，属破坏性变更。手工演示时如需触发，由演示者自行 `UPDATE users SET must_change_password=1 WHERE username='admin'`，**不得写成迁移脚本**。
- [ ] 业务 API：`/api/auth/me` 响应体**新增字段**（向后兼容，不删不改既有字段）

## 时间线评估

小：约 0.5 天。

## 风险

- **重启死循环**：见 What Changes 2。这是本变更最大的坑，必须有测试守住「admin 已存在时不改写该标记」；
- **claim 还是 DTO**：不要把该标记塞进 JWT claim。它是**可变状态**，塞进无状态 token 会出现「已改密但旧 token 仍说必须改密」的窗口，只能靠黑名单/`sessionVersion` 兜。放 `CurrentUserResponse` 由 `/api/auth/me` 实时读库，语义才正确。代码注释要写明这个取舍；
- **不改鉴权拦截器**：本变更只暴露标记，**不在后端拦截「未改密却调业务接口」**。后端强拦会波及所有既有集成测试且属行为变更；前端守卫 + 后端可读标记已足够表达该策略。若日后要后端强拦，单独立项；
- **不动 `schema.sql`、不加迁移**：列已存在；
- **测试基线只增不减**：当前 `Tests run: 213, Failures: 0, Errors: 0, Skipped: 1`（Skipped 1 为契约导出方法受 `exportContract` 开关控制，属设计使然）。本变更新增测试后总数上调，`Skipped` 保持 1；
- **契约必须重新导出**：改完 DTO 不导出 `openapi.yaml` 等于前端拿不到字段，本变更等于白做。
