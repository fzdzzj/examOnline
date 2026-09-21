# 规范差异：frontend（强制改密前端守卫）

本文件包含对 `spec/specs/frontend/spec.md` 的规范变更（新增；接回阶段 19 因契约缺字段而移出的「强制改密前置」需求）。

## ADDED Requirements

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
