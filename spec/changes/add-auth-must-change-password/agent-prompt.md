# 子 agent 提示词 —— `add-auth-must-change-password`（后端小阶段）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/add-auth-must-change-password/proposal.md`、`tasks.json`、`specs/authentication/spec-delta.md`。

---

## 现状

- 仓库 `D:\code\examOnline`，分支 `feature/add-performance-deepening-readwrite`。
- **后端全量基线：`Tests run: 213, Failures: 0, Errors: 0, Skipped: 1` → BUILD SUCCESS**。`Skipped: 1` 是 `OpenApiContractTest.exportOpenApiContract()` 受 `@EnabledIfSystemProperty(named="exportContract")` 控制，**属设计使然，不要试图消除**。
- `must_change_password` 目前是**死列**。指导 agent 已核实（你不必重复核实，但动手前确认没变）：
  - `src/main` 中 `mustChangePassword` / `must_change_password` **仅 2 处**：`schema.sql` L14 建列、`com.exam.user.entity.User` L39 实体字段；
  - **无读路径**：零 getter 调用、无 DTO 装载、`JwtUtil` claim 不含它（claim 只有 `username / name / email / roles / permissions / roleLevel / sessionVersion`）；
  - **无写路径**：`AdminInitializer` 与 `AuthService` 根本没引用该字段，插入靠列默认值 0，**永不置 1**；
  - `CurrentUserResponse` 只有 `id / username / name / email / roles / permissions`。
- 最多修复尝试 **2 次**。第 3 次仍失败停下回报。
- **你必须自己 commit**（一次 `feat(authentication)`）。提交后立即 `git rev-parse HEAD`、`git status --short`、`git log --oneline -2` 并把三条原文贴进回报。HEAD 若变 unborn，从 `.git/logs/HEAD` 取 sha 按仓库约定补 ref，**不要用 `git update-ref`**。**若 commit 失败，贴 git 完整原始报错，不要编造原因、不要声称已提交。**
- **不要改 `spec/`**（含不要勾 `tasks.json`）。归档由指导 agent 做。

## 硬约定（违反即返工）

1. **重启死循环是本阶段最大的坑**：`AdminInitializer` **只能在首次创建 admin 时**置 `mustChangePassword = 1`；admin **已存在时绝对不能改写该字段**。否则每次重启都把已改过密码的 admin 打回强制改密。**必须有测试守住这一条**。
2. **不要把该标记塞进 JWT claim**。它是**可变状态**，入无状态 token 会产生「已改密但旧 token 仍说必须改密」的窗口。只能放 `CurrentUserResponse`，由 `/api/auth/me` 实时读库。代码注释写明这个取舍。
3. **不改鉴权拦截器**：本阶段只**暴露标记**，**不在后端拦截「未改密却调业务接口」**。后端强拦会波及所有既有集成测试且属行为变更。若你认为该强拦，停下回报，不要自己加。
4. **零 DDL、零数据迁移**：列已存在。**不改 `schema.sql`、不新增 `docker/mysql/migrations/` 脚本**。**不追溯**把存量 admin 置 1（那会把既有部署的管理员突然锁进强制改密，属破坏性变更）。
5. **不改任何既有接口签名的语义**：`/api/auth/me` 只**新增**响应字段，不删不改既有字段。
6. **契约必须重新导出**：改完 DTO 不导出 `openapi.yaml` 等于前端拿不到字段，本阶段白做。
7. **测试基线只增不减**：213 → 213+新增，`Skipped` 保持 1。禁止放宽断言、禁止 `@Disabled`、禁止改 H2 模式。
8. **禁止 `@Sql` 自建表**（项目硬约定，`src/test` 中 `@Sql` 注解必须零命中）。
9. **不动**：`docker/**`、告警 YAML、Grafana provisioning、`frontend/**`（当前尚不存在）、其它 Service / Controller / Mapper / 实体。
10. **不要顺手修** `ExamSubmitSender` 的真 broker 启动异常（遗留 #10，另行立项）。

## 写入边界

允许改 / 新增：

- `src/main/java/com/exam/auth/dto/CurrentUserResponse.java`
- `src/main/java/com/exam/auth/service/AuthService.java`（**仅** `currentUser()` 装载 + 改密成功置 0 两处；不动其它方法体）
- `AdminInitializer`（**仅**创建 admin 的分支）
- `openapi.yaml`（重新导出，覆盖）
- `src/test/java/...`（新增集成测试）

禁止其它路径。**特别禁止**：`pom.xml`、`schema.sql`、`docker/mysql/migrations/**`、`application*.yml`、`JwtUtil`、`WebMvcConfig`、`AuthenticationInterceptor`、`spec/**`。

## 实施

1. **基线**：跑全量，确认 213 / Skipped 1，记下数字。
2. **读路径**：`CurrentUserResponse` 加 `mustChangePassword`（`Boolean`）；`AuthService.currentUser()` 从 `User` 装载，`Integer → Boolean` 转换明确（**`null` 视为 `false`**，不要 NPE）。
3. **写路径 A**：`AdminInitializer` 仅在**首次创建** admin 的分支置 1；已存在分支**不触碰**该字段。
4. **写路径 B**：`/api/auth/password/change` 成功后置 0。
5. **测试**（走真实链路，用既有 `@SpringBootTest` + MockMvc + H2 模式，**不要 mock Service**）：
   - 新建上下文 → admin 登录 → `/api/auth/me` 断言 `mustChangePassword == true`；
   - 调 `/api/auth/password/change` 成功 → 再查 `/api/auth/me` 断言为 `false`；
   - **再次执行初始化（或再次触发 `AdminInitializer` 逻辑）→ 断言该标记仍为 `false`**（防重启死循环，这条最关键）；
   - 普通注册用户（非 admin）断言为 `false`；
   - Jackson 注意：项目用 `non_null`，**断言缺失字段用 `path()` 或 `assertNull(node.get(...))`，不要对缺失 key 直接 `.isNull()`**。
6. **重新导出契约**：用 `-DexportContract=true` 跑 `OpenApiContractTest` 的导出方法，或按下面 dev 实例方式导出。**导出后必须自验**：`openapi.yaml` 的 `CurrentUserResponse` schema 含 `mustChangePassword`；paths 数仍为 **65**；`/api/auth/login` 等 5 条公开端点仍有 `security: []`（不要破坏阶段 18 的免鉴权标注）。
7. **全量回归**，记录前后数字。

## 契约导出的两条路（任选，都要贴命令进回报）

**路 A（离线，推荐）**：

```powershell
$jargs = @('-classpath','D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar','-Dclassworlds.conf=D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf','-Dmaven.home=D:\develop\Maven\apache-maven-3.9.4','-Dmaven.multiModuleProjectDirectory=D:\code\examOnline','org.codehaus.plexus.classworlds.launcher.Launcher','-o','test','-Dtest=OpenApiContractTest','-DfailIfNoTests=false','-DexportContract=true'); & 'D:\develop\jdk177\bin\java.exe' @jargs
```

**路 B（真 dev 实例）**：环境事实见 `docs/指导Agent交接文档.md` §6.2 —— MySQL 容器 `exam-mysql-master` 在宿主 **13306**（不是 3306）、口令 **root123**（不是 root）；`application-dev.yml` 默认值指向 Windows MySQL80 服务，**会拒绝 root/root**；Redis 用宿主 6379，**不要启 `exam-redis` 容器**（端口冲突）；容器若 exited 用 `docker start exam-mysql-master exam-mysql-slave exam-rabbitmq`。启动前必须设 `DB_URL`/`DB_PASSWORD`/`SLAVE_DB_URL`/`SLAVE_DB_PASSWORD` 四个环境变量（完整命令见 `spec/changes/archive/add-backend-openapi/agent-prompt-round2.md` 修 4 节），然后 `Invoke-WebRequest 'http://localhost:8080/v3/api-docs.yaml' -OutFile openapi.yaml`，完事**停掉应用**。

> dev 启动日志里会出现 `ExamSubmitSender` → `waitForConfirmsOrDie` 的 `IllegalStateException`（遗留 #10，真 broker 下才暴露、非致命）。`/actuator/health` 返回 UP 即视为启动成功，**不要修它、不要因它判定失败**。
> dev 库 `exam_online` 有历史数据（26 张表，含垃圾表 `rep_test`），**不要删表、不要改数据**。

## 本机 Maven 全量命令（必须照抄）

```powershell
$jargs = @('-classpath','D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar','-Dclassworlds.conf=D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf','-Dmaven.home=D:\develop\Maven\apache-maven-3.9.4','-Dmaven.multiModuleProjectDirectory=D:\code\examOnline','org.codehaus.plexus.classworlds.launcher.Launcher','-o','test'); & 'D:\develop\jdk177\bin\java.exe' @jargs
```

PowerShell 下**必须数组 splatting**，否则 `-Dclassworlds.conf=...` 会被拆坏。不要用 PATH 里的 `mvn`。

## Commit

`git add` **明确列出**你改的文件（**不要用 `git add -A` / `git add .`**）。建议 message：

```
feat(authentication): 接通 must_change_password 初始密码强制修改标记
```

## 回报格式（按此六段，不要写散文）

1. **基线三数字 + Skipped 数**（改动前）
2. **改了哪些文件、各改了什么**：`CurrentUserResponse` 新增字段；`AuthService.currentUser()` 装载与 `Integer→Boolean` 的 null 处理；`AdminInitializer` 首次创建置 1 的**具体判据**（你怎么区分「首次创建」与「已存在」）；改密成功置 0 的落点
3. **防重启死循环的测试**：用例名 + 它怎么再次触发初始化 + 断言了什么
4. **契约重新导出**：用了路 A 还是路 B、命令原文、`CurrentUserResponse` schema 里 `mustChangePassword` 的 yaml 片段、paths 数、5 条公开端点 `security: []` 是否仍在
5. **收尾三数字 + Skipped 数 + commit 三条输出原文**（`git rev-parse HEAD` / `git status --short` / `git log --oneline -2`）
6. **意外发现**（含你是否按指示未动拦截器 / 未动 `ExamSubmitSender` / 未加迁移）

## 禁止

- 禁止把标记塞进 JWT claim；
- 禁止 `AdminInitializer` 在 admin 已存在时改写该标记；
- 禁止改鉴权拦截器做后端强拦；
- 禁止改 `schema.sql` / 加迁移脚本 / 追溯置存量 admin 为 1；
- 禁止改 `pom.xml` / `application*.yml` / `JwtUtil` / `WebMvcConfig`；
- 禁止删改 `/api/auth/me` 既有响应字段；
- 禁止手写或编造 `openapi.yaml`；禁止破坏阶段 18 的免鉴权标注；
- 禁止 mock Service 冒充集成测试；禁止 `@Sql`；
- 禁止放宽断言 / `@Disabled` / 改 H2 模式；
- 禁止顺手修 `ExamSubmitSender`；
- 禁止 `git add -A` / `git add .`；
- 禁止在 commit 未成功时声称已提交；
- 禁止勾 `tasks.json` 或归档 `spec/`。
