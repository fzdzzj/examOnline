# 规范差异：authentication（初始密码强制修改标记）

本文件包含对 `spec/specs/authentication/spec.md` 的规范变更（新增）。

## ADDED Requirements

### Requirement: 初始密码强制修改标记

WHEN 系统自行创建带初始密码的特权账号,

系统 SHALL 标记该账号必须修改密码，且 SHALL 使该标记可被客户端实时读取、在密码修改成功后解除。

#### Scenario: 首次创建的 admin 被标记

GIVEN 系统首次启动并自动创建 admin 账号

WHEN 该账号查询当前用户信息

THEN 返回的必须改密标记为真

#### Scenario: 标记可被客户端读取

GIVEN 一个被标记为必须改密的账号

WHEN 客户端请求当前用户信息接口

THEN 响应体含该标记

AND 该标记出现在对外契约中，可被类型化客户端生成

#### Scenario: 改密成功后解除

GIVEN 一个被标记为必须改密的账号

WHEN 该账号成功修改密码

THEN 标记被解除

AND 再次查询当前用户信息返回假

#### Scenario: 重复启动不打回已改密账号

GIVEN 一个 admin 账号已完成密码修改

WHEN 系统再次启动并执行初始化

THEN 不重新置该账号的必须改密标记

AND 该账号不会被反复要求改密

#### Scenario: 标记不入无状态令牌

GIVEN 必须改密标记属可变状态

WHEN 系统签发访问令牌

THEN 该标记不作为令牌声明携带

AND 客户端通过实时查询获取其当前值

#### Scenario: 存量账号不被追溯

GIVEN 一个在标记能力上线前已存在的账号

WHEN 系统升级后启动

THEN 不追溯修改其必须改密标记

AND 不因此把既有部署的管理员突然锁入强制改密
