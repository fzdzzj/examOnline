# frontend spec-delta：邀请码管理页（add-frontend-invite-code-admin）

## ADDED Requirements

### Requirement: 邀请码管理界面

管理端 SHALL 提供邀请码管理页（`/admin/invite-codes`，落既有 `/admin` 角色分区）：列表按后端返回字段渲染（状态按 `status` 呈现，前端不推算）、经 `listInviteCodes` 取数；生成 SHALL 经表单提交 `createInviteCode`（字段按契约），成功后新码在任何列表刷新前于当前弹层内一次性可见并可复制；作废 SHALL 经知情确认弹窗（形态对齐考试删除）调用 `invalidateInviteCode`；查询失败 SHALL 以 Alert 显性呈现后端 message 且 SHALL NOT 伪装空态；操作失败 SHALL 原文呈现后端 message，SHALL NOT 本地编造失败文案或拦截请求；生成 / 作废成功 SHALL 失效邀请码列表查询刷新。

#### Scenario: 列表按后端字段渲染

GIVEN 后端返回邀请码数组（含有效与已作废行）

WHEN 页面渲染

THEN 邀请码 / 备注 / 状态 / 已使用次数 / 创建时间均按返回值呈现

AND 作废入口仅在有效（status=0）行出现

#### Scenario: 生成后新码一次性可见可复制

GIVEN 管理员填写备注并提交生成

WHEN `createInviteCode` 返回新邀请码

THEN 弹层内展示新码且提供复制入口

AND 邀请码列表查询被失效刷新

#### Scenario: 作废知情确认

GIVEN 管理员点击有效行的「作废」

WHEN 确认弹窗呈现

THEN 弹窗明示作废不可逆（形态对齐考试删除弹窗）

AND 确认后调用 `invalidateInviteCode`（path 携带该行 id）并失效列表查询

#### Scenario: 查询失败不伪装空态

GIVEN 列表查询失败

WHEN 页面渲染

THEN 错误 Alert 呈现后端 message，空态不出现

AND 成功且无数据时空态照常呈现

#### Scenario: 失败原文呈现不本地编造

GIVEN 生成或作废被后端拒绝

WHEN 操作失败

THEN message 呈现后端返回的原始 message，且不失效查询、不展示伪成功结果
