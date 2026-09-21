# AGENTS.md —— agent 必读索引（只有规则与指针，没有事实）

> 本文件会被注入每一次 agent 会话，所以它**只承载两类东西**：
> ① 足以让改动在任何环境必然失败、或造成禁止性后果的硬约定；② 指向 canonical 真源的指针。
> 每条固定三段：**一句话规则 + canonical 出处 + 可机械执行的自查命令**。正文一律不复述（复述＝制造第四个副本）。
> 这里不写端口、口令、用例数、表清单——本仓库的失败记录正是根入口带着过期数值造成的（`README.md` 顶部说明本规则）。

## 四条硬约定

### 1. 未接 Flyway；建表唯一入口是 `src/main/resources/schema.sql`

- **规则**：新增/修改表、列、索引一律改 `src/main/resources/schema.sql`（dev 与测试共用，H2/MySQL 双兼容；`AUTO_INCREMENT` 块必须同时写 `PRIMARY KEY (id)`）。不要引入 Flyway / Liquibase，也不要恢复 `src/main/resources/db/migration/V*.sql` 形态的文件——那种形态在本仓库**从不执行**，留着只会制造"表已存在"的错觉。
- **出处**：`docs/需求决策记录.md` 第十八节「建表与迁移的唯一事实源」（`:384-385`）。
- **自查**：`grep -n flyway pom.xml` → 命中须为 0；`test ! -e src/main/resources/db && echo OK` → 须输出 `OK`。

### 2. 集成测试不得用 `@Sql` 自建表

- **规则**：测试库建表只有 `schema.sql` 一个来源。`@Sql` 自建表会把"新库 / 新环境建不起来"这件事藏进 CI 的绿勾里——本仓库发生过 `schema.sql` 缺 `classes` / `user_class` 两表而测试全绿。
- **出处**：`spec/README.md`「工作流」第 5 条（行号会漂，按条目编号定位；事故与收口记录见同文件「已收口」列表第 4 条）。
- **自查**：`grep -rn '@Sql' src/test | sed -E 's/^[^:]+:[0-9]+://' | grep -vE '^[[:space:]]*\*'` → 输出须为空。
  当前唯一命中是 `src/test/java/com/exam/monitoring/retention/DataRetentionIntegrationTest.java` **类注释**里复述本规矩的那一行（javadoc 续行以 `*` 开头，第二段 `grep -v` 就是用来排除它的）。**任何注解形态的命中都判违规**，判据不认"我先建个临时表"。

### 3. 实体字段与建表定义必须双向一致

- **规则**：MyBatis-Plus 按实体字段生成 INSERT——**实体有、表里没有的列，会让该写入在任何环境都失败**（`score_review` 缺 `created_time` 曾使复核申请接口从未成功执行过一次）。给实体加字段必须同批改 `schema.sql`；`schema.sql` 新增表也必须有对应实体或写明为什么没有。
- **出处**：`spec/README.md`「工作流」第 6 条（行号会漂，按条目编号定位）。
- **自查（表级，只读）**：
  `comm -23 <(grep -rho '@TableName("[a-z_]*")' src/main/java | sed -E 's/@TableName\("(.*)"\)/\1/' | sort -u) <(grep -oiE 'CREATE TABLE IF NOT EXISTS [a-z_]+' src/main/resources/schema.sql | awk '{print tolower($NF)}' | sort -u)`
  → 输出须为空；任何命中即"实体指向了 `schema.sql` 里不存在的表"。**列级不在这里查**：列级靠走真实链路的集成用例暴露（写不进去就会红）——这也正是约定 2 存在的原因。

### 4. 存量库改表走 `docker/mysql/migrations/`，而该目录没有自动执行者

- **规则**：`schema.sql` 是 `CREATE TABLE IF NOT EXISTS`，**存量库不会因此补表**；把脚本放进 `docker/mysql/migrations/` **不等于变更已生效**，必须由人按顺序应用到存量库并验证（缺表的写库路径可能被 `catch` 静默吞掉，测试还看不见）。写脚本前先查 `schema.sql` 是否已有等价对象。
- **出处**：`docs/需求决策记录.md:384-385` + 该目录自述 `docker/mysql/migrations/README.md`。
- **自查**：`grep -n 'docker/mysql' docker-compose.yml` → 命中只有 `master/init` 与 `slave/init` 两处，本目录不在其中，因此**不存在**自动执行者。任何声称"脚本已提交＝迁移已完成"的回报按违规处理。

## 禁忌（只给指针，正文以被指向处为准）

- 「不要为了架构优化而重写已经能讲清楚的单体」——明确不建议的架构动作清单：`docs/指导Agent交接文档.md:26-34`。
- dev 库里那张历史遗留表 `rep_test` **不要删**：`docs/指导Agent交接文档.md:193`。
- 启动期"答案补发对账"在真 broker 下抛的那个异常 **不要顺手修**（非致命，属遗留项）：`docs/指导Agent交接文档.md:236`，并见 `spec/README.md` 遗留事项 #10。
- 子 agent 的回报不是事实：每轮交付必须独立看 `git status`、`git diff`、关键代码、测试报告：`docs/指导Agent交接文档.md:10`。

## 唯一门禁命令

**待 `update-agent-gate-single-source`（E2）落地后回填。**

本文件此刻故意不写出任何命令行：现有文档对"哪一条命令算门禁"存在互斥说法，抄任何一种进来都会让它变成又一个副本——而且是最新、被读到最多的那个。
E2 收口前若确实需要跑构建：读 `docs/指导Agent交接文档.md` 的工具链小节自行拼命令，并在回报里给出**实际执行的命令与原始输出**（不得引用文档里的数字当结果）。

## 本文件与 `README.md` 不承载易变事实

禁止出现：端口号、口令、用例计数、覆盖比例数值、表清单，以及"共 N 个"这类计数（能力域数、归档变更数、迁移脚本数都不例外）。
需要事实 → 去读产生它的文件；需要数值出现在回报里 → 现场跑命令，并把产生它的 revision 与数值同排记录；否则把常量改写成判据。
canonical 需求：`spec/specs/agent-harness/spec.md` 的「文档判据优先于文档常量」（由已归档的 `add-agent-context-routing` 合入）。

自查（三条，跑一遍就该全绿）：

```bash
# 1) 本文件不得含端口 / 口令 / 用例数 / 百分比
grep -nE '1331[67]|330[67]|root12[3]|[T]ests run|[0-9]+%' AGENTS.md
# 2) 根入口不得含连接事实
grep -nE '330[67]|1331[67]|root12[3]|root/[r]oot' README.md
# 3) 测试侧不得出现 @Sql 注解（注释复述本规矩不算）
grep -rn '@Sql' src/test | sed -E 's/^[^:]+:[0-9]+://' | grep -vE '^[[:space:]]*\*'
```

判据 1、2 里的字符类方括号（`330[6]`、`[T]ests`、`root/[r]oot`）是**故意的**：写成裸字面量会让这条命令永远命中它自己所在的文件，于是永远变红、等于没有判据。
怀疑某条自查是装饰性的，就按 `docs/指导Agent交接文档.md` 的变异验证纪律走一遍：注入违规 → 确认变红 → 撤销 → 确认变绿。
