# 规范差异：frontend（前端工程底座与认证集成）

本文件包含对 `spec/specs/frontend/spec.md` 的规范变更（新能力域，新增）。

## ADDED Requirements

### Requirement: 前端工程底座

WHEN 需要为已交付的后端能力提供可演示的 Web 界面,

系统 SHALL 提供与后端同仓库的前端工程，且 SHALL 使构建、类型检查、代码规范检查与测试各自可独立跑通。

#### Scenario: 工程可复现启动

GIVEN 一份干净检出

WHEN 按记录命令安装依赖并启动开发服务

THEN 前端可访问且能通过代理调用后端接口

#### Scenario: 质量门禁可跑

GIVEN 前端代码存在

WHEN 运行 lint、type-check 与单元测试

THEN 三者均通过

#### Scenario: 前端基线不影响后端基线

GIVEN 前端测试与后端 Maven 测试并存

WHEN 各自运行

THEN 前端测试不计入后端 surefire 计数

AND 后端基线不因前端变更而下降

### Requirement: API 契约驱动集成

WHEN 前端调用后端接口,

系统 SHALL 以导出的 OpenAPI 契约生成类型化客户端，且 SHALL NOT 手写与契约重复的接口定义。

#### Scenario: 客户端由契约生成

GIVEN openapi.yaml 存在

WHEN 运行生成命令

THEN 产出类型化客户端供页面调用

#### Scenario: 代理保留后端路径前缀

GIVEN 后端端点自身带 /api 前缀

WHEN 前端开发代理转发请求

THEN 不剥离该前缀

AND 真实请求返回业务响应而非 404

#### Scenario: 统一响应解包

GIVEN 后端返回统一响应结构

WHEN 前端收到响应

THEN 由拦截层统一解包

AND 业务错误码呈现为可读提示而非原始报文

### Requirement: 会话与令牌生命周期

WHEN 用户在前端持有访问令牌,

系统 SHALL 使令牌过期后可自动续期，且 SHALL 在续期失败时清理本地会话。

#### Scenario: 并发过期只刷新一次

GIVEN 多个请求同时收到未授权响应

WHEN 触发令牌刷新

THEN 刷新只发生一次

AND 其余请求排队等待刷新结果后重放

#### Scenario: 刷新失败即登出

GIVEN 刷新令牌已失效

WHEN 刷新请求失败

THEN 清理本地令牌与用户状态

AND 跳转登录页

#### Scenario: 登出主动失效

GIVEN 用户点击登出

WHEN 前端执行登出

THEN 调用后端使令牌进入黑名单

AND 本地会话被清理

### Requirement: 角色路由守卫

WHEN 不同角色用户访问前端路由,

系统 SHALL 按角色渲染可用入口并拦截越权访问，且 SHALL NOT 以前端守卫作为安全边界。

#### Scenario: 按角色渲染入口

GIVEN 已登录用户具有某一角色

WHEN 进入应用

THEN 仅呈现该角色可用的菜单与路由

#### Scenario: 越权访问被拦截

GIVEN 用户访问不属于其角色的路由

WHEN 路由守卫执行

THEN 跳转无权限页

AND 不呈现该页面内容

#### Scenario: 安全边界在后端

GIVEN 前端守卫被绕过

WHEN 直接调用后端接口

THEN 后端角色校验仍然拒绝

AND 前端代码中明确记录该取舍

---

> **已移出本变更**：原「强制改密前置」Requirement（含 2 个 Scenario）经核实**后端语义上不可判定**——`must_change_password` 列在 `src/main` 中仅有建列与实体字段两处命中，**无任何读路径（零 getter 调用、无 DTO 装载、无 JWT claim）、无任何写路径（永不置 1）**，`CurrentUserResponse` 亦不含该字段。该 Requirement 已移至独立后端变更 `add-auth-must-change-password`；待其暴露契约后，再以前端变更补回守卫与相应 Scenario。本变更不含任何强制改密相关实现。
