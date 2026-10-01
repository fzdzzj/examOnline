## ADDED Requirements

### Requirement: 根入口只路由不承载易变事实

WHEN agent 或新成员从仓库根开始了解项目,
系统 SHALL 通过 `README.md` 获得指向 canonical 真源的入口指针，且 `README.md` 不承载端口、口令、工程结构、里程碑等会随提交过期的事实副本。

#### Scenario: 查询 dev 数据库连接默认值

GIVEN 一个只读过 `README.md` 的 agent 需要知道 dev 环境连哪个 MySQL 地址与口令
WHEN 它按 `README.md` 的入口指针跳转
THEN 它被指向 `src/main/resources/application-dev.yml`
AND `README.md` 内不含该端口、口令或连接串的字面值
AND 它不需要在同一文件里判断"哪个说法是新的"

#### Scenario: 环境默认值变更后根入口无需跟改

GIVEN `application-dev.yml` 的默认端口或口令发生变更并已提交
WHEN 该变更完成
THEN `README.md` 无需同步修改即保持正确
AND 该次变更的 diff 不含 `README.md`（`git show --stat` 中不出现该文件）

#### Scenario: 有人把事实抄回根入口

GIVEN 贡献者向 `README.md` 添加了具体端口号或口令默认值
WHEN 执行本能力域约定的自查 `grep -nE '3306|13316|13317|root123|root/root' README.md`
THEN 命中数大于 0 即判定违反本需求
AND 评审要求把该值改回"以某文件为准"的指针形式

### Requirement: 硬约定必须有 agent 必经的索引入口

WHEN 一条约定足以让改动在任何环境必然失败或造成禁止性后果,
系统 SHALL 在仓库根 `AGENTS.md` 提供该约定的一句话表述、其 canonical 出处指针、以及可机械执行的自查命令。

#### Scenario: 变更涉及建表或改表

GIVEN 一个 agent 准备新增一列或一张表
WHEN 它读取仓库根 `AGENTS.md`
THEN 它读到"本项目未接 Flyway，建表唯一入口是 `src/main/resources/schema.sql`"及其出处（`docs/需求决策记录.md` 中标题含「未接 Flyway」的那条决策）
AND 它读到存量库手工脚本的位置及其不自动执行的限制

#### Scenario: 集成测试需要建表数据

GIVEN agent 想让集成测试在自有表上运行
WHEN 它按 `AGENTS.md` 的自查命令执行 `grep -rn '@Sql' src/test`
THEN 任何 `@Sql` **注解**命中都判定为违反硬约定
AND 复述本规矩的注释行不计为违规（`src/test/java/com/exam/monitoring/retention/DataRetentionIntegrationTest.java` 类注释中即有一处，属合规；自查须能区分注释与注解）

#### Scenario: 索引本身不得复制易变事实

GIVEN `AGENTS.md` 需要提及一条会漂移的事实
WHEN 撰写该条目
THEN 条目只写规则与指向 canonical 文件的指针
AND 不复制端口、口令、测试用例数、覆盖率、表清单等数值
AND 自查 `grep -nE '13316|13317|root123|Tests run' AGENTS.md` 命中为 0

### Requirement: 无自动执行者的 SQL 目录必须自述触发方式

WHERE 仓库某目录内的 SQL 脚本不会被任何启动或测试路径自动执行,
该目录 SHALL 提供一份说明，写明其执行者（手工）、触发时机、不执行时的后果，以及一个仓库内已发生过的真实先例。

#### Scenario: agent 在 migrations 目录前决策

GIVEN `docker/mysql/migrations/` 下有一批 `2026-WNN-add-*.sql`，而 `docker-compose.yml` 只挂载 `master/init` 与 `slave/init`
WHEN 一个 agent 考虑"在此目录添加脚本来完成 schema 变更"
THEN 目录第一份说明让它立即得知：放进该目录不等于变更已生效，dev 与存量库不会因此自动补表
AND 说明指向 `audit_log` 在 dev 库缺失、异常被 catch 静默吞掉的先例
AND 说明要求"先查 `schema.sql` 是否已有等价对象，再决定是否写脚本"

#### Scenario: 存量库确需手工脚本

GIVEN 一个已存在的库需要一次 `CREATE TABLE IF NOT EXISTS` 无法覆盖的变更
WHEN 按目录说明手工应用脚本
THEN 说明给出应用顺序、执行后的验证方式，以及"新库无需执行本脚本"的边界
AND 目录内既有脚本文件未被本次说明的撰写所修改

#### Scenario: 测试环境看不见该类问题

GIVEN H2 测试库每轮由 `schema.sql` 重建
WHEN 存量库因缺表导致某条写库路径异常
THEN 说明明确指出这类缺口在集成测试中不可见，必须在 dev 真库上单独核对

### Requirement: 文档判据优先于文档常量

WHEN 项目级文档需要引用一次执行的输出（测试计数、覆盖率、健康检查结果等）,
文档 SHALL 引用产生该数值的命令与产物位置；如必须记录历史数值，须与产生它的 revision 一并记录。

**范围界定**：本需求只约束**文档表述**（治理文档、规范、提示词里怎么写）。单次交付的验收记录要素由 `update-agent-gate-single-source` 的「验收结论必须绑定产生它的执行」约束，两条需求各管一层，不得以其中一条的完成冒充另一条。

#### Scenario: 撰写阶段验收判据

GIVEN 一份阶段提示词需要规定"回归不得变差"
WHEN 撰写该判据
THEN 判据写成"执行唯一门禁命令后 Failures=0 且 Errors=0，用例总数不得少于开工时实测记录的数值"
AND 不写"必须仍是 NNN 全绿"这类需要人在文档顶部加更正段才能自洽的常量

#### Scenario: 发现残留的无 revision 常量

GIVEN 治理文档中出现未绑定 revision 的数值常量
WHEN 执行自查 `grep -rn '基线' spec/README.md docs/`
THEN 命中项必须全部是"判据"或"带 revision 的历史记录"
AND 其余命中判定为需要整改，整改方式是把常量换成判据

#### Scenario: 记录历史数值

GIVEN 需要留档某次验收的测试数
WHEN 写入文档
THEN 同一行记录 revision 与该数值的来源命令
AND 后续读者知道该数值描述的是哪一次执行而非当前状态
