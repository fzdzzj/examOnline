# 规范增量：authentication

> 本案全部落点为 authentication 能力域既有 Requirement 追加 Scenario（MODIFIED 形态）。

## MODIFIED Requirement: 用户注册（追加 2 Scenario）

### ADDED Scenario: 并发同号注册友好提示

- **WHEN**: 两个同号注册请求并发到达，均通过存在性预检后在库层撞 `uk_users_username` 唯一约束
- **THEN**: 后到写入 SHALL 捕获唯一键冲突并返回「账号已存在」业务错误（既有错误码），SHALL NOT 呈现为 500 内部错误
- **AND**: 注册事务 SHALL 整体回滚（无半注册残留），既有「存在性预检 + 业务错误」主路径零改动

### ADDED Scenario: 邀请码并发使用计数不丢失

- **WHEN**: 多名教师持同一邀请码并发注册
- **THEN**: 使用计数 SHALL 以定向原子 UPDATE 递增（`used_count = used_count + 1` 且仍限定有效状态），SHALL NOT 读-改-写整实体
- **AND**: 并发「作废 + 使用」时使用方 SHALL 因状态条件不满足而注册失败（邀请码无效），SHALL NOT 把作废前的旧状态回写复活已作废邀请码

## MODIFIED Requirement: 用户登录（追加 1 Scenario）

### ADDED Scenario: 登录审计记录真实客户端 IP

- **WHEN**: 部署形态含反向代理（Nginx TLS 终结），学生经代理发起登录
- **THEN**: 登录审计与锁定审计记录的 IP SHALL 为解析自代理转发头的真实客户端 IP（复用既有 ClientIpResolver），SHALL NOT 恒为代理内网地址
- **AND**: 账号锁定审计 SHALL 记录触发锁定场景的真实客户端 IP，SHALL NOT 硬编码占位值

## MODIFIED Requirement: 登录接口限流（追加 1 Scenario）

### ADDED Scenario: 固定窗口计数原子化

- **WHEN**: 限流/锁定失败计数执行 INCR 后、过期设置前发生进程或连接异常
- **THEN**: 计数键的递增与过期设置 SHALL 以原子操作（Lua）完成，SHALL NOT 出现「已递增但永不过期」的键
- **AND**: 该防护 SHALL 覆盖登录限流与失败锁定两处计数；阈值与窗口配置零改动

## MODIFIED Requirement: 登录锁定（并入上条计数原子化，不单独追加）

## MODIFIED Requirement: 密码找回（追加 2 Scenario）

### ADDED Scenario: 验证码发送按维度限流

- **WHEN**: 脚本对发送验证码端点高频重放（单一邮箱或单一来源 IP）
- **THEN**: 系统 SHALL 按 邮箱+IP 维度执行固定窗口限流（复用登录限流阈值配置量级），超限返回 429
- **AND**: 限流检查 SHALL 先于邮箱存在性判断（防枚举语义不变：未注册邮箱同样计数且同样不暴露存在性）

### ADDED Scenario: 邮件有效期文案与配置一致

- **WHEN**: 管理员将验证码 TTL 配置调整为非默认值后用户请求找回密码
- **THEN**: 邮件文案中的有效期 SHALL 取自该配置值，SHALL NOT 硬编码固定分钟数

## MODIFIED Requirement: 会话主动失效（追加 2 Scenario）

### ADDED Scenario: 修改密码落库原子性

- **WHEN**: 修改密码流程中「更新密码哈希」成功而「解除强制改密标记」落库失败
- **THEN**: 两项数据库写 SHALL 同处一个事务整体回滚（旧密码仍有效、强制改密标记保持），SHALL NOT 出现「密码已改而强制改密流未解除」的中间态
- **AND**: 会话失效（黑名单 + 全端下线）SHALL 在事务内的落库步骤之后执行，数据库回滚时 SHALL NOT 产生已执行的会话失效副作用

### ADDED Scenario: 账号不可用操作联动全端下线（设计约束）

- **WHEN**: 未来补建任何使账号不可用的管理操作（禁用、删除等）
- **THEN**: 该操作 SHALL 联动全端下线（会话版本递增 + 删除 Refresh 会话），使存量访问令牌立即失效
- **AND**: 登录与刷新链路对不可用账号的拒绝语义保持不变
（由 harden-auth-edge-cases 合入；背景勘正：巡查台账原条目称「禁用账号只拦新登录不踢存量会话」，立项侦察发现用户禁用操作入口在代码中不存在〔user 包仅导入导出端点，status=1 无可达写入路径〕，故本条以设计约束形态固化而非代码修复；待禁用功能立项时受此约束）
