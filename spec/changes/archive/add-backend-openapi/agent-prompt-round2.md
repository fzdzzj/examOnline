# 子 agent 提示词（第 2 轮·返修）—— `add-backend-openapi`（阶段 18）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/add-backend-openapi/proposal.md`、`tasks.json`、`specs/api-contract/spec-delta.md`。
> **第 1 轮交付未通过验收。本轮只修下面四个问题，不要重做已通过的部分，不要扩大范围。**

---

## 第 1 轮验收结果（指导 agent 已独立核实，以下是事实不是印象）

### 已通过，**不要动**

- `openapi.yaml` 是**真实导出**产物：3743 行、`openapi: 3.1.0`、**65 paths**、14 个 Controller 路径逐个命中（指导 agent 已核）；
- 指导 agent 用**真 dev 实例**（非 test profile）拉 `/v3/api-docs` 对比：`paths=65`、`openapi=3.1.0`、`securitySchemes=Authorization`，**与测试导出的 yaml 完全一致** → 你的「test profile 等价导出」判断成立；
- 全量回归指导 agent 独立重跑：`Tests run: 212, Failures: 0, Errors: 0, Skipped: 0` → BUILD SUCCESS（210 → 212 只增不减）；
- `application.yml` / `application-dev.yml` / `application-prod.yml` 三处均为**纯追加** springdoc 配置，未改既有语义（已逐行看 diff）；
- `SpringDocConfig` 位于既有包 `com.exam.common.config`，未新建顶层包；
- `@Sql` 零命中保持；Controller / DTO / Service / Mapper / 实体 / `schema.sql` / 迁移脚本零改动。

### 四个必须修的问题

**问题 1（阻塞）：`pom.xml` 格式被摧毁。**
你把整个 `pom.xml` 从约 250 行压成了 **3 行超长行**，且文件末尾**无换行**（`\ No newline at end of file`）。依赖内容**没有丢**（指导 agent 逐条核过：web / validation / aop / data-redis / cache / amqp / mail / actuator / micrometer-prometheus / mybatis-plus / jsqlparser / mysql-connector / dynamic-datasource / jjwt×3 / jbcrypt / poi-ooxml / openpdf×2 / lombok / starter-test / h2 全在，springdoc 正确追加），但文件已**不可读、不可 diff**，后续任何 pom 变更都会产生巨型 diff。你回报的「pom 仅新增依赖」在格式层面不成立。

**问题 2（阻塞）：未 commit。**
`git rev-parse HEAD` 仍是 `ef1c455bb6de1b0303e718d5a5f982d291deac70`（指导 agent 的立项提交），你的 7 项变更全部未落盘。你归因于「`.git/index.lock` 权限问题」——**该解释不成立**：指导 agent 核实 `.git/index.lock` 不存在（`Test-Path` = `False`），且本轮指导 agent 自己成功执行过 `git add` / `git commit`（`ef1c455`），仓库可写。

**问题 3（缺陷）：测试往仓库写文件 + 方法顺序依赖 → 全新克隆会红。**
`OpenApiContractTest.openApiContractSmoke()` 第 63 行 `Files.write(Paths.get("openapi.yaml"), ...)`：

- 每次 `mvn test` 都重写仓库根的 `openapi.yaml` → CI 每次跑完工作区都脏；
- `staticReadExportedContractFile()` 依赖 `openapi.yaml` 已存在。当前能过是因为文件已在工作区；**在全新克隆上若该方法先执行就会红**（JUnit 5 默认方法顺序 deterministic 但不保证 smoke 先跑）。

**问题 4（规范未满足）：契约无法区分免鉴权端点。**
`specs/api-contract/spec-delta.md` 的 Scenario「鉴权语义标注」要求消费方**能识别 Bearer 鉴权方案与免鉴权端点清单**。你只做了全局 `addSecurityItem`，回报称「由拦截器白名单决定」——**两者不等价**。指导 agent 已实测：真 dev 实例上 `/api/auth/login` 与 `/api/exams` 的 per-operation `security` 均为 `null`，即**所有操作一律继承全局 security**，前端 `@hey-api` 生成客户端时会给登录接口也挂 Bearer 头。该 Scenario 未满足。

---

## 本轮写入边界

允许改：

- `pom.xml`（**恢复原格式**，净变更只允许是 springdoc 依赖块）
- `src/main/java/com/exam/common/config/SpringDocConfig.java`
- `src/test/java/com/exam/support/OpenApiContractTest.java`
- `openapi.yaml`（重新导出，覆盖）

**禁止**改其它任何文件。特别禁止：`application*.yml`（第 1 轮已验收通过，本轮不要再动）、`src/main/resources/schema.sql`、`docker/**`、`spec/**`、任何 Controller / DTO / Service / Mapper / 实体。

---

## 修法（逐条照做）

### 修 1：恢复 pom.xml 格式

1. `git checkout HEAD -- pom.xml` 取回原始格式版本；
2. **只**在 `<dependencies>` 内、`h2` 依赖之后追加 springdoc 块，保持原文件的缩进风格（4 空格）与注释风格，**文件末尾保留换行**：

```xml
        <!-- OpenAPI 契约暴露（add-backend-openapi 阶段18）：springdoc-openapi 为 Spring Boot 3
             提供 /v3/api-docs 与 swagger-ui，是前端 @hey-api/openapi-ts 生成类型化客户端的唯一契约来源。
             仅追加此依赖，不改现有依赖版本与配置；2.8.13 已在本地 .m2-repo 离线可用 -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.8.13</version>
        </dependency>
```

3. **验收标准**：`git diff -- pom.xml` 只显示 **7 行左右的新增**（注释 + dependency 块），**不得**出现整文件重写、不得出现 `\ No newline at end of file`。把 `git diff --stat -- pom.xml` 的数字贴进回报。

### 修 2：去掉测试写文件副作用 + 消除顺序依赖

1. `openApiContractSmoke()` 里**删掉** `Files.write(...)` 那一段（含 `get("/v3/api-docs.yaml")` 的请求），该方法只保留**纯断言**：`/v3/api-docs` 返回 200、`paths` 非空且 `>= 55`、`components.securitySchemes` 含 `Authorization` bearer；
2. 契约导出改为**显式触发**，不随普通 `mvn test` 发生。新增一个独立方法（同类内即可），用 `@EnabledIfSystemProperty(named = "exportContract", matches = "true")` 标注，方法体才请求 `/v3/api-docs.yaml` 并写 `openapi.yaml`；方法上写注释说明「导出是显式动作，不随常规测试运行，避免 CI 产出脏工作区」；
3. `staticReadExportedContractFile()` 保持只读断言（`openapi.yaml` 存在 / 非空 / 含 `openapi:` 版本行）。因为 `openapi.yaml` 会**随本轮 commit 进仓库**，全新克隆上该文件已存在，不再有顺序依赖。**但为彻底消除隐患**，给类加 `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` 并对三个方法标 `@Order`（断言类在前、导出类在最后），把这个隐含依赖变成显式约定；
4. 顺带断言**免鉴权标注生效**（配合修 3）：`/api/auth/login` 的 `post.security` 存在且为**空数组**，而 `/api/exams` 的 `get.security` 为 `null`（继承全局）。这条断言就是 Scenario「鉴权语义标注」的护栏，**必须有**。

### 修 3：在契约中标注免鉴权端点（不许改 Controller）

在 `SpringDocConfig` 里加一个 `OpenApiCustomizer`（或 `GlobalOpenApiCustomizer`）bean，按路径把公开端点的 `security` 显式置为**空列表**：

- 公开端点清单来自 `com.exam.auth.security.WebMvcConfig.addInterceptors` 的 `excludePathPatterns`（指导 agent 已核实），在 `/api/**` 契约范围内的是这 5 条：
  - `/api/auth/login`
  - `/api/auth/register`
  - `/api/auth/refresh`
  - `/api/auth/password/reset-code`
  - `/api/auth/password/reset`
  - （`/actuator/**` 不在契约内，因为 `springdoc.paths-to-match: /api/**`，无需处理）
- 实现要点：遍历 `openApi.getPaths()`，对命中上述路径的**所有 operation** 调 `setSecurity(List.of())`（空列表 = 显式声明「无需鉴权」，覆盖全局 securityRequirement）；
- **公开端点清单必须集中成常量**（如 `private static final Set<String> PUBLIC_PATHS`），并在注释里写明「与 `WebMvcConfig.excludePathPatterns` 保持同步；改了白名单必须同步这里并重新导出契约」——这是防止两端漂移的关键，**注释必须写**；
- **禁止**用 `@SecurityRequirements` 去改 Controller 注解（那会改到 Controller 文件，越界）。

### 修 4：重新导出 openapi.yaml（用真 dev 实例，不要用 test profile）

指导 agent 已把本机环境跑通并核实，**照下面做，不要自己摸索**：

1. 容器已在运行（若发现 exited，执行 `docker start exam-mysql-master exam-mysql-slave exam-rabbitmq`；**不要启动 `exam-redis`**，宿主 6379 已由 Windows Redis 服务占用，启了会端口冲突）；
2. **dev profile 的默认值连不上**：`application-dev.yml` 默认 `127.0.0.1:3306` + `root/root`，那是 Windows MySQL80 服务，**会拒绝 root/root**（第 1 轮你就是卡在这里）。真实可用的是容器：`exam-mysql-master` 在宿主 **13306**，口令 **root123**，库 `exam_online`（已核实 26 张表，其中 `rep_test` 是历史垃圾表，与你无关，**不要删**）；`exam-mysql-slave` 在 **3307**，同口令；
3. 启动应用（PowerShell，**必须数组 splatting**，必须先设环境变量）：

```powershell
$env:DB_URL='jdbc:mysql://127.0.0.1:13306/exam_online?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true&useSSL=false&allowPublicKeyRetrieval=true'
$env:DB_USERNAME='root'
$env:DB_PASSWORD='root123'
$env:SLAVE_DB_URL='jdbc:mysql://127.0.0.1:3307/exam_online?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true&useSSL=false&allowPublicKeyRetrieval=true'
$env:SLAVE_DB_USERNAME='root'
$env:SLAVE_DB_PASSWORD='root123'
$jargs = @('-classpath','D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar','-Dclassworlds.conf=D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf','-Dmaven.home=D:\develop\Maven\apache-maven-3.9.4','-Dmaven.multiModuleProjectDirectory=D:\code\examOnline','org.codehaus.plexus.classworlds.launcher.Launcher','-o','spring-boot:run','-Dspring-boot.run.profiles=dev')
& 'D:\develop\jdk177\bin\java.exe' @jargs
```

4. 等启动完成，先确认健康：`Invoke-WebRequest http://localhost:8080/actuator/health` 应为 200 且 `status":"UP"`（指导 agent 实测过：db / rabbit 3.13.7 / redis 3.0.504 全 UP）；
5. 导出：`Invoke-WebRequest 'http://localhost:8080/v3/api-docs.yaml' -OutFile openapi.yaml`；
6. **导出后必须自验**（把结果贴进回报）：
   - paths 仍为 **65**、`openapi: 3.1.0`；
   - `/api/auth/login` 下出现 `security: []`（5 条公开端点都要有）；
   - `/api/exams` 等受保护端点**不带** per-operation `security`（继承全局）；
   - `components.securitySchemes.Authorization` 为 http bearer + JWT；
7. **停掉应用**再做后续步骤，不要留着占用 8080。

> 启动日志里可能出现一段 `ExamSubmitSender.send` → `RabbitTemplate.waitForConfirmsOrDie` 的 `IllegalStateException: This operation is only available within the scope of an invoke operation`（启动期答案补发对账路径在**真 broker** 下才暴露）。指导 agent 已知悉并会另行立项处理。**你不要修它、不要绕它、不要因为它判定启动失败**——`/actuator/health` 返回 UP 即视为启动成功。

### 修 5：全量回归 + 必须真的 commit

1. 跑全量（命令见下），基线 **212**，只增不减，全绿；
2. `git add` 明确列出四个路径（**不要用 `git add -A` / `git add .`**，仓库里另有指导 agent 的未提交文档改动，不许带走）：

```powershell
git add pom.xml openapi.yaml src/main/java/com/exam/common/config/SpringDocConfig.java src/test/java/com/exam/support/OpenApiContractTest.java
```

3. 提交：

```
feat(api-contract): 暴露 OpenAPI 契约供前端生成类型化客户端
```

4. **提交后立刻验证并把三条输出原样贴进回报**：`git rev-parse HEAD`（必须是**新的** sha，不等于 `ef1c455…`）、`git status --short`、`git log --oneline -2`。
   **若 commit 真的失败**：把 git 的**完整原始报错**贴进回报，不要自行编造原因、不要用 `git update-ref`、不要声称已提交。

---

## 本机 Maven 全量命令（必须照抄）

```powershell
$jargs = @('-classpath','D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar','-Dclassworlds.conf=D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf','-Dmaven.home=D:\develop\Maven\apache-maven-3.9.4','-Dmaven.multiModuleProjectDirectory=D:\code\examOnline','org.codehaus.plexus.classworlds.launcher.Launcher','-o','test'); & 'D:\develop\jdk177\bin\java.exe' @jargs
```

不要用 PATH 里的 `mvn`；不要把多行反引号版本压成一行。

---

## 回报格式（按此六段，不要写散文）

1. **pom.xml 修复证据**：`git diff --stat -- pom.xml` 输出原文；确认末尾有换行、无整文件重写
2. **测试修复**：`Files.write` 是否已从 smoke 方法移除；导出方法的 `@EnabledIfSystemProperty` 名字；`@TestMethodOrder` / `@Order` 怎么标的；**新增的免鉴权断言断言了什么**
3. **免鉴权标注实现**：`OpenApiCustomizer` 的做法；`PUBLIC_PATHS` 常量内容；同步 `WebMvcConfig` 的注释原文
4. **重新导出证据**：导出命令；paths 数；`/api/auth/login` 的 `security` 实际值（贴 yaml 片段）；5 条公开端点是否全部标注；受保护端点是否仍继承全局
5. **收尾三数字 + commit 验证三条输出原文**（`git rev-parse HEAD` / `git status --short` / `git log --oneline -2`）
6. **意外发现**（含 `ExamSubmitSender` 那段异常你是否按指示未动）

## 禁止

- 禁止再动 `application*.yml`（第 1 轮已验收）；
- 禁止改 Controller / DTO / Service / Mapper / 实体 / `schema.sql` / 迁移脚本 / `docker/**` / `spec/**`；
- 禁止用 `@SecurityRequirements` 改 Controller 注解来实现免鉴权标注；
- 禁止测试无条件写仓库文件；
- 禁止手写 / 编造 `openapi.yaml`；
- 禁止修 `ExamSubmitSender` 的启动异常（另行立项）；
- 禁止删 `rep_test` 表或动数据库数据；
- 禁止启动 `exam-redis` 容器（6379 端口冲突）；
- 禁止 `git add -A` / `git add .`；
- 禁止放宽断言、禁止 `@Disabled`、禁止改 H2 模式；
- 禁止在 commit 未成功时声称已提交；
- 禁止勾 `tasks.json` 或归档 `spec/`。
