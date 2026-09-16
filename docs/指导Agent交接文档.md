# examOnline 项目交接文档（指导 Agent 视角）

> **交接对象**：下一位项目指导 Agent
> **文档时点**：2026-09-17
> **当前分支**：`feature/add-performance-deepening-readwrite`
> **当前性质**：阶段 1–17 已归档；无进行中变更。
> **实施 HEAD**：`83bc9ca73ba254cc1f5a6922b3912a3ab71a6399`（阶段 17 代码 commit）；本归档 docs commit 紧随其后。
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

**无进行中变更。** 从下列候选中选题立项，一个阶段一个 OpenSpec 变更，不混做：

1. **MySQL 8 空库真机初始化验证**（遗留 #9 的直接收口）——在真实空 MySQL 8 实例执行 `schema.sql`，记录建全 25 张表的真机证据；顺手在存量库跑一次 W16 迁移记录 1068 预期行为。成本极低，证据价值高。
2. **JMeter 5000 并发交卷真实数据**（遗留 #1）——P99 / 丢单 / 批量落库硬指标；
3. **HTTP → MQ → 落库 requestId 日志串联证据**——观测链路可回答性深化；
4. **DLQ 真 broker 往返**（遗留 #6）——需要本机 Docker 与真实 RabbitMQ；
5. **补考成绩接线**（遗留 #5）——`MakeupScoreService.finalScore` 全仓库零调用，需新增接口，属功能变更。

1 与 2/3 是「我能证明它有」路线；4 依赖本机 Docker 环境；5 是功能补全，优先级由用户定。

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
