# frontend spec-delta：审计日志与强制下线（add-frontend-audit-logs-kick）

## ADDED Requirements

### Requirement: 审计日志查询与强制下线界面

管理端 SHALL 提供安全审计日志页（`/admin/audit-logs`，落既有 `/admin` 角色分区）：列表经 `auditLogs`（`GET /api/admin/audit-logs`）按 `page`/`size`/`username`/`action` 取数并按后端返回字段渲染，前端 SHALL NOT 自行推算动作或状态语义；用户名与动作筛选 SHALL 作为参数传递至服务端、SHALL 具备防抖且筛选变更 SHALL 重置到第 1 页；因响应为页内数组且无 `total` 信封，分页器 SHALL 以「当页满页即至少还有下一页」的下界推断呈现，SHALL NOT 伪造精确总数；仅携带 `userId` 的行 SHALL 呈现「强制下线」入口（无 `userId` 的系统事件行不呈现）；强制下线 SHALL 经危险确认弹窗（明示目标用户会话立即失效、需重新登录）调用 `kickUser`（path 携带该行 `userId`）；查询失败 SHALL 以 Alert 显性呈现后端 message 且 SHALL NOT 伪装空态；操作失败 SHALL 原文呈现后端 message，SHALL NOT 本地编造失败文案或拦截请求；强制下线成功 SHALL 失效审计日志查询刷新。

#### Scenario: 列表按后端字段渲染

GIVEN 后端返回审计日志数组（含 `userId` 行与 `userId` 为空的系统事件行）

WHEN 页面渲染

THEN 用户名 / 动作 / 状态 / IP / 时间 / 详情均按返回值呈现

AND 「强制下线」入口仅在携带 `userId` 的行出现

#### Scenario: 查询失败不伪装空态

GIVEN `auditLogs` 查询失败

WHEN 页面渲染

THEN 错误 Alert 呈现后端返回的原始 message，空态文案不出现

AND 成功且无数据时空态照常呈现、不弹错误 Alert

#### Scenario: 无 total 信封的下界分页

GIVEN 审计日志响应为页内数组且无 `total` 字段

WHEN 当前页返回条数等于 `size`

THEN 分页器据「满页即至少还有下一页」预留下一页入口，翻页可真实发起带 `page` 的请求

AND 当前页返回条数小于 `size` 时不预留后续页码

#### Scenario: 筛选参数透传服务端且防抖回第 1 页

GIVEN 管理员在用户名输入框连续快速输入字符并填写动作筛选

WHEN 触发输入事件

THEN 防抖窗口内不发起新请求，停止输入后以 `query: { page, size, username, action }` 携带去空白后的筛选值请求

AND 筛选值变化时当前页码重置为 1，空筛选值不携带该参数

#### Scenario: 强制下线知情确认

GIVEN 管理员点击某条携带 `userId` 记录的「强制下线」

WHEN 危险确认弹窗呈现

THEN 弹窗明示该用户所有会话立即失效、需重新登录

AND 确认后调用 `kickUser`（path 携带该行 `userId`）并失效审计日志查询

#### Scenario: 下线失败原文呈现不本地编造

GIVEN 强制下线被后端拒绝

WHEN 操作失败

THEN message 呈现后端返回的原始 message，且不失效查询、不展示伪成功结果
