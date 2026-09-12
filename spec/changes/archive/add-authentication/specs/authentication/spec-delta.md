# 规范差异：authentication

本文件包含对 `spec/specs/authentication/spec.md` 的规范变更（认证能力，全部为新增）。

## ADDED Requirements

### Requirement: 用户注册
WHEN 用户提交注册请求,
系统 SHALL 创建新账号并授予默认角色；学生 SHALL 以学号为账号注册，教师 SHALL 以工号为账号注册并校验有效邀请码。

#### Scenario: 学生以学号注册成功
GIVEN 学号尚未被占用
AND 密码满足强度要求（至少 8 位）
WHEN 用户提交学生注册表单
THEN 系统创建 STUDENT 角色账号
AND 以学号作为登录账号
AND 返回注册成功

#### Scenario: 教师以工号注册成功
GIVEN 工号尚未被占用
AND 提供的教师邀请码有效
WHEN 用户提交教师注册表单
THEN 系统创建 TEACHER 角色账号
AND 以工号作为登录账号

#### Scenario: 学号或工号重复
GIVEN 学号或工号已被占用
WHEN 用户提交注册表单
THEN 系统拒绝注册
AND 返回"账号已存在"错误

#### Scenario: 教师注册邀请码无效
GIVEN 用户提交教师注册
AND 提供的邀请码无效或已作废
WHEN 用户提交注册表单
THEN 系统拒绝授予 TEACHER 角色
AND 返回"邀请码无效"错误

#### Scenario: 密码强度不足
GIVEN 用户提供少于 8 位的密码
WHEN 用户提交注册表单
THEN 系统拒绝注册
AND 返回"密码至少 8 位"错误

### Requirement: 用户登录
WHEN 用户提交有效凭据,
系统 SHALL 认证用户并签发访问令牌；凭据错误、账号被禁用或锁定时 SHALL 拒绝。

#### Scenario: 登录成功
GIVEN 一个已注册且状态正常的账号
AND 用户提供正确的账号（学号或工号）与密码
WHEN 用户提交登录请求
THEN 系统认证成功
AND 返回访问令牌与刷新令牌

#### Scenario: 密码错误
GIVEN 用户提供错误密码
WHEN 用户提交登录请求
THEN 系统拒绝登录
AND 返回统一的"账号或密码错误"提示
AND 不返回任何令牌

#### Scenario: 账号被禁用
GIVEN 账号状态为禁用
WHEN 用户提交正确凭据
THEN 系统拒绝登录
AND 返回"账号已被禁用"错误

### Requirement: 访问令牌与刷新令牌
WHEN 系统认证成功,
系统 SHALL 签发短期有效的访问令牌与长期有效的刷新令牌；刷新令牌 SHALL 可换取新访问令牌。

#### Scenario: 刷新令牌换取新令牌
GIVEN 用户持有有效且未作废的刷新令牌
WHEN 用户调用刷新接口
THEN 系统签发新的访问令牌
AND 轮换刷新令牌（旧刷新令牌作废）
AND 返回新的双令牌

#### Scenario: 刷新令牌过期
GIVEN 刷新令牌已超过有效期
WHEN 用户调用刷新接口
THEN 系统拒绝刷新
AND 返回"登录已过期，请重新登录"

#### Scenario: 已作废刷新令牌被复用
GIVEN 一个已被轮换作废的刷新令牌
WHEN 该令牌再次被用于刷新
THEN 系统检测到复用
AND 使该用户的全部会话失效
AND 拒绝本次刷新

### Requirement: 会话主动失效
WHEN 用户登出、修改密码或被管理员强制下线,
系统 SHALL 使对应的访问令牌立即失效。

#### Scenario: 登出
GIVEN 用户持有一个有效访问令牌
WHEN 用户调用登出接口
THEN 系统使该令牌立即失效
AND 后续使用该令牌的请求被拒绝

#### Scenario: 修改密码后旧会话失效
GIVEN 用户持有有效令牌并成功修改密码
WHEN 修改完成
THEN 系统使该用户所有已签发令牌失效
AND 用户需重新登录

### Requirement: 密码找回
WHEN 用户请求找回密码,
系统 SHALL 通过邮箱验证码验证身份后允许重置密码。

#### Scenario: 验证码重置成功
GIVEN 用户提供已注册邮箱
AND 持有有效的单次验证码（未过期）
WHEN 用户提交新密码与验证码
THEN 系统重置密码
AND 使该用户所有旧令牌失效

#### Scenario: 验证码错误或过期
GIVEN 验证码错误或已超过有效期
WHEN 用户提交重置请求
THEN 系统拒绝重置
AND 返回"验证码无效或已过期"

### Requirement: 角色与权限授权
WHERE 用户访问受保护资源,
系统 SHALL 验证用户角色层级与权限点；角色层级为管理员 > 教师 > 学生，未满足最低要求 SHALL 拒绝。

#### Scenario: 角色满足要求
GIVEN 接口声明最低要求为 TEACHER
AND 当前用户角色为 TEACHER 或 ADMIN
WHEN 用户访问该接口
THEN 系统放行请求

#### Scenario: 角色不足
GIVEN 接口声明最低要求为 TEACHER
AND 当前用户角色为 STUDENT
WHEN 用户访问该接口
THEN 系统返回 403 Forbidden

#### Scenario: 权限点校验
GIVEN 接口声明需要某权限点
AND 当前用户角色未绑定该权限点
WHEN 用户访问该接口
THEN 系统返回 403 Forbidden

### Requirement: 水平越权防护
WHERE 用户访问或操作特定资源,
系统 SHALL 校验该用户对资源的归属或授权，非归属且无授权 SHALL 拒绝。

#### Scenario: 越权访问被阻止
GIVEN 用户 A 试图操作不属于自己的资源
AND 用户 A 不具备管理员权限
WHEN 用户 A 发起操作请求
THEN 系统返回 403 Forbidden
AND 不执行任何数据变更

### Requirement: 登录锁定
WHEN 账号连续登录失败达到阈值,
系统 SHALL 锁定该账号一段时间，期间拒绝登录。

#### Scenario: 触发锁定
GIVEN 账号连续 5 次登录失败
WHEN 用户再次尝试登录
THEN 系统锁定该账号 15 分钟
AND 锁定期间的登录请求被拒绝

#### Scenario: 锁定到期恢复
GIVEN 账号锁定已满 15 分钟
WHEN 用户使用正确凭据登录
THEN 系统允许登录并重置失败计数

### Requirement: 登录接口限流
WHEN 登录接口请求速率超过阈值,
系统 SHALL 拒绝超限请求以保护系统。

#### Scenario: 触发限流
GIVEN 同一账号或来源的登录请求在短时间内超过阈值
WHEN 用户再次请求登录
THEN 系统返回 429 Too Many Requests
AND 提示稍后重试

### Requirement: 管理员初始化
WHEN 系统首次启动且不存在管理员账号,
系统 SHALL 自动创建默认管理员账号并授予完整权限。

#### Scenario: 首次启动建管理员
GIVEN 数据库中不存在任何 ADMIN 角色账号
WHEN 系统启动
THEN 系统自动创建默认管理员账号
AND 授予其 ADMIN 角色与全部权限

#### Scenario: 已存在管理员不重复创建
GIVEN 数据库中已存在 ADMIN 角色账号
WHEN 系统启动
THEN 系统不重复创建管理员账号
