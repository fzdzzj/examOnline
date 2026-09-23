# examOnline 项目交接文档（指导 Agent 视角）

> **交接对象**：下一位项目指导 Agent
> **文档时点**：2026-09-23（本轮覆盖阶段 19–23 前端五方向收口、G1–G5 执行、前端统一验收）
> **当前分支**：`feature/add-performance-deepening-readwrite`
> **当前性质**：阶段 1–23 全部归档；后续提案 3 个在途（`add-makeup-final-score-frontend` / `audit-log-off-critical-path` / `update-agent-gate-single-source`），2 个待立项（D1/D2，见遗留 #17/#18）。
> **代码 HEAD**：`77de655f26fa9d7c957646915391190290c1d636`（accept-frontend-19-23 收口）；近期链：`3b8bcfa`（G3 观测）→ `df1871d`+`259d4f4`（G1/G2/G4/G5 容量与复验）→ `5b56d03`/`809aa04`（阶段 19/20 回勾与走查合入）→ `77de655`（统一验收收口）。
> **后端验收判据（不是常量）**：某次后端全量门禁算不算绿，只看该次执行是否满足
> `Failures=0` 且 `Errors=0`、`Skipped` 保持 1（Skipped 为受 `exportContract` 开关控制的契约导出方法，属设计使然，不要消除），
> 且**用例总数不得少于本阶段开工时实测并记录在案的数值**——记录方式为「命令 + 当次原始输出 + 当时短 revision」写进该阶段的 `tasks.json` 证据字段。
> **本文档与任何提示词都不再承载"基线 = N 个用例"这类常量**：历史上写在这里的 `213` 只是**描述本文档定稿时点（HEAD `47d42f8`，见上一行）那一次执行**的历史记录，不是当前基线，也不得被用来判定当前交付；照它去"凑数"（删/禁用例）是本仓库最防的行为。
> 仓库自有的唯一门禁命令待 `update-agent-gate-single-source`（E2）B-2 回填。
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

- 分支：`feature/add-performance-deepening-readwrite`（HEAD `77de655`，2026-09-23 收口后工作树干净，仅 `?? .trae/` 本地目录）。
- 2026-09-23 提交链（按时间）：`ef02fc1`（五份后续提案立项）→ `3b8bcfa`/`e34e12e`/`981f254`/`b1fa8a2`（G3 观测补齐与收口）→ `df1871d`/`259d4f4`/`acf7427`/`59bab7b`（G1/G2/G4/G5 容量调整与压测复验收口）→ `6a90195`/`5b56d03`/`809aa04`/`77de655`（accept-frontend-19-23：走查记录、阶段 19/20 回勾、统一验收收口）。
- 早期链：`f5b4b99`（阶段 21 收口）、`fce387d`（阶段 23 复核收口）、`813da6d`（阶段 22 收口）。

### 2.2 OpenSpec 状态

- `spec/changes/` 进行中 **3** 个：`add-makeup-final-score-frontend`（提案⑨前端收口，无前置）、`audit-log-off-critical-path`（G6，**门控提案**：先评估后实施）、`update-agent-gate-single-source`（E2，需联网与磁盘授权）。行数注记与 `find spec/changes -mindepth 1 -maxdepth 1 -type d -not -name archive` 现场计数互为判据。
- 归档 **47** 个（2026-09-23 现场数），能力域 **19** 个。
- `spec/specs/frontend/spec.md` 现含 **21** 个 Requirement——五阶段全量，已知缺陷（D1/D2）在合入注记与遗留 #17/#18 如实登记。
- 开放遗留速览（详单以 `spec/README.md` 遗留节为准）：#1 P99 保持开放（复验最好 2293ms，瓶颈已跳出应用）；#6 DLQ 往返未证；#14/#15 偶发测试留观；#16 压测复位 gbk 假绿坑；#17/#18 D1/D2 待立项。

### 2.3 本轮新增的动态证据（2026-09-23）

- **G3 观测**：`tomcat.threads.busy/config.max` 经 MBean registry 暴露、`exam_submit_duration_seconds` 铺 SLO 桶（0.1–10s）——surefire 输出含 `[evidence]` 行、变异验证红绿在案（`3b8bcfa`）。
- **G1/G2/G4/G5 压测复验**：`docs/submit-loadtest-report.md` + `loadtest/` 脚本化方法学（臂级 ≥3 轮中位数、预热轮、TIME_WAIT 排空轮询、Prometheus 净增量口径）。三臂复验结论：0 丢单三臂达标；P99 三臂均不达标（最好 2293ms）；批量落库仅默认臂达标。**改指标凑数是违规，不达标如实登记也是合格收口**。
- **前端统一真机走查**：`frontend/docs/frontend-stages-walkthrough.md`（`59bab7b`，Playwright 真实 Chromium + 网络录像），五阶段全页面族实走，D1/D2 两个逻辑级缺陷如实登记未修未绕。

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
| 17 | `fix-schema-mysql-pk` | 已归档 | 25 张表补 `PRIMARY KEY (id)`；W16 存量迁移脚本；文本约定测试；MySQL 空库真机初始化已由 `verify-mysql8-init` 补证（遗留 #9 收口） |
| 18 | `add-backend-openapi` | 已归档 | springdoc 2.8.13；`openapi.yaml` 65 paths / 3.1.0 / 覆盖 14 Controller；5 个公开端点标 `security: []`；契约冒烟测试；**返修 1 轮**（pom 格式被压成 3 行、测试写仓库文件 + 方法顺序依赖、免鉴权未标注、未 commit） |
| 19–23 | 前端五方向 | 已归档 | 骨架+认证 / 题库组卷 / 考试管理 / 学生端考试 / 考后闭环；经 `accept-frontend-19-23` 统一验收（走查记录 `frontend/docs/frontend-stages-walkthrough.md`）；frontend 基线 21 个 Requirement 五阶段全量；阶段 20 有 D1 两格禁盲勾未勾（遗留 #17） |
| — | `add-submit-loadtest` | 已归档 | 5000 并发压测报告（`docs/submit-loadtest-report.md`）：P99 不达标归因线程池饱和 + 近饱和排队放大；JIT 预热自纠 |
| — | `add-submit-observability` | 已归档 | G3：Tomcat 线程水位暴露 + 交卷时延 SLO 桶；变异验证红绿在案（`3b8bcfa`） |
| — | `tune-submit-capacity` | 已归档 | G1/G2/G4/G5：容量裁决（B+：线程 400 + 池 100）、relaxed binding 注入、方法学脚本化、Hikari 采样；三臂复验 P99 仍不达标如实登记（遗留 #1 保持开放） |
| — | `verify-mysql8-init` | 已归档 | MySQL 8 空库真机初始化 + 存量迁移真跑（遗留 #9 收口） |
| — | `fix-broker-confirm-and-dlq-roundtrip` | 已归档 | 提案⑦：broker 确认修复 + DLQ 往返（遗留 #10/#6 相关收口） |
| — | `fix-contract-export-charset` | 已归档 | 提案⑧：契约导出 UTF-8 无损 + servers.url 不降级（遗留 #12 收口） |

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
| Maven PATH 版本错误（**该机该 shell 的历史观察，非普适结论**） | 当时的绕行办法见 §6.1（含仓库外 JDK/Maven 绝对路径，可移植性为零，**不是推荐命令**）；无论怎么拼都**必须带 `clean`**，否则残留报告会抬高用例计数；仓库自有的唯一门禁命令待 `update-agent-gate-single-source`（E2）B-2 回填，本行届时改为指向它 |
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
| antd4 Alert 默认插槽静默丢弃 | ant-design-vue 4 的 `Alert` 只渲染 `slots.message` / `slots.description`，默认插槽正文**零渲染、零报错**——弹窗正文必须走具名插槽（本坑已修 5 处：exams 发布/force-end、teacher/scores 发布确认、student/scores 复核申请 + 阶段 22 两处）。写用例断言 `document.body.textContent` 而非组件树 |
| 模板组件忘 import 只告警不失败 | `<Table>` 用了但只 `import type { TableColumnsType }` → `[Vue warn]: Failed to resolve component`，整块零渲染而门禁全绿（D1，遗留 #17）。页面级用例应断言「无组件解析警告」 |
| PowerShell 不支持 `&&` / `\|\|` | 本机 PowerShell 版本多命令只能用 `;` 串接 + `$LASTEXITCODE` 判退码；vitest 路径含 `(dashboard)` 括号必须加双引号 |
| 无头浏览器无真实窗口焦点 | visibilityState 恒 visible，切屏/失焦类用例只能合成 DOM 信号并在记录中如实标注——不要声称覆盖了真实用户切换 |
| mysql 客户端 gbk 静默假绿 | 复位/对账脚本必须 `--default-character-set=utf8mb4`，且复位结果以独立复核命令为准（遗留 #16 详单） |
| 子 agent 被中断留下半成品 | 接手的工作树先 `git status --short` + 通读未提交改动，判定质量后决定续用或按写入边界重做；禁 `stash`/`reset`/`checkout -- .`/`clean` 一键抹掉 |

### 6.1 本机历史坑与当时的绕行办法（**不是推荐的验证命令**）

> **定性**：本节是**特定 shell + 特定本机环境**下的历史绕行配方，不是仓库自有的门禁命令，也不是"正确的跑法"。仓库唯一的门禁命令待 `update-agent-gate-single-source`（E2）B-2 回填；E2 收口前若确需跑构建，可按本节自行拼装，并在回报里给出**实际执行的命令与原始输出**（不得引用本文档里的任何数字当结果）——这与 `AGENTS.md`「唯一门禁命令」一节的指引同口径。
> **成因（当时的观察，非推断）**：该机 PowerShell 的 PATH 上 `java` 是 1.8 而 `javac` 是 21，Maven 因此拿不到可用解释器，才有下面这套「用绝对路径直调 Plexus classworlds 启动器」的配方。
> **两个不许越界的结论**：① 不要因为换一个 shell（PATH / `JAVA_HOME` 已指向 JDK 21）同样跑得通，就判本节"写错了"或删掉它——它记的是当时那个环境；② 也不要反过来在文档里新写一句"以后就用 PATH 里的 mvn"，那会变成第五种门禁命令说法。唯一命令只由 E2 B-2 决定。
> 命令里的 JDK / Maven 目录与 `multiModuleProjectDirectory` 全是**仓库外的机器路径**，正是 E2 要消除的机器绑定，因此本节的可移植性为零。

```powershell
'D:\develop\jdk177\bin\java.exe' `
  -classpath 'D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar' `
  -Dclassworlds.conf='D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf' `
  -Dmaven.home='D:\develop\Maven\apache-maven-3.9.4' `
  -Dmaven.multiModuleProjectDirectory='D:\code\examOnline' `
  org.codehaus.plexus.classworlds.launcher.Launcher -o test
```

单类：末尾追加 `-Dtest=ClassName -DfailIfNoTests=false`。

**任何取数方式都必须带 `clean`**：`target/surefire-reports` 里混有上一轮残留报告时，汇总出的用例总数会明显虚高，那种数不得进入任何验收记录（判据见首屏）。

### 6.2 本机 dev 启动环境（2026-09-21 实测已修：零环境变量可起）

> **与 §6.1 的关系（注记，勿自行裁决）**：本节"直接 `mvn spring-boot:run` 即可"与 §6.1"PATH 里的 mvn 在该机不可用"**互斥**，二者分属不同 shell / 不同时间点的本机观察，本节不因此成为门禁命令、§6.1 也不因此被证伪。**仓库唯一的门禁命令待 `update-agent-gate-single-source`（E2）B-2 回填**，回填后本节的命令行一并改为指向那条命令的引用，不再就地保留任何一种写法。

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

**契约导出推荐走路 A（离线，`-DexportContract=true`）**：`OpenApiContractTest.exportOpenApiContract()` 自 `fix-contract-export-charset` 起改用 `getContentAsByteArray()` 按字节写文件并固定端口 8080，中文无损、`servers.url` 保持 `http://localhost:8080` 完整形态（修复前路 A 用 `getContentAsString()` 取正文、未设 charset 按 ISO-8859-1 解码，会写出**中文乱码**的 `openapi.yaml` 且 `servers.url` 退化为 `http://localhost`——那是损坏操作，不是推荐路径；详见遗留 #12 收口记录）。路 B（真 dev 实例 `Invoke-WebRequest 'http://localhost:8080/v3/api-docs.yaml'`）仍可作真环境校验手段：与路 A 产物比对一致后再入库；用路 B 导出后**必须停掉应用**（曾有残留 dev 实例占 8080 导致导出到旧契约且静默成功）。

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

- [ ] 核对 `git rev-parse HEAD` = `77de655`（或读 `.git/logs/HEAD` 末行）与 `git status --short`（预期仅 `?? .trae/`）；
- [ ] `find spec/changes -mindepth 1 -maxdepth 1 -type d -not -name archive` 计数 = 进行中表行数（当前 3）；
- [ ] 需要门禁结论时现场跑（判据见首屏），不引用本文档任何历史数字；
- [ ] 不要把以下事项误报成已完成：P99 达标（三臂复验仍不达标）、DLQ 往返（若 #6 仍未证）、补考最终成绩**前端**展示（后端已接线、前端在途）、D1/D2 修复（待立项）；
- [ ] 派工前读对应变更目录 `agent-prompt.md`，并现场核验其中的「现状」段（工作树路径、事实基线会过期）。

---

## 九、下一步路线

### 在途（3 个，提示词均已落盘）

1. **`add-makeup-final-score-frontend`**（提案⑨前端收口，P3，无前置、改动面小）——后端 `ScoreController` 两条 makeup-final 端点与 `openapi.yaml` 均在，前端生成层零命中、makeups 页还挂着「后端尚未接线」诚实边界。注意 Alert 具名插槽坑。
2. **`audit-log-off-critical-path`**（G6，**门控提案**）——任务 1 评估可先行；任务 2 实施仅在裁决=移出关键路径时执行；结论=不移出则如实关闭也是合格收口。
3. **`update-agent-gate-single-source`**（E2）——需要联网与磁盘授权，收口后 `AGENTS.md`「唯一门禁命令」回填。

### 待用户裁决立项

- **D1**（遗留 #17）：`teacher/papers/[id].page.vue` 补 `import { Table }` + 「无组件解析警告」守卫用例——一行修复，优先级建议高（试卷详情核心功能交付即坏）；
- **D2**（遗留 #18）：批改工作台加「运行判分」入口（`grading/run` 已在 SDK 无调用）——涉及产品决策（入口放哪、汇总是否对未判分数据给出提示）。

### 遗留观察项（不主动开工）

- #1 P99：瓶颈已跳出应用（MySQL 内部排队 + 压测机同机抢核），继续需分离压测机/DB 排队治理（另立项）；
- #14/#15 偶发测试失败留观（ExamTakingIntegrationTest 注册链路 400、DataRetentionIntegrationTest 共享 H2 干扰）——再复现值得单独立项查用例隔离；
- #16 压测复位纪律照办。

---

## 十、当前事实与未知信息分离

### 已核实事实

- 阶段 1–23 全部归档；frontend 基线 21 个 Requirement（五阶段全量）；归档 47 个变更、19 个能力域（2026-09-23 现场数）；
- G3 已落地：线程水位指标 + 交卷时延 SLO 桶，surefire `[evidence]` 行与变异验证红绿在案（`3b8bcfa`）；
- G1/G2/G4/G5 已落地：三臂（default/t400/t400-p100）×3 轮复验、0 丢单三臂达标、方法学脚本化（`loadtest/run-arm.sh` 等）；
- 后端 `makeup-final` 两条端点已存在并被 openapi.yaml 覆盖（`ScoreController.java:119/:137`）；
- 走查记录 `frontend/docs/frontend-stages-walkthrough.md` 五阶段实走、缺陷零静默；
- 本机 PowerShell 门禁实测可跑：`pnpm lint:check` / `type-check:check` exit=0、vitest 38 文件 331 例（`59bab7b`，2026-09-23 指导 agent 亲自执行——仅描述该次执行，不是常量基线）。

### 当前未知 / 不应声称

- 5000 并发 P99 < 2s —— **三臂复验均不达标**（最好 2293ms，超 14.6%），遗留 #1 保持开放；
- 真实用户窗口切换的切屏上报（走查受无头环境限制，只有合成 DOM 信号证据）；
- D1 修复后试卷详情功能是否完整（未修未测）；
- D2 相关的「跳过判分直接汇总按 0」分支真机行为（走查为保护共享数据未实走，仅代码定性）；
- E2 收口前不存在仓库自有的唯一门禁命令。

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
3. **前端守卫不是安全边界**，只是体验层。`src/router/access.ts#decideNavigation` 是**唯一**导航裁决入口——`must_change_password` 强制改密前置已由 `add-frontend-must-change-guard` 接入（该函数为唯一落点，有 `access.spec.ts` 覆盖未认证/正常/mustChangePassword 三场景）。登录态一律以后端 `/api/auth/me` 的结果为准，绝不用「本地有 token」当代替。

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

前端测试基线与后端全量门禁**相互独立、口径互不并入**（未往 `pom.xml` 挂任何前端插件，CI 分工是后端归 Maven、前端归 pnpm）：两边各自记自己的数，任何一边都不许拿另一边的数字当判据；后端那侧的**唯一门禁命令待 `update-agent-gate-single-source`（E2）B-2 回填**，本文件此处不预先写死任何一种 Maven 命令行形态。

### 已知缺口（不要误报成已完成）

- `pnpm test:e2e` 套件本身**未实跑**（本机 chromium 版本曾不匹配）；但 Playwright 真实 Chromium 已在统一走查中大量使用（`frontend/docs/frontend-stages-walkthrough.md`），浏览器可用性已证。
- e2e 冒烟只覆盖「登录 → 首页 → 越权重定向 → 登出」，注册 / 找回密码有验证码与邮件依赖，未编入用例。
- **D1**（遗留 #17）：`teacher/papers/[id].page.vue` 试卷题目表漏 import Table，手动组卷改分/调序与试卷预览题目内容不可用——**交付即坏、待立项修复**，走查 `20-3b`/`20-5` 有实证。
- **D2**（遗留 #18）：批改工作台无「运行判分」入口，判分前 `totalStudents=0`，教师纯靠 UI 进不了批改队列。
- 走查残留（无删除端点，已在走查记录如实登记）：考试 14 及其快照/答卷/行为日志/主观分/复核记录、试卷 14、走查账号 6 个——**下次走查/联调前先看走查记录的残留清单，别把残留数据当产品缺陷**。
- 无头环境无真实窗口焦点：切屏相关结论只有合成 DOM 信号证据（走查记录有标注）。
