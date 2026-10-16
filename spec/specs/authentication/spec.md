# authentication 规范

> 能力域：认证与鉴权（含工程基础能力，阶段 1/2 W1 + 阶段 18 后小阶段 W18）。
> 来源：`spec/changes/add-project-skeleton` 合入（工程骨架阶段）；
> `spec/changes/archive/add-auth-must-change-password` 合入（初始密码强制修改标记，0fb56b9）；
> `spec/changes/archive/harden-security-config` 合入（安全事件审计落库、建表来源唯一）；
> d2cbde9 直补（审计事件查询——非变更提案，见文末注记）。

## Requirements

### Requirement: 单体工程结构

WHEN 工程被构建,
系统 SHALL 通过 `mvn clean install` 全量编译打包，且 SHALL 提供健康检查端点。

#### Scenario: 全量构建成功

GIVEN 本地已安装 JDK 17 与 Maven
WHEN 执行 `mvn clean install`
THEN 工程编译打包成功
AND 无依赖缺失报错

#### Scenario: 健康检查可访问

GIVEN 应用已启动
WHEN 访问健康检查端点
THEN 返回 UP 状态

---

### Requirement: 依赖版本集中锁定

WHERE 工程声明依赖,
系统 SHALL 由父 POM dependencyManagement 统一管理版本，避免版本冲突。

#### Scenario: 依赖版本一致

GIVEN 工程引入 mybatis-plus、jjwt、redis 等依赖
WHEN 构建解析依赖
THEN 版本继承父 POM 锁定值
AND 与 Spring Boot 3.5.5 兼容矩阵一致

---

### Requirement: 统一响应与错误码

WHEN 任一接口返回结果,
系统 SHALL 使用统一 `ApiResponse<T>` 包装；失败时 SHALL 返回结构化错误码与信息。

#### Scenario: 成功响应包装

GIVEN 接口处理成功
WHEN 返回结果
THEN 响应体为 `{code:0, message:"ok", data:<payload>}`

#### Scenario: 业务异常

GIVEN 接口抛出业务异常
WHEN 全局异常处理器拦截
THEN 返回对应错误码与可读信息
AND 不泄漏堆栈细节

---

### Requirement: 数据库初始化

WHEN 首次启动系统,
系统 SHALL 执行建表脚本初始化 RBAC 五表，且建表 SHALL 幂等。

#### Scenario: 首次建表成功

GIVEN 数据库为空
WHEN 执行建表脚本
THEN 五表创建成功
AND users.username 唯一约束生效

#### Scenario: 幂等重放

GIVEN 表已存在
WHEN 再次执行建表脚本
THEN 脚本幂等，不报错且不破坏既有结构

---

### Requirement: 中间件一键编排

WHEN 开发者执行 `docker compose up`,
系统 SHALL 一键启动 MySQL 主从、Redis、RabbitMQ，并保证启动依赖顺序与健康就绪。

#### Scenario: 一键起全部中间件

GIVEN 已安装 Docker
WHEN 执行 `docker compose up`
THEN 各中间件按依赖顺序启动
AND 健康检查全部就绪

#### Scenario: 依赖未就绪被等待

GIVEN MySQL 尚未健康
WHEN 依赖 MySQL 的容器启动
THEN 容器等待 MySQL 健康检查通过后再启动

---

## 认证能力（来源：`spec/changes/add-authentication` 合入，阶段 2/2 W1-W2）

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

#### Scenario: 并发同号注册友好提示 (harden-auth-edge-cases)

- **WHEN**: 两个同号注册请求并发到达，均通过存在性预检后在库层撞 `uk_users_username` 唯一约束
- **THEN**: 后到写入 SHALL 捕获唯一键冲突并返回「账号已存在」业务错误（既有错误码），SHALL NOT 呈现为 500 内部错误
- **AND**: 注册事务 SHALL 整体回滚（无半注册残留），既有「存在性预检 + 业务错误」主路径零改动

#### Scenario: 邀请码并发使用计数不丢失 (harden-auth-edge-cases)

- **WHEN**: 多名教师持同一邀请码并发注册
- **THEN**: 使用计数 SHALL 以定向原子 UPDATE 递增（`used_count = used_count + 1` 且仍限定有效状态），SHALL NOT 读-改-写整实体
- **AND**: 并发「作废 + 使用」时使用方 SHALL 因状态条件不满足而注册失败（邀请码无效），SHALL NOT 把作废前的旧状态回写复活已作废邀请码

---

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

#### Scenario: 登录审计记录真实客户端 IP (harden-auth-edge-cases)

- **WHEN**: 部署形态含反向代理（Nginx TLS 终结），学生经代理发起登录
- **THEN**: 登录审计与锁定审计记录的 IP SHALL 为解析自代理转发头的真实客户端 IP（复用既有 ClientIpResolver），SHALL NOT 恒为代理内网地址
- **AND**: 账号锁定审计 SHALL 记录触发锁定场景的真实客户端 IP，SHALL NOT 硬编码占位值

---

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

---

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

#### Scenario: 修改密码落库原子性 (harden-auth-edge-cases)

- **WHEN**: 修改密码流程中「更新密码哈希」成功而「解除强制改密标记」落库失败
- **THEN**: 两项数据库写 SHALL 同处一个事务整体回滚（旧密码仍有效、强制改密标记保持），SHALL NOT 出现「密码已改而强制改密流未解除」的中间态
- **AND**: 会话失效（黑名单 + 全端下线）SHALL 在事务内的落库步骤之后执行，数据库回滚时 SHALL NOT 产生已执行的会话失效副作用

#### Scenario: 账号不可用操作联动全端下线 (harden-auth-edge-cases)

- **WHEN**: 未来补建任何使账号不可用的管理操作（禁用、删除等）
- **THEN**: 该操作 SHALL 联动全端下线（会话版本递增 + 删除 Refresh 会话），使存量访问令牌立即失效
- **AND**: 登录与刷新链路对不可用账号的拒绝语义保持不变
（由 harden-auth-edge-cases 合入；背景勘正：巡查台账原条目称「禁用账号只拦新登录不踢存量会话」，立项侦察发现用户禁用操作入口在代码中不存在〔user 包仅导入导出端点，status=1 无可达写入路径〕，故本条以设计约束形态固化而非代码修复；待禁用功能立项时受此约束）

---

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

#### Scenario: 验证码发送按维度限流 (harden-auth-edge-cases)

- **WHEN**: 脚本对发送验证码端点高频重放（单一邮箱或单一来源 IP）
- **THEN**: 系统 SHALL 按 邮箱+IP 维度执行固定窗口限流（复用登录限流阈值配置量级），超限返回 429
- **AND**: 限流检查 SHALL 先于邮箱存在性判断（防枚举语义不变：未注册邮箱同样计数且同样不暴露存在性）

#### Scenario: 邮件有效期文案与配置一致 (harden-auth-edge-cases)

- **WHEN**: 管理员将验证码 TTL 配置调整为非默认值后用户请求找回密码
- **THEN**: 邮件文案中的有效期 SHALL 取自该配置值，SHALL NOT 硬编码固定分钟数

---

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

---

### Requirement: 水平越权防护

WHERE 用户访问或操作特定资源,
系统 SHALL 校验该用户对资源的归属或授权，非归属且无授权 SHALL 拒绝。

#### Scenario: 越权访问被阻止

GIVEN 用户 A 试图操作不属于自己的资源
AND 用户 A 不具备管理员权限
WHEN 用户 A 发起操作请求
THEN 系统返回 403 Forbidden
AND 不执行任何数据变更

---

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

---

### Requirement: 登录接口限流

WHEN 登录接口请求速率超过阈值,
系统 SHALL 拒绝超限请求以保护系统。

#### Scenario: 触发限流

GIVEN 同一账号或来源的登录请求在短时间内超过阈值
WHEN 用户再次请求登录
THEN 系统返回 429 Too Many Requests
AND 提示稍后重试

#### Scenario: 固定窗口计数原子化 (harden-auth-edge-cases)

- **WHEN**: 限流/锁定失败计数执行 INCR 后、过期设置前发生进程或连接异常
- **THEN**: 计数键的递增与过期设置 SHALL 以原子操作（Lua）完成，SHALL NOT 出现「已递增但永不过期」的键
- **AND**: 该防护 SHALL 覆盖登录限流与失败锁定两处计数；阈值与窗口配置零改动
（由 harden-auth-edge-cases 合入；「登录锁定」Requirement 的计数点同为本条覆盖对象，不单独追加 Scenario）

---

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

---

### Requirement: 初始密码强制修改标记

WHEN 系统自行创建带初始密码的特权账号,
系统 SHALL 标记该账号必须修改密码，且 SHALL 使该标记可被客户端实时读取、在密码修改成功后解除。

实施注记（0fb56b9）：标记落在 `CurrentUserResponse.mustChangePassword`（`Boolean`），由 `/api/auth/me` 实时读库装载（`Integer → boolean`，`null` 视为 `false`，故该字段恒有值）；**刻意不入 JWT claim**——它是可变状态，入无状态 token 会产生「已改密但旧 token 仍说必须改密」的窗口，只能靠黑名单 / `sessionVersion` 兜，语义不正确。`AdminInitializer` 仅在**首次创建**分支置 1（两道提前 return 在构造 `User` 之前，物理上不可能改写已存在账号）；`changePassword` 成功后置 0。**未改鉴权拦截器**：本能力只暴露标记，不在后端强拦「未改密却调业务接口」（那会波及全部既有集成测试且属行为变更，若要强拦需单独立项）。零 DDL、零数据迁移，**不追溯**把存量 admin 置 1。前端守卫接入属独立前端变更（守卫入口已收敛为 `frontend/src/router/access.ts` 的 `decideNavigation`）。

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

---

### Requirement: 安全事件审计落库

WHEN 发生登录、账户锁定等安全事件,

系统 SHALL 把事件持久化到 `audit_log`，含操作人、来源 IP、结果与链路标识。

#### Scenario: 失败登录同样留痕

GIVEN 一次账号不存在或密码错误的登录

WHEN 登录被拒

THEN 写入一条 FAILURE 审计

AND 账号不存在时 `user_id` 为空，但对外提示与密码错误一致（防账号枚举）

#### Scenario: 成功登录记下操作人

GIVEN 一次凭据正确的登录

WHEN 写入审计

THEN `user_id` 为该账号 ID

AND 操作人须由已持有用户对象的调用方显式传入——不得在异步工作线程里读安全上下文
（那里上下文为空，会让操作人恒为 null 而无人察觉）

#### Scenario: 链路标识可回溯

GIVEN 审计行携带链路标识

WHEN 用该标识去追踪系统查询

THEN 命中的正是产生这次登录的那条链路

AND 该标识取自当次请求的真实链路上下文，不得是当场生成的随机值

#### Scenario: 审计失败不阻断登录

GIVEN 审计存储临时不可用

WHEN 写入抛出异常

THEN 异常被吞并记 ERROR

AND 不因旁路故障把正常登录变成 500

---

### Requirement: 审计事件查询

WHEN 管理员排查近期安全事件,

系统 SHALL 提供 `GET /api/admin/audit-logs`，按账号/事件类型过滤、以时间倒序分页返回审计记录。

#### Scenario: 最近的事件排在最前

GIVEN 同一账号存在多条审计记录

WHEN 管理员查询

THEN 记录按时间倒序返回（最新在前）

AND 分页不得把最近的事件埋进末页——那是管理员最想看的部分

#### Scenario: 过滤条件真正生效

GIVEN 库中混有多个账号的审计记录

WHEN 按 `username` 或 `action` 查询

THEN 只返回匹配的记录，无一混入

#### Scenario: 查询结果可跳查链路

GIVEN 一条审计记录

WHEN 管理员读取其响应体

THEN 含完整 32 位 `traceId`，可直接拿去追踪系统定位那次请求

#### Scenario: 非管理员不可读取

GIVEN 教师或学生身份

WHEN 访问该接口

THEN 返回 403

AND 门槛只由类级角色校验承担——不额外挂权限点，因为它只能归属同一批人，
等于把一道门建两遍并多一处会漏配的初始化数据

#### Scenario: 越界分页被拒而非静默夹紧

GIVEN 请求携带超过上限的 `size`

WHEN 参数校验执行

THEN 返回 400（不是把 101 悄悄夹成 100 后返回 200）

---

### Requirement: 建表来源唯一

WHEN 新增数据库表,

系统 SHALL 把 DDL 落在 `schema.sql`（dev 与测试共用的唯一建表入口），并为存量库另给手工迁移脚本。

#### Scenario: 未接线的迁移目录不产生表

GIVEN 某 DDL 只放在 `db/migration/` 下

WHEN 应用在新库上启动

THEN 该表不会被创建（本项目未接 Flyway，该目录下的脚本从不执行）

AND 应改由 `schema.sql` 承载，避免"表已存在"的错觉

---

> 合入注记（2026-09-20，`harden-security-config`）：本域只合入审计落库与建表来源两条。
> JWT 启动期强度校验、密码复杂度规则、连续失败锁定均已在 HEAD 中生效，非本变更新增。
> **未合入**（提案提出但尚未实现）：`/audit/logs` 查询接口（数据已入库但无读取路径，
> 审计的追溯价值目前只到"能查库"为止）、密码有效期策略、KMS 托管密钥。
>
> 后续（2026-09-20，d2cbde9 直补，非变更提案）：上述"查询接口"一条已实现为
> `GET /api/admin/audit-logs`，故新增"审计事件查询"Requirement。路径与提案写的
> `/audit/logs` 不同——审计是管理员专属，放在既有 `/api/admin` 前缀下才吃得到类级
> `@RequireRole(ADMIN)`，不必再造一套鉴权。剩余未实现：密码有效期策略、KMS 托管密钥。

---

### Requirement: 用户 Excel 批量导入

WHEN 管理员上传学生名单 xlsx,

系统 SHALL 提供 `POST /api/admin/users/import`（multipart，权限 ADMIN + user:manage）：按固定列模板（学号/姓名/邮箱可选/初始密码/班级名称）逐行独立事务导入，创建角色固定为 STUDENT 的账号（初始密码 BCrypt 入库）；学号已存在（库内或文件内先出现者）、班级名称不存在或歧义、必填缺失或格式非法的行 SHALL 跳过并逐行报告错误，正确行照常入库；数据行数超过 1000 SHALL 文件级拒绝。返回 {imported, failed, errors}。

#### Scenario: 合法行入库与班级挂接

GIVEN 模板含合法学生行且班级名称在 classes 表存在

WHEN 管理员上传

THEN 账号创建（角色 STUDENT、密码 BCrypt）、user_class 挂接正确

AND 该学生能以初始密码登录

#### Scenario: 重复学号跳过不覆盖

GIVEN 某行学号已存在（库内或文件内先出现）

WHEN 导入执行

THEN 该行跳过并报告错误，既有账号的密码/姓名/角色不被改动

#### Scenario: 班级不存在跳过不自动建班

GIVEN 某行班级名称在 classes 表不存在或存在同名歧义

WHEN 导入执行

THEN 该行跳过并报告错误，不自动创建班级，其余行不受影响

#### Scenario: 角色固定学生

GIVEN 模板不含角色列且导入服务固定 STUDENT

WHEN 导入完成

THEN 全部成功行账号角色为 STUDENT，不存在批量创建教师/管理员的路径

#### Scenario: 行数上限与坏格式

GIVEN 数据行超过 1000，或存在坏格式文件（非 xlsx/表头不符）

WHEN 上传

THEN 文件级拒绝并返回可观察错误，不产生任何入库

#### Scenario: 权限门控

GIVEN 非 ADMIN token 或缺 user:manage 权限的请求方

WHEN 请求导入/模板/导出任一端点

THEN 返回 403

---

### Requirement: 用户导入模板下载

WHEN 管理员下载导入模板,

系统 SHALL 提供 `GET /api/admin/users/import-template`（权限同上）返回 xlsx 模板，表头列序与导入解析、导出输出严格一致（护栏用例锁定三向一致）。

#### Scenario: 模板表头三向一致

GIVEN 模板文件、导入解析列定义、导出列定义

WHEN 三者表头逐列比对

THEN 列序与文案完全一致

---

### Requirement: 用户 Excel 导出

WHEN 管理员导出用户名单,

系统 SHALL 提供 `GET /api/admin/users/export`（权限同上，参数 scope=student|teacher|all，可选 classId 过滤学生）以 SXSSF 流式输出 xlsx：scope=student 输出学生（含班级归属列，classId 指定时仅该班）、scope=teacher 输出教师、scope=all 输出全部用户（含角色列）；软删/状态口径 SHALL 与管理侧既有列表口径一致；列口径与模板一致（学生域列）。

#### Scenario: scope 三态与班级过滤

GIVEN 库内存在学生（分属两班）、教师、管理员

WHEN 分别以 scope=student、scope=teacher、scope=all、scope=student&classId=X 导出

THEN 各导出仅含对应人群，classId 过滤仅输出该班学生

#### Scenario: 流式不溢出

GIVEN 大量用户记录

WHEN 导出

THEN SXSSF 流式写入，不因内存不足失败

#### Scenario: 导出口径与列表一致

GIVEN 管理侧列表查询口径（软删/状态过滤）

WHEN 导出执行

THEN 导出行集合与列表口径一致，不输出列表不可见的用户

#### Scenario: 大批量用户导出分片分批查询资源保护 (harden-review-flow-and-resource-limits)

GIVEN 管理员导出包含大量用户（如超过 500 人）的学生、教师或全量用户数据

WHEN 系统按 ID 加载用户实体

THEN 执行有界分片批量查询（每批至多 500 个 ID），禁止单次发起包含数千参数的巨型 `WHERE id IN (...)` 查询

AND 分片查询导出的结果集顺序与字段完整性与既有口径完全一致，防止 MySQL 报文上限溢出与应用层内存激增

---

### Requirement: 用户批量导入导出

> 合入注记（2026-10-15，变更 `harden-data-sanitization-and-ui-states`，M-01/M-02 收口）：
> 用户 Excel 导入在单元格提取阶段（`cellText`）自动剥除首尾全部 Unicode 空白（含全角空格 `\u3000` 与 `\u00A0`），导出写出单元格执行 `sanitizeFormula` 公式防注入。

#### Scenario: Excel 导入用户全角空格清洗与导出防注入 (ADDED)
- **WHEN**: 管理员通过 Excel 导入用户（`POST /api/admin/users/import`），单元格（学号/用户名、姓名、邮箱、班级名称等）首尾带有中文全角空格（`\u3000`）或不换行空格（`\u00A0`）
- **THEN**: 系统 SHALL 在单元格提取阶段剥除首尾所有 Unicode 空白字符，确保用户名、姓名及班级名称的解析与匹配不受全角空格干扰
- **AND**: 在导出用户（`GET /api/admin/users/export`）时，写出单元格 SHALL 对用户名、姓名等可控字段执行公式防注入清洗（`sanitizeFormula`），防止注入执行

### Requirement: 鉴权拦截器对 Redis 异常的降级容错 (harden-auth-interceptor-redis-degradation)

WHEN Redis 不可用时携带有效 JWT 的请求经过鉴权拦截器（登出吊销黑名单、会话版本检查）,

系统 SHALL 降级放行并记录降级计数与 ERROR 日志, AND SHALL NOT 因基础设施异常裸穿 500 使全部 API 停摆。

#### Scenario: Redis 异常时放行 (harden-auth-interceptor-redis-degradation)

GIVEN 黑名单/会话版本查询因 Redis 异常失败

WHEN 携带有效 JWT 的请求经过拦截器

THEN 请求被放行并正常处理

AND 记录 ERROR 日志，降级计数器递增

#### Scenario: 降级半径自限且可论证 (harden-auth-interceptor-redis-degradation)

GIVEN Redis 故障期间黑名单不可读

THEN 已吊销 Access token 的复活半径 ≤ 其剩余寿命（≤30min）

AND 与在案观察项「Redis 清空后已吊销 Access ≤30min 复活」同半径 by design（黑名单本就是 TTL 自限设计）

AND 会话版本检查失败意味着改密 bump 暂时无法踢下线，半径同性质同上界

#### Scenario: 与限流降级口径同源 (harden-auth-interceptor-redis-degradation)

GIVEN 仓库已有 `exam.ratelimit.degraded`（fail-open + ERROR 日志 + 计数）先例

WHEN 拦截器发生降级

THEN 打点与日志口径与该先例一致（指标名对齐先例命名法，`AlertAssetsTest` 守护同笔登记）

AND 不新增第二套降级哲学

#### Scenario: 变异击红 (harden-auth-interceptor-redis-degradation)

GIVEN 还原为无容错旧形态（Redis 异常直接上抛）

WHEN 运行拦截器容错单测

THEN 以确定性断言失败（先红后绿留证；等价变异如实登记不强杀）

#### Scenario: 范围外不越界 (harden-auth-interceptor-redis-degradation)

GIVEN 「Redis 依赖与草稿兜底」裁决页后置

WHEN 本变更收口

THEN RedisCacheManager、登录守卫、草稿 Redis 存储、交卷 SETNX 锁四处零改动

AND 不在收口笔中夹带裁决页应辖的任何行为变更

