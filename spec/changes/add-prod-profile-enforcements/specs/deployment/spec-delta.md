# spec-delta：deployment 域（add-prod-profile-enforcements）

## ADDED Requirements

### Requirement: 生产环境安全强制项 (add-prod-profile-enforcements)

WHEN prod 环境启动,

系统 SHALL 检测默认管理员账号是否仍为公开已知默认口令并 fail-fast 拒绝启动（显式豁免除外）, AND SHALL NOT 在 SMTP 未配置时将找回密码验证码明文落日志（改为功能明确报错不可用）, AND SHALL NOT 影响 dev/test 档的本地便利行为。

#### Scenario: 默认口令启动闸 fail-fast (add-prod-profile-enforcements)

GIVEN prod 编排强制要求 ADMIN_PASSWORD 环境变量（新库初始创建即强口令）

WHEN 启动时检测到配置的管理员账号口令哈希仍匹配公开已知默认口令

THEN 以明确异常中止启动（含账号与两条处置路径的启动日志）

AND 检查时序晚于 AdminInitializer（@Order(3)，检查创建后最终状态）

#### Scenario: 显式豁免仅限存量库过渡 (add-prod-profile-enforcements)

GIVEN 存量库的管理员口令改密需先登录完成

THEN exam.auth.admin.allow-default-password=true 显式豁免可放行启动（打 WARN）

AND 豁免是过渡形态：登录改密后必须移除豁免并重启复核（处置路径写进日志与 javadoc）

#### Scenario: SMTP 未配置时验证码绝不明文落日志 (add-prod-profile-enforcements)

GIVEN 验证码明文落日志意味着 5 分钟 TTL 内有日志读权限者可接管任意账号

THEN base 档 allow-log-fallback 默认 false（安全缺省），prod 继承 false

WHEN SMTP 未配置且兜底关闭时请求找回密码验证码

THEN 返回明确业务报错（功能暂不可用），任何日志行不含验证码明文

AND 未注册邮箱静默成功的防枚举语义不变

#### Scenario: dev/test 档便利保留 (add-prod-profile-enforcements)

GIVEN 本地开发与测试依赖日志兜底跑通找回流程

THEN dev/test 档显式声明 allow-log-fallback=true，行为与本卡之前零变化

AND 启动闸仅 prod profile 装配，dev/test 启动零影响

#### Scenario: 变异击红 (add-prod-profile-enforcements)

GIVEN 摘除启动闸的抛出（改为仅 WARN）

WHEN 运行启动闸单测

THEN 以确定性断言失败（先红后绿留证；等价变异如实登记不强杀）
