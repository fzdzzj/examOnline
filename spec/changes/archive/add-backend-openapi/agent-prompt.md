# 子 agent 提示词 —— `add-backend-openapi`（阶段 18）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/add-backend-openapi/proposal.md` 与 `tasks.json`。

---

## 现状

- 仓库 `D:\code\examOnline`，分支 `feature/add-performance-deepening-readwrite`。
- 这是**纯后端单体**（Java 17 + Spring Boot 3.5.5 + MyBatis-Plus），**当前仓库无前端**。本阶段是前端系列（阶段 19–23）的前置：让后端暴露 OpenAPI 契约，供前端用 `@hey-api/openapi-ts` 生成类型化客户端。
- 已核实事实（指导 agent grep/read 过，你不必重复核实，但要在使用前确认没变）：
  - `pom.xml` **无** springdoc / swagger 依赖；
  - 后端 **14 个 Controller**，全部挂 `/api/**` 前缀：`anticheat/BehaviorLogController`、`auth/AdminController`、`auth/AuthController`、`clazz/ClassController`、`exam/ExamController`、`grading/GradingController`、`monitoring/MonitorController`、`paper/PaperController`、`question/QuestionController`、`question/TagController`、`score/ScoreController`、`score/ScoreReviewController`、`submission/DlqAdminController`、`taking/ExamTakingController`；
  - 鉴权拦截器只拦 `/api/**`（`com.exam.auth.security.WebMvcConfig.addInterceptors`），**`/v3/api-docs` 与 `/v3/api-docs.yaml` 不在拦截范围，无需改认证白名单代码**；
  - 公开端点（无需 Access Token）：`/api/auth/login`、`/api/auth/register`、`/api/auth/refresh`、`/api/auth/password/reset-code`、`/api/auth/password/reset`、`/actuator/**`；
  - **后端全量测试基线：`Tests run: 210, Failures: 0, Errors: 0, Skipped: 0` → BUILD SUCCESS**（阶段 17 归档时由指导 agent 独立重跑）。
- 最多修复尝试 **2 次**。第 3 次仍失败就停下回报，不要硬凑。
- **你必须自己 commit**（一次 `feat(api-contract)` 或 `feat(openapi)`）。提交后立即 `git rev-parse HEAD` 与 `git status --short`；HEAD 若变 unborn，从 `.git/logs/HEAD` 取 sha 并按仓库约定补 ref，**不要用 `git update-ref`**。
- **不要改 `spec/`**（含不要勾 `tasks.json`，不要动 `spec/README.md`）。归档由指导 agent 做。

## 硬约定

1. **不改任何 Controller 签名、DTO 字段、业务逻辑、SQL**。契约要如实描述现状，不许为了「schema 好看」改后端。
2. **`openapi.yaml` 必须是真实导出的产物，严禁手写或让模型编造**。手写契约会让前端生成的类型与后端漂移，等于埋雷。若无法启动应用导出，**停下回报**，不要交一份编造的 yaml。
3. **测试基线只增不减**：210 → 211+。禁止为了让测试绿而放宽断言、改 H2 模式、加 `@Disabled`。
4. **禁止 `@Sql` 自建表**（项目硬约定，`src/test` 中 `@Sql` 注解必须保持零命中）。
5. 不改 `application*.yml` 的既有配置项语义；新增 springdoc 配置只**追加**，且不得影响现有 profile 行为。
6. 不动 `docker/`、`docs/observability-runtime-evidence.md`、告警 YAML、Grafana provisioning。

## 写入边界

允许改 / 新增：

- `pom.xml`（**仅**新增 springdoc 依赖，不动既有依赖与插件配置）
- `src/main/java/com/exam/common/config/SpringDocConfig.java`（新增；若该包路径不存在，选一个已有的 config 包，**不要新建顶层包**）
- `src/main/resources/application*.yml`（仅追加 springdoc 配置项）
- `openapi.yaml`（新增，仓库根，由 `/v3/api-docs.yaml` 导出）
- `src/test/java/...`（新增 1 个契约冒烟测试类，建议 `com.exam.support.OpenApiContractTest`）

禁止其它路径。**特别禁止**：`src/main/resources/schema.sql`、`docker/mysql/migrations/**`、任何 `*Service.java` / `*Controller.java` / `*Mapper.java` / 实体类。

## 实施

1. **加依赖**：`springdoc-openapi-starter-webmvc-ui`，版本选与 Spring Boot 3.5.5 兼容的 2.8.x。先确认能离线解析（本机 Maven 仓库 `D:\code\examOnline\.m2-repo`，命令必须带 `-o`）；**若离线仓库没有该 artifact，停下回报**，不要擅自去掉 `-o` 联网下载（涉及用户环境，需授权）。
2. **写 `SpringDocConfig`**：
   - `OpenAPI` bean：标题 `examOnline API`、版本、简述；
   - 定义 `SecurityScheme`：HTTP bearer（JWT），name 用 `Authorization`；
   - 全局挂该 scheme，使生成客户端带鉴权语义；
   - 5 个公开端点标注免鉴权（用 `@SecurityRequirements` 或在 config 里按路径排除，二选一，**不要改 Controller 上的业务注解语义**）；
   - 注释写「为什么」：契约是前端客户端生成的唯一来源，controller 新增端点后必须重新导出。
3. **导出契约**：
   - 启动应用（dev profile，宿主 8080）。本机环境特例：Windows MySQL80 服务占 3306、Windows Redis 服务占 6379，**不要试图改端口或停这些服务**；RabbitMQ 若未运行导致启动失败，**停下回报**，不要为了让它起来而改产品代码或 `application.yml`。
   - `Invoke-WebRequest http://localhost:8080/v3/api-docs.yaml -OutFile openapi.yaml`（springdoc 默认提供 yaml 端点）。
   - 导出后核对：14 个 Controller 的路径都能在 `openapi.yaml` 的 `paths:` 下找到。**逐个包名核，不要只看条数**。
   - 在回报里写清导出命令，保证可复现。
4. **契约冒烟测试**（`com.exam.support.OpenApiContractTest`）：
   - 用项目既有集成测试的启动方式（`@SpringBootTest` + test profile + MockMvc），请求 `/v3/api-docs`；
   - 断言 200、`paths` 非空、**paths 数量 ≥ 一个写死的下限**（按你导出时实际数量取一个略低的整数，例如实际 60 就写 55），失败信息写明「新增端点后必须重新导出 openapi.yaml」；
   - 断言 `components.securitySchemes` 含 bearer scheme；
   - 纯静态读 `openapi.yaml` 断言它不是空文件且含 `openapi:` 版本行（防契约文件被误清空）。
5. **全量回归**，记录前后三数字。

## 本机 Maven 命令（必须照抄，别自己拼）

PowerShell 下**必须用数组 splatting**，否则 `-Dclassworlds.conf=...` 会被拆坏（指导 agent 已踩坑两次）：

```powershell
$jargs = @('-classpath','D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar','-Dclassworlds.conf=D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf','-Dmaven.home=D:\develop\Maven\apache-maven-3.9.4','-Dmaven.multiModuleProjectDirectory=D:\code\examOnline','org.codehaus.plexus.classworlds.launcher.Launcher','-o','test'); & 'D:\develop\jdk177\bin\java.exe' @jargs
```

单类：在数组末尾追加 `'-Dtest=ClassName','-DfailIfNoTests=false'`。

**不要**用 PATH 里的 `mvn`（版本错）。**不要**把交接文档里的多行反引号版本压成一行（会解析失败）。

## Commit

一次提交，建议 message：

```
feat(api-contract): 暴露 OpenAPI 契约供前端生成类型化客户端
```

## 回报格式（按此五段，不要写散文）

1. **基线三数字**（改动前全量回归，直调 jdk177 launcher）
2. **加了什么依赖 / 什么版本 / 是否离线可解析**；`SpringDocConfig` 放在哪个包、bearer scheme 与免鉴权端点怎么标的
3. **契约导出**：导出命令、`openapi.yaml` 行数、14 个 Controller 逐个是否命中（列表）、paths 总数
4. **冒烟测试类名与它断言什么**（含你写死的 paths 下限数字）
5. **收尾三数字 + `git rev-parse HEAD` + `git status --short`**
6. **意外发现**（含：应用是否需要 RabbitMQ 才能启动、swagger-ui 是否默认可访问、有无端点因缺注解而 schema 不完整）

## 禁止

- 禁止手写 / 编造 `openapi.yaml`；
- 禁止为了导出成功而改产品代码、改端口、停本机服务、去掉 `-o`；
- 禁止改 Controller / DTO / Service / Mapper / 实体 / schema.sql / 迁移脚本；
- 禁止放宽测试断言、禁止 `@Disabled`、禁止改 H2 模式；
- 禁止顺手做阶段 19–23 的前端工作（**本阶段不建 `frontend/` 目录**）；
- 禁止把 `spec/changes/add-backend-openapi/` 归档或勾 tasks。
