# examOnline 项目交接文档（指导 Agent 视角）

> **交接对象**：下一位项目指导 Agent
> **文档时点**：2026-09-17
> **当前分支**：`feature/add-performance-deepening-readwrite`
> **当前性质**：阶段 1–18 已归档；阶段 19–23（前端五方向）已立项待实施。
> **代码 HEAD**：`47d42f85613418a663a89a2656532249c7e5d1e9`（阶段 18 补提交 springdoc 配置）；其前为 `000bbf0`（阶段 18 主体）、`ef1c455`（前端系列立项）。
> **后端测试基线**：`Tests run: 213, Failures: 0, Errors: 0, Skipped: 1`（Skipped 为受 `exportContract` 开关控制的契约导出方法，属设计使然）。
>
> 本文档只记录当前已核实事实、已知未知和下一步边界。子 agent 的回报不是事实；每轮交付必须独立查看 `git status`、`git diff`、关键代码和测试报告。

---

## 一、项目定位

这是个人面试作品，不是商业生产系统。

目标不是继续堆功能，而是把关键路径做成三层可回答：

- **L1：功能正确**——正常流程可用；
- **L2：并发边界正确**——并发、重复、超时、崩溃、网络分区下行为可解释；
- **L3：可观测、可恢复、可证明**——有指标、日志、对账、自愈、动态证据和压测数据。

判断是否值得做的标准：能否让一条关键路径从「我说它有」变成「我能证明它有」。

**不要为了“架构优化”而重写已经能讲清楚的单体。** 当前明确不建议：

- 拆微服务；
- Caffeine + Redis 二级缓存；
- 为整洁拆 `PaperService` / `ScoreService` God class；
- 引入 ShedLock / Quartz / Redisson；
- 给 DLQ 加 TTL / max-length；
- 在线 `OPTIMIZE TABLE`；
- 为点亮告警而降低阈值或在 Java 中 `sleep`。

---

## 二、当前仓库状态（已核实）

### 2.1 Git

- 分支：`feature/add-performance-deepening-readwrite`
- 阶段 17 实施 commit：`83bc9ca fix(data-access): schema.sql 为 AUTO_INCREMENT 补主键以兼容 MySQL 8`（仅 3 文件，161 行纯新增）
- 最近提交（实施之前）：
  - `6c9b69d docs(spec): 归档 add-observability-runtime-evidence 并合入 observability 规范`
  - `ff7e981 docs(observability): 记录观测栈动态验证证据（抓取 UP / 规则 firing / 面板出图）`
  - `580fe67 docs(spec): 归档 add-data-retention 并合入 data-access 规范`
  - `f42adba feat(retention): 按考试生命周期有界清理三张辅助表`
- 当前没有已跟踪文件修改（本次归档 docs commit 之后）。
- 仓库根曾有的 `%SystemDrive%/` 未跟踪垃圾目录（历史命令把环境变量当字面量展开失误所致）已于 2026-09-17 清理，从未进入任何 commit。

### 2.2 OpenSpec 状态

- `spec/changes/` 当前只剩 `archive/`（共 20 个已归档变更，阶段 1–17 全部收尾）。
- `spec/README.md` 当前事实：
  - 进行中变更：**无**；
  - 已合入能力域规范：15 个；
  - `data-access` 规范新增两个 Requirement（新库建表可在 MySQL 8 执行 / AUTO_INCREMENT 列必须有主键）；
  - 阶段 14 的 DLQ 真 broker 往返仍是遗留，不能因为告警 firing 就声称 DLQ 重投端到端完成；
  - 阶段 17 的 MySQL 空库真机初始化仍未实测，已列入遗留事项 #9。

### 2.3 阶段 16 动态观测证据

证据文件：`D:\code\examOnline\docs\observability-runtime-evidence.md`

已记录并已进入提交的事实：

- Prometheus target `exam-online` 为 `UP`；
- 9 条告警规则已加载；
- 真实 firing：`ExamOnlineDown`、`RateLimitDegraded`、`MqSubmitRetryExhausted`、`MqDlqBacklog`、`AntiCheatEventSpike`；
- 未点着且保留 PromQL 反证：`Http5xxRatioHigh`、`SubmitFailureRatioHigh`、`MqSubmitQueueBacklog`、`SubmitLatencyP99High`；
- Grafana 数据源 uid=`prometheus`，总览面板不是整页 `No data`；
- **未证明**：坏消息真进 DLQ 后再通过 broker 真重投回来；该事项仍属于遗留 #6；
- **未证明**：5000 并发交卷 P99 / 丢单 / 批量落库硬指标。

环境特例只作为运行证据，不要改成产品代码：Windows MySQL80 占用 3306、Windows Redis 服务占用 6379、从库未同步时 slave 读可能 404；空 MySQL 执行旧版 `schema.sql` 暴露了主键问题，阶段 17 专门处理。

---

## 三、技术栈与核心能力

| 类别 | 选型 |
|---|---|
| 语言/框架 | Java 17 + Spring Boot 3.5.5 |
| 持久层 | MyBatis-Plus 3.5.9 + jsqlparser 分页 |
| 数据库 | MySQL 8 主从，master 3306 / slave 3307 |
| 缓存 | Redis 7：缓存、草稿、计数器、令牌桶、黑名单、分布式锁 |
| 消息 | RabbitMQ 3.13：publisher confirm、手动 ack、批量落库、重试、DLQ |
| 多数据源 | dynamic-datasource 4.3.1，`@DS` + 读己之写 |
| 可观测 | Micrometer、Prometheus registry、慢 SQL、Grafana、告警规则 |
| 认证 | jjwt 双 Token + bcrypt + RBAC |
| 构建 | Maven，必须使用本机直调 launcher |

核心路径：

```text
出题 → 组卷快照 → 建考试 → 发布
→ 学生进入（个人快照）→ 自动保存 → 交卷
→ CAS/唯一索引/Redis 锁幂等 → MQ 削峰 → 批量落库
→ 客观/主观判分 → 汇总 → 发布 → 导出
→ 缺考 → 补考 → 成绩复核
```

四个面试深水区：交卷可靠性、认证安全、数据一致性、性能工程。

---

## 四、阶段状态与已交付资产

| 阶段 | 变更 | 状态 | 关键结果 |
|---|---|---|---|
| 1–11 | 历史变更 | 已归档 | 工程、认证、题库、考试、交卷、判分、性能、观测基础 |
| 12 | `add-post-exam-closure-e2e` | 已归档 | 闭环 E2E；修 force-end 缺考；补 `score_review.created_time`；删 `@Sql`；修 `@PathVariable` |
| 13 | `add-multi-instance-sweep-safety` | 已归档 | token 解锁；双线程扫描证据；不加调度锁；重复扫描指标 |
| 14 | `add-dlq-observability-and-replay` | 已归档 | DLQ 指标、告警、面板、有界留档重投；真 broker 仍未证 |
| 15 | `add-data-retention` | 已归档 | 默认关闭 + dry-run；按考试生命周期清理三张辅助表；零 DDL；不纳入 DLQ 表 |
| 16 | `add-observability-runtime-evidence` | 已归档 | 9 条规则 loaded；5 条真实 firing；4 条留反证；面板出图 |
| 17 | `fix-schema-mysql-pk` | 已归档 | 25 张表补 `PRIMARY KEY (id)`；W16 存量迁移脚本；文本约定测试；210 全绿；**MySQL 空库真机初始化未实测** |
| 18 | `add-backend-openapi` | 已归档 | springdoc 2.8.13；`openapi.yaml` 65 paths / 3.1.0 / 覆盖 14 Controller；5 个公开端点标 `security: []`；契约冒烟测试；**返修 1 轮**（pom 格式被压成 3 行、测试写仓库文件 + 方法顺序依赖、免鉴权未标注、未 commit） |
| 19–23 | 前端五方向 | **已立项待实施** | 骨架+认证 / 题库组卷 / 考试管理 / 学生端考试 / 考后闭环；串行执行，提示词见各变更目录 `agent-prompt.md` |

### 4.1 阶段 17 资产位置（已归档）

- 提案：`D:\code\examOnline\spec\changes\archive\fix-schema-mysql-pk\proposal.md`
- 任务（已按证据回勾）：`D:\code\examOnline\spec\changes\archive\fix-schema-mysql-pk\tasks.json`
- 子 agent 提示词：`D:\code\examOnline\spec\changes\archive\fix-schema-mysql-pk\agent-prompt.md`
- 规范差异（已合入 data-access）：`D:\code\examOnline\spec\changes\archive\fix-schema-mysql-pk\specs\data-access\spec-delta.md`

实施产物（commit `83bc9ca`，仅 3 文件）：

- `src/main/resources/schema.sql`：25 个建表块各补一行 `PRIMARY KEY (id)`（置于既有 UNIQUE/KEY 之前，未删任何约束）；
- `docker/mysql/migrations/2026-W16-add-primary-keys.sql`：25 条 `ALTER TABLE … ADD PRIMARY KEY (id)`，头注释写明 1068/1146 属预期、不做存储过程静默化；
- `src/test/java/com/exam/support/SchemaSqlMysqlCompatibilityTest.java`：纯 JUnit 文本护栏，凡含 `AUTO_INCREMENT` 的建表块必须有 `PRIMARY KEY (id)`，缺主键就红并列出表名；另含「自增块 ≥25」防假绿护栏。

---

## 五、阶段 17 的已核实结果与证据边界

### 5.1 已核实结果（指导 Agent 独立核，非子 agent 回报）

- grep 计数：`AUTO_INCREMENT` 25 处 ↔ `PRIMARY KEY (id)` 25 处，一表一键；主键行逐一落在对应表块约束区；
- `CREATE TABLE` 计 26 处中第 26 处为注释文字（schema.sql L396），真实建表块 25 个；
- `src/test` 的 `@Sql` 唯一命中为 javadoc 文字「禁止 @Sql 自建表」，非真实注解；
- 指导 Agent 独立重跑全量（数组 splatting 直调 jdk177 launcher）：`Tests run: 210, Failures: 0, Errors: 0, Skipped: 0` → BUILD SUCCESS（209 → 210 只增不减）；
- `pom.xml`、实体、Mapper、业务 SQL 零改动。

### 5.2 证据边界（如实记录，不得越界声称）

- **MySQL 8 空库真机初始化仍未验证**——证据止于文本护栏 + H2 全绿，已列入 `spec/README.md` 遗留事项 #9；
- **W16 存量迁移脚本未在真实存量库执行过**——头注释已写明预期报错（1068/1146），但无真机记录；
- tasks.json 已按上述实际证据回勾，未勾任何越界声称。

---

## 六、已知坑

| 坑 | 正确做法 |
|---|---|
| Maven PATH 版本错误 | 使用 `D:\develop\jdk177\bin\java.exe` + Plexus launcher；必须带 `-o` |
| Git 分支 ref 丢失 | 每次 commit 后立即 `git rev-parse HEAD`；若 unborn，从 `.git/logs/HEAD` 取 sha 并按仓库约定补 ref，不用 `git update-ref` |
| `@Sql` 掩盖建表缺陷 | `src/test` 中保持 `@Sql` 零命中；schema.sql 是唯一测试建表来源 |
| MySQL / H2 方言差异 | 不能只看 H2；阶段 17 正在补 MySQL 8 的主键护栏 |
| schema 新增列不自动迁移 | 同时改 schema 与迁移；`IF NOT EXISTS` 不会补已有表列 |
| Jackson `non_null` | 缺失字段用 `path()` 或 `assertNull(node.get(...))`，不要对缺失 key 直接 `.isNull()` |
| 读己之写 | 答卷/成绩强一致读不要加 `@DS("slave")` |
| DLQ 证据边界 | 告警 firing 不等于 DLQ broker 往返端到端完成 |
| 清理磁盘误解 | `DELETE` 不等于释放 InnoDB 文件空间；不得声称磁盘已回收 |
| 观测告警凑数 | 不改阈值、不用 sleep；点不着就记录 PromQL 反证 |
| `git stash` + `reset` 丢工作 | 阶段 19 返修时子 agent `stash push` 后未 pop 又执行 `reset`，11 个文件改动从工作区消失；靠 `git fsck --lost-found` 找到 dangling stash commit 再 `git stash apply <sha>` 才恢复。**子 agent 提示词一律禁止 `stash` / `reset` / `checkout -- .` / `clean`**；要检查历史版本只能用 `git worktree add`；要固化未提交改动就**先 commit**，别 stash |
| 工作区 ≠ 提交 | 子 agent 报「工作区 clean」不可信；每轮验收必须自己跑 `git status --short` **加** `git diff --numstat`（行尾噪音时 `--numstat` 无输出，真实改动才有数字）。门禁若跑在工作区而 HEAD 是旧版本，等于**已提交代码从未被验证** |
| grep 实体字段漏 setter | 查某字段有没有被用过，必须同时匹配 `setXxx` / `getXxx`（大小写不同）或直接用 `-i`。曾用小写 `mustChangePassword` grep，漏掉 `setMustChangePassword(0)` 两处，得出错误结论 |

### 6.1 本机 Maven 命令

```powershell
'D:\develop\jdk177\bin\java.exe' `
  -classpath 'D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar' `
  -Dclassworlds.conf='D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf' `
  -Dmaven.home='D:\develop\Maven\apache-maven-3.9.4' `
  -Dmaven.multiModuleProjectDirectory='D:\code\examOnline' `
  org.codehaus.plexus.classworlds.launcher.Launcher -o test
```

单类：末尾追加 `-Dtest=ClassName -DfailIfNoTests=false`。

### 6.2 本机 dev 启动环境（2026-09-21 实测已修：零环境变量可起）

`application-dev.yml` 的默认值**已对齐本机实况**，直接 `mvn spring-boot:run`（dev profile）即可，
不需要再设任何环境变量。历史坑与现状：

| 组件 | 宿主端口 | 凭证 | 说明 |
|---|---|---|---|
| `exam-mysql-master` | **13316** | `root/root123`，库 `exam_online` | 27 张表（含历史垃圾表 `rep_test`，**不要删**） |
| `exam-mysql-slave` | **13317** | `root/root123` | GTID 复制**实测在跑**：IO/SQL 双 Yes、`Last_Errno=0` |
| `exam-rabbitmq` | 5672 / 15672 | `exam/exam123` | 3.13.7 |
| Redis | 6379 | 无 | 宿主 **Windows Redis 3.0.504** 服务；**不要启 `exam-redis` 容器**（抢 6379） |

> 旧文档写的默认值是 `3306` + `root/root`：**3306 被另一个 compose 项目占用**
> （`D:\code\sports` 的 `sport-verify-mysql`，它会被那边的 `compose up` 随时 recreate），
> Windows 的 `MySQL80` 服务也拒绝 root/root。曾经有人据此误判成"另一套实例、凭据不通"，
> 实际是**容器没起**。端口是容器创建时分配的，**以 `docker ps` 实际映射为准**：
> `docker ps --format "{{.Names}} {{.Ports}}"`。

**三件套 restart 策略已改成 `unless-stopped`**（主库/从库/RabbitMQ；`docker-compose.yml` 同步）。
此前全是 `no`：宿主或 Docker Desktop 一重启，整套依赖静默躺平，容器只留 `Exited (255)`——
这就是 `Exited (255)` 的真实含义（VM/宿主停过，不是密码错）。
`exam-redis` 与 `exam-grafana` **故意保持 `no`**：前者抢 6379，后者属未完成的观测项，不该自动起。

主库 `innodb_redo_log_capacity` 已从默认 100MB 提到 **256MB**（`SET PERSIST`，已进
`performance_schema.persisted_variables`，重启仍在；compose 里也加了同名启动参数）。
实测批量写入几十万行时默认值会卡住（日志 MY-014084/MY-014089 连排几分钟）。

`JWT_SECRET` 现在也有 dev-only 默认值（`application-dev.yml`）。此前 dev 启动**必需**它，
而缺失时报的是 `JWT secret 长度必须 >= 32 字节（当前 0）`——看着像代码问题，其实是缺环境变量，
且旧文档只列了 4 个 DB 相关变量。**生产的 fail-fast 未被削弱**：`application.yml` 仍是
`${JWT_SECRET:}`，空值直接启动失败。

容器若 exited：`docker start exam-mysql-master exam-mysql-slave exam-rabbitmq`。
存量库改表要跑 `docker/mysql/migrations/` 下对应脚本——**`schema.sql` 是
`CREATE TABLE IF NOT EXISTS`，存量库不会自动补表**（实测：`audit_log` 因此在 dev 上缺失，
审计写异常被 catch 静默吞掉，只有 ERROR 日志可查；H2 测试每轮重建，永远看不见这类问题）。

**实测启动耗时 11.6s**，`/actuator/health` 返回 `UP`（db=MySQL、rabbit=3.13.7、redis=3.0.504）
即为成功。**验证完必须停掉实例**：`netstat -ano | grep :8080` 取 PID → 核对命令行确是
`ExamOnlineApplication` → `taskkill //PID <pid> //F`（`pkill` 在 Git Bash 下打不到 Windows 进程）。

**两条会白白耗掉一轮的工具链约束（2026-09-19 实测）**：

1. **必须显式用 JDK 21**：该机 `PATH` 里默认的 `java` 是 1.8.0_202，直接 `java -jar target/exam-online.jar`
   会因版本不兼容失败；用 `/d/develop1/jdk21/bin/java`（或设 `JAVA_HOME`）。
2. **Bash 工具的命令串不能含非 ASCII 字符**（中文提交信息、中文路径都会）：整条命令在
   `eval` 包装层被打坏，表现为 exit 127 + `.../Temp/qoder-xxxx-cwd: No such file or directory`，
   极易被误判成"审批未通过 / 目录问题"。中文内容改走 Write/Edit/Grep 工具，或先写进文件再引用
   （如 `git commit -F msg.txt`）。

**启动日志里的已知异常（不要误判为启动失败）**：`ExamSubmitSender.send` → `RabbitTemplate.waitForConfirmsOrDie` 抛 `IllegalStateException: This operation is only available within the scope of an invoke operation`，伴随 `答案补发对账: 待补=2 已补=0`。这是遗留 #10，真 broker 下才暴露、非致命，`/actuator/health` 返回 UP 即视为启动成功。**不要顺手修**。

**契约导出必须走路 B（真 dev 实例），不要走路 A**：`OpenApiContractTest.exportOpenApiContract()`（`-DexportContract=true`）用 `getContentAsString()` 取正文，未设 charset 时按 ISO-8859-1 解码，会写出**中文乱码**的 `openapi.yaml` 且 `servers.url` 退化为 `http://localhost`。详见遗留 #12。正确做法：起 dev 实例后 `Invoke-WebRequest 'http://localhost:8080/v3/api-docs.yaml' -OutFile openapi.yaml`，导出后**必须停掉应用**（曾有残留 dev 实例占 8080 导致导出到旧契约且静默成功）。

---

## 七、指导 Agent 协作纪律

1. 用户提供的诊断和方案是**待验证假设**，先 grep/read/test。
2. 取舍先问用户，不擅自改范围。
3. 一个阶段一个 OpenSpec 变更，避免把多个问题混成一个大提案。
4. 子 agent 提示词必须自包含，写明：写入边界、最多修复尝试次数、回报格式、commit 责任与验证要求。
5. 子 agent 必须自己 commit；指导 Agent 不代交，除非用户明确要求。
6. 每轮交付独立核：`git status --short`、`git diff --stat`、关键 grep、实际测试报告。
7. 新测试坚持红先绿后，不接受放宽断言式的转绿。
8. 回勾 tasks 必须按实际证据，不按回报或印象勾满。
9. 归档五步：spec 合入 → 目录 archive → tasks 回勾 → README 更新 → 一个 docs commit。

---

## 八、接手检查清单

- [x] 读 `docs/需求决策记录.md`；
- [x] 读 `spec/README.md` 与相关 `spec/specs/`；
- [x] 核对 `git rev-parse HEAD`、`git status --short`；
- [x] 阶段 17 提案四件套确认（已随归档移入 `archive/`）；
- [x] 提示词 B 已发给子 agent，子 agent 自己 commit（`83bc9ca`）；
- [x] 指导 Agent 独立核 diff + 重跑全量（210 全绿）；
- [x] 归档五步完成（spec 合入 → 目录 archive → tasks 回勾 → README 更新 → docs commit）；
- [x] `%SystemDrive%/` 垃圾目录已清理（从未进入 commit）；
- [ ] 下一位接手时：核对 `git rev-parse HEAD` 与本归档 docs commit 一致；
- [ ] 不要把 DLQ 真 broker、5000 并发压测、补考成绩接线、MySQL 空库真机初始化误报成已完成。

---

## 九、下一步路线

### 立即下一步

**实施阶段 19 `add-frontend-skeleton-auth`**（前端方向①：工程骨架 + 认证）。提示词已落盘：`spec/changes/add-frontend-skeleton-auth/agent-prompt.md`。阶段 18 已交付 `openapi.yaml`（65 paths），前置条件满足。

前端系列（19→20→21→22→23）**必须串行**，每个阶段验收并归档后才发下一个。各阶段提示词均已写好，位于各自变更目录。

### 阶段 19 之后的候选（不要与前端系列混做）

1. **`ExamSubmitSender` 真 broker 启动异常修复**（遗留 #10）——`waitForConfirmsOrDie` 须在 `invoke()` 作用域内调用；启动对账当前实际未补发成功；
2. **MySQL 8 空库真机初始化验证**（遗留 #9）——成本极低，证据价值高；
3. **JMeter 5000 并发交卷真实数据**（遗留 #1）——P99 / 丢单 / 批量落库硬指标；
4. **HTTP → MQ → 落库 requestId 日志串联证据**——观测链路可回答性深化；
5. **DLQ 真 broker 往返**（遗留 #6）——本机 Docker 环境已确认可用（见 §6.2）；
6. **补考成绩接线**（遗留 #5）——`MakeupScoreService.finalScore` 全仓库零调用，需新增接口，属功能变更。

1 与 6 是缺陷/缺口修复；2/3/4 是「我能证明它有」路线。优先级由用户定。

---

## 十、当前事实与未知信息分离

### 已核实事实

- 阶段 17 实施 commit 为 `83bc9ca`（仅 3 文件，161 行纯新增），归档 docs commit 紧随其后；
- schema.sql 25 个自增表全部有 `PRIMARY KEY (id)`，与 `AUTO_INCREMENT` 计数一一对齐；
- 全量测试 210 全绿（指导 Agent 独立重跑，非转述）；
- 阶段 17 已归档：spec 已合入 data-access、目录已入 archive、tasks 已按证据回勾、README 已更新；
- 阶段 16 已有 5 条 firing、4 条未点着的运行证据；
- `exam_dlq_messages` 没有 `exam_id`，阶段 15 因此不纳入清理；
- `%SystemDrive%/` 垃圾目录已清理，从未进入 commit。

### 当前未知 / 不应声称

- 阶段 17 修复后的 MySQL 空库真机是否真的成功初始化——**仍未实测**（遗留 #9）；
- W16 存量迁移脚本在真实存量库的行为——无真机执行记录；
- DLQ 真 broker 往返重投是否成功——仍未验证（遗留 #6）；
- 5000 并发交卷的 P99、丢单、批量落库时延——仍未实测（遗留 #1）。

---

## 十一、前端（`frontend/`，阶段 19 起）

技术栈照抄参考项目 `D:\code\crm\font\crm-front`：Vue 3.5 + TS + Vite 7 + ant-design-vue 4.2 + tailwindcss 4 + unplugin-vue-router 文件路由 + `@tanstack/vue-query` 5 + axios/`@hey-api/client-axios` + vuex 4 + vitest + Playwright，包管理器 **pnpm**。

### 启动

```powershell
# 1) 先起后端依赖（仓库根的 docker-compose.yml：mysql / redis / rabbitmq）
docker compose up -d
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"

# 2) 另开终端起前端
cd frontend
pnpm install
pnpm dev            # http://localhost:5173，hash 路由
```

管理员账号取 `application.yml` 的 `exam.auth.admin`（默认 `admin` / `admin123`，仅 dev 库）。

### 三条必须记住的约定

1. **代理不得 rewrite `/api`**。后端 14 个 Controller 的路径本身就带 `/api` 前缀，而参考项目的网关会剥掉它、所以 crm-front 写了 `rewrite: path.replace(/^\/api/, '')`。这里照抄会把所有接口变成 404。`vite.config.ts` 里已就地写了警示注释，并由 `e2e/auth-smoke.spec.ts` 断言请求 URL 仍含 `/api`。
2. **API 客户端只能由 `pnpm gen:api` 生成**（契约 = 根目录 `openapi.yaml`，或 `VITE_CONTRACT_URL` 指向运行中的 `/v3/api-docs`）。手写只允许 `src/api/` 下的薄封装；`src/api/axios/**` 已在 eslint / prettier 里整体豁免，不要人工改、也不要为通过 formatter 去动它。
3. **前端守卫不是安全边界**，只是体验层。`src/router/access.ts#decideNavigation` 是**唯一**导航裁决入口——日后接入 `must_change_password` 强制改密前置（另行立项 `add-auth-must-change-password`）时只改这一个函数。登录态一律以后端 `/api/auth/me` 的结果为准，绝不用「本地有 token」当代替。

### Refresh 单飞（硬约束，勿退化）

`src/api/sessionRefresh.ts` 把并发 401 收敛成一次刷新，其余请求排队等同一个 promise 再重放。后端是 **Refresh Rotation + 复用检测**：两个请求各刷各的会被判定为 token 重放、导致全端下线。该行为由 `src/api/__tests__/apiClient.spec.ts`（并发两个 401 只刷一次）与 `sessionRefresh.spec.ts`（inflight 释放后允许第二轮、且用新 refresh）锁定，改动前务必先看这两组用例。

改密 / 重置密码成功后后端会让全部会话失效，因此**前端这两个页面不得再调 logout**，直接清本地 token 跳登录页。

### 质量门禁

```powershell
cd frontend
pnpm type-check:check   # vue-tsc(app) + tsc(config)，两个工程都要 0
pnpm lint:check
pnpm test               # vitest 单测，不依赖后端
pnpm test:e2e           # 需要后端在跑 + pnpm exec playwright install
```

前端测试基线与后端 `mvn test` **相互独立**：未往 `pom.xml` 挂任何前端插件，CI 分工是后端归 maven、前端归 pnpm。

### 已知缺口（不要误报成已完成）

- `pnpm test:e2e` **未实跑**：本机 Playwright 要求的 chromium build 与已安装版本不一致（缺 `chromium_headless_shell-1243`）。用例已能通过收集与转译，卡在浏览器启动。首次执行需 `pnpm exec playwright install chromium`（会下载浏览器，属需授权动作）。
- e2e 冒烟只覆盖「登录 → 首页 → 越权重定向 → 登出」，注册 / 找回密码有验证码与邮件依赖，未编入用例。
- 阶段 19 只有认证四页 + 首页骨架；`/admin`、`/teacher`、`/student` 三个分区菜单项是 `disabled` 占位，属阶段 20+。
