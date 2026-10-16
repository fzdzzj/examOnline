# 阶段 2 证据留证：auth 域边界与并发加固（harden-auth-edge-cases）

本目录记录阶段 2 的完整证据链：后端单测**先红后绿**、四项变异 **m1/m2/m3/m4 击红-复原-回绿**、
全量后端门禁实测，以及关键红线核对。

- 分支：`feature/harden-auth-edge-cases`
- 基线 revision：`99f5947`（阶段 0 & 1 立项笔）
- 受控白名单：仅 `src/main` 与 `src/test` 的 `com/exam/auth/**`；`ClientIpResolver` 仅 import
  不触碰 `anticheat` 包；**零 DDL**（`schema.sql` 零 diff）、`docker/`、`openapi.yaml`、`pom.xml`、
  `frontend/` 均未触碰。
- 命令均在仓库根执行，留证日志为完整控制台输出的裁剪版（仅保留判定性行与结果汇总）。

---

## 一、后端单测先红后绿

新增 6 个测试类（19 例）：
- 新增 `src/test/java/com/exam/auth/service/LoginGuardServiceTest.java`（8 例）：
  三计数点 Lua 原子化（execute 单次调用、键 `auth:rl:` / `auth:fail:` / `auth:coderl:`、
  TTL=60s、杜绝 opsForValue().increment + expire 两步形态）、登录限流超阈值 429 与按 IP 维度隔离、
  失败计数达阈值锁定并清计数、`recordFailure(username, ip)` 把真实 IP 透传锁定审计；
- 新增 `src/test/java/com/exam/auth/service/InviteCodeServiceTest.java`（2 例）：
  markUsed 定向原子 UPDATE（setSql 自增 + eq id + eq status=0，杜绝整实体 updateById）、
  UPDATE 影响 0 行抛 `INVITE_CODE_INVALID`；
- 新增 `src/test/java/com/exam/auth/service/MailServiceTest.java`（2 例）：
  邮件文案用传入 ttlMinutes 动态生成（10 / 3 分钟内有效，非硬编码 5）；
- 新增 `src/test/java/com/exam/auth/service/AuthServiceTest.java`（4 例）：
  register 捕获 `DuplicateKeyException` 转友好 `ACCOUNT_ALREADY_EXISTS`、
  changePassword 声明 `@Transactional(rollbackFor=Exception.class)`（反射断言）、
  sendResetCode 限流先于邮箱存在性查询（InOrder + 未注册不发信）、
  sendResetCode 把配置 ttlMinutes 透传邮件服务；
- 新增 `src/test/java/com/exam/auth/LoginAuditRealIpIntegrationTest.java`（1 例）：
  反代 XFF 场景登录失败审计记录解析后的真实客户端 IP（非代理地址）；
- 新增 `src/test/java/com/exam/auth/ResetCodeRateLimitIntegrationTest.java`（2 例）：
  已注册 / 未注册邮箱 10 次后第 11 次均 429（限流先于存在性判断，防枚举语义不变）。

### 红（实现前）

两轮红均在主代码未改动时执行：
- **行为红**（`red-before-impl-behavioral.log`，`mvnw.cmd clean test -Dtest=InviteCodeServiceTest,LoginAuditRealIpIntegrationTest,ResetCodeRateLimitIntegrationTest`）：
  `Tests run: 5, Failures: 5` BUILD FAILURE——XFF 审计 ipAddress=127.0.0.1（真实 IP 未接线）、
  reset-code 无限流（429 期望得 200 ×2）、markUsed 非原子（无定向 UPDATE / 0 行不抛）：
  - `LoginAuditRealIpIntegrationTest` ×1：ipAddress=127.0.0.1
  - `ResetCodeRateLimitIntegrationTest` ×2：expected 429 but was 200
  - `InviteCodeServiceTest` ×2：updateById 未被替换 / 0 行未抛 `INVITE_CODE_INVALID`
  - 注：该轮把依赖新签名的 3 个测试类（LoginGuardServiceTest / MailServiceTest / AuthServiceTest）
    暂移出 src/test 后再跑（`-Dtest` 只过滤运行、不过滤编译），跑毕即复原。
- **编译红**（`red-before-impl-compile.log`，`mvnw.cmd clean test -Dtest=LoginGuardServiceTest,MailServiceTest,AuthServiceTest`）：
  `testCompile` 编译失败——`recordFailure(username, ip)` / `checkCodeRateLimit(email, ip)` /
  `sendResetCode(email, ip)` / `sendResetCode(email, code, ttlMinutes)` 新签名在旧主代码上不存在
  （14 处「无法将类…方法应用到给定类型 / 找不到符号」），即新能力缺失的编译期红。

### 绿（实现后）

实现后重跑 6 个相关测试类（`green-after-impl.log`）→
`Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` **BUILD SUCCESS**。

---

## 二、变异击红留证（m1 / m2 / m3 / m4）

每项均执行：注入违规 → 跑判定用例子集确认变红 → 快照复原（逐字节还原，非 git checkout）→ 确认回绿。
驱动脚本：`evidence/run-mutations.ps1`（`powershell -NoProfile -ExecutionPolicy Bypass -File ...`）。

### m1 —— 登录退回 remoteAddr（真实 IP 接线失效）

- 注入点：`AuthController.login` 的 `ClientIpResolver.resolve(http)` → `http.getRemoteAddr()`。
- 击红：`Tests run: 1, Failures: 1`——审计 ipAddress=127.0.0.1（代理地址），XFF 真实 IP 断言失败。
  日志：`mut-m1-red.log`
- 复原 + 回绿：`Tests run: 1, Failures: 0` **BUILD SUCCESS**。日志：`mut-m1-green.log`

### m2 —— markUsed 退回整实体 updateById（定向 UPDATE 失效）

- 注入点：`InviteCodeService.markUsed` 的原子 UPDATE 块 → 旧读-改-写 `ic.setUsedCount(...)+updateById(ic)`。
- 击红：`Tests run: 2, Failures: 2`——无定向 UPDATE 调用（丢失更新/复活路径回归）、
  0 行不再抛 `INVITE_CODE_INVALID`。日志：`mut-m2-red.log`
- 复原 + 回绿：`Tests run: 2, Failures: 0` **BUILD SUCCESS**。日志：`mut-m2-green.log`

### m3 —— 移除 reset-code 入口限流（按维度限流失效）

- 注入点：`AuthService.sendResetCode` 删除 `loginGuard.checkCodeRateLimit(email, ip)` 首行调用。
- 击红：`Tests run: 2, Failures: 2`——已注册 / 未注册邮箱第 11 次均 200（无 429）。
  日志：`mut-m3-red.log`
- 复原 + 回绿：`Tests run: 2, Failures: 0` **BUILD SUCCESS**。日志：`mut-m3-green.log`

### m4 —— 计数退回两步 INCR/EXPIRE（原子化失效）

- 注入点：`LoginGuardService.incrementWithTtl` 的 Lua execute → 旧两步
  `opsForValue().increment + expire`（首次计数时）。
- 击红：`Tests run: 8, Failures: 8`——`execute` 原子调用断言与计数语义全红（含限流 429、
  锁定审计 IP、达阈值清计数）。日志：`mut-m4-red.log`
- 复原 + 回绿：`Tests run: 8, Failures: 0` **BUILD SUCCESS**。日志：`mut-m4-green.log`

### 等价变异体登记（不强杀）

`mutants.md` 文末登记 eq1（阈值 `count > perMinute` ↔ `count >= perMinute + 1` 算术等价）、
eq2（`ttl.toSeconds()` ↔ `ttl.getSeconds()` 字节等价）、eq3（Lua INCR 后回读 GET——
被测路径无并发写者，等价于直接 INCR 返回值）——如实登记，不投击红成本。

---

## 三、全量后端门禁实测

- 命令：`mvnw.cmd clean test`（仓库根，标准命令带 clean）
- 结果（原始输出见 `full-gate.log`）：`Tests run: 618, Failures: 0, Errors: 0, Skipped: 1`（Skipped 为存量用例）**BUILD SUCCESS**。
- 门禁数字链：599 → 618（backendTests 净增 +19 = 本阶段新增 19 例，只增不减；
  gate-baseline.json 上调属阶段 3 收口笔）。

---

## 四、红线核对

| 红线 | 落实 | 证据 |
|---|---|---|
| 白名单仅 `com/exam/auth/**` | 主代码只改 AuthController / AuthService / LoginGuardService / InviteCodeService / MailService；测试只增 `com/exam/auth/**` 6 类 | `git status --short` + 本 README 未触碰面 |
| ClientIpResolver 仅 import 不碰 anticheat 包 | `AuthController` 仅新增 import + 调用；`src/main/java/com/exam/anticheat/**` 零 diff | `git status --short`（无 anticheat 文件） |
| 零 DDL | `schema.sql` 零 diff | `git status --short`（无 schema.sql） |
| 零契约 | `openapi.yaml` / openapi paths 97 不变（无 DTO 字段增删，`SendResetCodeRequest` 等零改动） | `git status --short` + 源码 diff |
| 零前端 | `frontend/` 零 diff | `git status --short` |
| 测试禁 `@Sql` | 新增/改写用例无 `@Sql` 注解 | `grep -rn '@Sql' src/test`（仅既有类注释复述行） |
| 单测先红后绿 | 行为红 5/5 + 编译红 14 处 → 绿 19/19 | `red-before-impl-*.log` / `green-after-impl.log` |
| 变异击红留证 | m1–m4 全部真实击红-复原-回绿（无假绿） | `mut-*.log` / `mutants.md` / `run-mutations.ps1` |
| backendTests 只增不减 | 599 → 618（+19，实测全绿） | `full-gate.log` |

### 未触碰面

- `schema.sql`：零 diff（零 DDL）；`docker/`、`openapi.yaml`、`pom.xml`、`frontend/`：零 diff。
- `InviteCodeService.invalidate()`、`getValidByCode()`、`listAll()` 与邀请码生成链路：零改动。
- 登录统一失败提示防枚举、Refresh 轮换复用检测、强制改密流、验证码单次消费（GETDEL）：零改动。
- `anticheat` 包（含 `BehaviorCounterService` 同型 INCR/EXPIRE 问题）：不在本案白名单，台账保持未修注记。
