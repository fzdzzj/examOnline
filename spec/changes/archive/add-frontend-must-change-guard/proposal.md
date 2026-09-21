# 提案：前端强制改密守卫接回（前后端优化组合·提案④，P1）

## Why

后端 `add-auth-must-change-password`（阶段 18+）已归档：`/api/auth/me` 恒有值返回 `mustChangePassword`，改密成功置 0，`AdminInitializer` 仅首次创建置 1。但**前端守卫至今未接**（遗留 #11）：阶段 19 曾因契约缺字段把「强制改密前置」Requirement 移出；契约现已就绪（`frontend/src/api/axios/types.gen.ts` 已含该字段，全前端仅此一处声明、零使用），守卫入口也已收敛为 `frontend/src/router/access.ts` 的单一函数 `decideNavigation`——接线条件全部具备，只差最后一步。

在守卫落地前，没有任何东西阻止未改密的 admin 继续使用系统，「初始密码强制修改」这项用户可感知的能力不得声称完成（遗留 #11 的原文判据）。

**前置**：无（前端独立小变更，不占阶段号，不塞进阶段 19–23 串行链；执行仍受「同一工作树不得并行跑两个子 agent」约束）。

## What Changes

1. `decideNavigation` 增加强制改密分支：会话信息中 `mustChangePassword === true` 时，可导航范围限制在改密页与登出，其余路由重定向到改密页；
2. 判定数据来自既有 `/api/auth/me` 会话查询——字段恒有值（`Boolean` 装载、`non_null` 不会吞掉 `false`），前端直接按布尔值分支，不新增后端接口；
3. 改密页 `frontend/src/pages/change-password.page.vue` 已存在：改密成功后走既有会话刷新路径，守卫随新会话数据自动放开；
4. 阶段 19 被移出的「强制改密前置」Requirement 由本变更的 spec-delta 接回 `frontend` 能力域；
5. `frontend/src/router/__tests__/access.spec.ts` 补守卫分支单测。

## Impact

### 受影响的规范
- `spec/specs/frontend/spec.md` — ADDED：强制改密前端守卫。（能力域目录由首个收尾的前端变更创建——`spec/README.md` 已声明这是正常状态，不预建空壳。）

### 受影响的文件
- `frontend/src/router/access.ts`（`decideNavigation` 增加分支）
- `frontend/src/router/__tests__/access.spec.ts`（补单测）
- 改密页 / 会话查询如需联动的小幅调整（限 `frontend/src/**`）

### 需要迁移
- [ ] 数据库迁移
- [ ] 业务 API（契约已就绪，无需后端改动；若发现缺口停下回报）

## 时间线评估

小：约 0.5–1 天（单一入口接线 + 单测）。

## 风险

- **不改后端**：`src/main`、`src/test`、`pom.xml` 零改动；
- 守卫只认 `/api/auth/me` 的权威字段，**不在前端本地持久化「已改密」标记**（会话过期/刷新即重判，不给绕过留缓存）；
- 后端刻意不做接口级强拦、刻意不入 JWT claim 是既有决策（可变状态不进无状态 token）——前端守卫是**体验层拦截**，直接调 API 的绕过不在本变更范围，也不得顺手去改鉴权拦截器；
- 不回头修改阶段 19 的历史文档（Requirement 移出是当时的事实，接回由本变更自己的 spec-delta 承载）；
- 交验证据三要素（实际执行的命令 + 该次原始输出 + 当时短 revision），自述产物不算交付证据。
