# add-prod-profile-enforcements

## 一、背景与问题（安全专项首扫发现 3+4 合并卡，2026-10-17 立案）

- **发现 3（部署依赖）**：管理员默认口令 admin123——`mustChangePassword` 仅前端引导（服务端 Aspect 不校验），未主动改密前全功能可用；生产环境以公开已知口令运行是完整接管面。
- **发现 4（部署依赖）**：`MailService` SMTP 未配置时找回密码验证码**明文落日志**——5 分钟 TTL 内有日志读权限者可接管任意账号（dev 便利分支无 profile 门禁）。

派工卡将两发现合并为「prod-only 强制项」卡。

## 二、方案

**① 管理员默认口令 fail-fast（prod 启动闸）**：`ProdAdminPasswordGuard`（@Profile("prod") @Order(3) CommandLineRunner——晚于 AdminInitializer(2)，检查创建后最终状态；runner 抛出即 Spring 启动中止）。检查口径：对存量哈希 `BCrypt.checkpw(公开缺省口令, 存储哈希)`——直接命中「口令仍为公开已知值」这一安全事实；只查配置的默认管理员账号（其他 ADMIN 角色账号非默认账号）。**豁免方式（显式 opt-in）**：`exam.auth.admin.allow-default-password`（env ADMIN_ALLOW_DEFAULT_PASSWORD）——存量库过渡场景置 true 启动（WARN）→ 登录改密 → 移除豁免重启复核；新库无需豁免（prod 编排强制要求 ADMIN_PASSWORD 环境变量，compose `:?` 必填校验，初始创建即强口令——**与部署流程零冲突**，这是停止条件核验结论）。哈希格式损坏按非默认口令放行并 WARN（账号本就无法以该哈希登录）。**处置路径**写进启动日志与 javadoc。

**② SMTP 未配置分支 prod 不可用**：`MailService` 日志兜底分支加配置门禁 `exam.auth.reset-code.allow-log-fallback`——**base 默认 false（安全缺省）**，dev/test 档显式 true 保留本地便利，prod 未覆盖即继承 false：SMTP 未配置时**不再明文落日志，改为功能明确报错不可用**（BusinessException「邮件服务未配置，找回密码功能暂不可用」，WARN 不含 code）。枚举面如实登记：报错仅在已注册邮箱上发生（未注册邮箱静默成功防枚举语义不变），与 SMTP 已配置但发送失败的既有诚实失败口径同形。

**配置旋钮落 yml（运维可见性纪律）**：base application.yml 显式声明两开关（含 env 覆盖形态与注释），dev/test 档显式覆盖 allow-log-fallback=true（测试档覆盖系 AuthIntegrationTest 找回流程依赖日志兜底路径——实测核验）。

**不降级面/边界**：dev/test 行为零变化（豁免开关默认关闭、日志兜底照旧）；AdminInitializer 语义零改动（豁免只影响启动闸，不影响创建）；「禁用账号无入口」与本卡无关（发现 5 另行裁决）。

## 三、白名单

- `src/main/java/com/exam/config/ProdAdminPasswordGuard.java`（新文件）
- `src/main/java/com/exam/config/AuthProperties.java`（Admin.allowDefaultPassword + ResetCode.allowLogFallback）
- `src/main/java/com/exam/auth/service/MailService.java`（兜底分支门禁 + javadoc）
- `src/main/resources/application.yml`（两旋钮声明 + 注释）
- `src/main/resources/application-dev.yml`（reset-code.allow-log-fallback: true）
- `src/test/resources/application-test.yml`（同上）
- `src/test/java/com/exam/config/ProdAdminPasswordGuardTest.java`（新文件：五用例）
- `src/test/java/com/exam/auth/service/MailServiceTest.java`（构造适配 + 新增两用例）
- `.github/ci/gate-baseline.json`、`spec/changes/add-prod-profile-enforcements/**`、`spec/README.md`、`docs/功能待办清单.md`
- **禁触**：AdminInitializer/AuthService/AuthController、application-prod.yml（旋钮继承 base，无需 prod 覆盖——prod yml 在案 5 后已含 management 收敛块，本卡零触碰）、docker/、schema.sql、openapi.yaml、pom.xml、frontend/

## 四、验收要点

- 启动闸五断言：默认口令哈希 fail-fast（IllegalStateException 含账号）；已改密通过；显式豁免放行；无管理员账号通过；装配契约（CommandLineRunner + @Profile("prod") + @Order(3) 晚于 AdminInitializer）
- 邮件门禁两断言：兜底关闭 → BusinessException 明确报错且**任何日志行不含验证码明文**（ListAppender）；兜底开启 → dev 便利保留
- 既有回归：AuthIntegrationTest 找回流程（test 档兜底开启）零回归
- 变异击红：摘除启动闸的抛出（改为仅 WARN）→ fail-fast 用例确定性失败 → 复原回绿留证；等价变异如实登记
- 范围外零改动核对（AdminInitializer/AuthService/AuthController/application-prod.yml + schema.sql + docker + openapi.yaml + pom + frontend）
- 门禁 `mvnw.cmd clean test` 全绿（仓库根、带 clean、禁 -o）；backendTests 基线在 676 上只增不减，收口笔同笔上调
- S 级理由：两个独立小面（启动闸单类 + 单分支门禁）+ 配置旋钮；无既有行为变化（dev/test/prod 主流程零改动）；与部署流程的冲突核验（compose 强制 ADMIN_PASSWORD）落 proposal
