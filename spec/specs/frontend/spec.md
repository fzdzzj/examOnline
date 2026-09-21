# frontend 规范

> 能力域：前端（阶段 19 起的前端系列）。
> 来源：`spec/changes/archive/add-frontend-must-change-guard` 合入（强制改密前端守卫，提案④，2026-09-21；接回阶段 19 因契约缺字段而移出的「强制改密前置」需求）。
> 实施注记：
> - 本文件由**首个收尾的前端变更**创建（spec/README.md 约定：谁先收尾谁建目录）；阶段 19–23 五份 spec-delta 待各自验收收尾后按 Requirement 标题逐个追加，不预建空壳。
> - 判定收敛在 `frontend/src/router/access.ts` 的 `decideNavigation` 单一函数，`guard.ts` 只做接线；前端守卫不是安全边界，后端 `@RequireRole` 才是权限的唯一裁决者。
> - `mustChangePassword` 来自 `/api/auth/me`（`Boolean` 装载、恒有值、`non_null` 不会吞掉 `false`）；守卫按 `=== true` 分支，缺省按 false 处理，不误拦正常用户。
> - 不本地持久化「已改密」标记；改密成功后走既有会话刷新路径（`clearTokens → login → fetchProfile`）自动放开，会话刷新即重判。
> - 单测在 `frontend/src/router/__tests__/access.spec.ts`（三分支 + 不误拦用例）。

## Requirements

### Requirement: 强制改密前端守卫

WHEN 已登录用户的会话信息中 `mustChangePassword` 为 true,

系统 SHALL 将其可导航范围限制在改密与登出，且 SHALL 在改密完成后自动恢复导航能力。

#### Scenario: 未改密用户被拦

GIVEN admin 以初始密码登录且未改密

WHEN 尝试导航到业务页面

THEN 被重定向到改密页

AND 不渲染业务功能

#### Scenario: 改密完成后恢复

GIVEN 用户在改密页完成改密

WHEN 会话信息刷新

THEN 守卫按新会话数据放开

AND 可正常导航

#### Scenario: 判定只有单一入口

GIVEN 任意路由切换

WHEN 守卫求值

THEN 判定只发生在路由守卫的单一决策函数内

AND 不存在旁路入口

#### Scenario: 恒有值字段直接判断

GIVEN /api/auth/me 响应

WHEN 读取 mustChangePassword 字段

THEN 该字段恒有值

AND 前端直接按布尔值分支，false 不被误判为必须改密
