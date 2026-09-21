# 提案：Agent 上下文路由与约定可达性（工程性变更 E1）

> 性质：工程性变更，不新增业务功能。来源：`better-harness` 评审（provider=qoder，窗口 2026-08-22 ~ 2026-09-21）发现 #1、#4。
> 姊妹提案：`update-agent-gate-single-source`（E2，门禁命令与验收判据）。本提案**不定义命令**，只建立"去哪儿查"的路由。

## Why

根入口给不出正确事实，而正确事实没有一个 agent 一定会读到的位置。这不是"文档不够好"，而是**同一事实在仓库里有两到三个副本，其中被读到最多的那个最新**——副本会漂移，指针不会。

### 已核实的事实（逐条可复算）

**1）`README.md` 是 agent 进入仓库的第一份阅读，而它停留在阶段 1，并给出错误的连接事实。**

| 事实 | `README.md` 的说法 | tracked 真源 |
|---|---|---|
| 主库端口 | `:14` "dev 默认连 `127.0.0.1:3306/6379`" | `src/main/resources/application-dev.yml:7` → `jdbc:mysql://127.0.0.1:13316/exam_online` |
| 从库端口 | 未提 | 同上 `:34` → `127.0.0.1:13317` |
| 口令 | `:28` 变量表把 `DB_PASSWORD` 默认写成"本机 root/root" | 同上 `:9` → `${DB_PASSWORD:root123}` |
| 下一步 | `:60` "下一变更 `add-authentication`"（阶段 2） | `spec/README.md:19-27` 进行中为阶段 19–23 |

- `git log --oneline -- README.md` 输出 **1 行**（`125efd7`，2026-09-12 骨架提交）。此后所有提交没有一个碰过它——**包括最近一次专门修环境的 `974248b`**（`fix(env): dev 零环境变量可起；三件套 restart 策略；compose 端口对齐实况`）。
- `grep -c "spec/" README.md` 与 `grep -c "docs/" README.md` **均为 0**。全文 60 行里没有任何一条指向规范区或交接区的链接。

**2）误判的后果已经发生过，而且是本仓库自己记下来的。**

- `docs/指导Agent交接文档.md:200`："Windows 的 `MySQL80` 服务也拒绝 root/root。曾经有人据此误判成"另一套实例、凭据不通"……实际是容器没起"；
- `spec/changes/add-frontend-skeleton-auth/agent-prompt.md:71`："阶段 18 第 1 轮子 agent 就卡在这里，误报成「环境坏了」"。

即：**不是"可能误导"，是记录在案、已重复两次的真实失败**。

**3）真正的硬约定在三层深处，而治理文档自己的计数也在漂。**

- canonical 出处：`docs/需求决策记录.md:384`（未接 Flyway、建表唯一入口 `src/main/resources/schema.sql`）、`spec/README.md:158`（集成测试不得用 `@Sql` 自建表）、`spec/README.md:159`（实体字段与建表定义必须双向一致）；"不要做"清单在 `docs/指导Agent交接文档.md:26-34`（拆微服务 / Caffeine+Redis 二级缓存 / 拆 God class / ShedLock-Quartz-Redisson / 给 DLQ 加 TTL / 在线 `OPTIMIZE TABLE` / 为点亮告警降阈值），另有 `:193` `rep_test` **不要删**、`:236` 启动期异常 **不要顺手修**。
- 这些文档**只通过 `spec/README.md:161-166` 的"参考资料"清单被引用一次**。承载文件名是非 ASCII，而 `core.quotePath` 未设置（`git config --get core.quotePath` 无输出即默认开启转义），于是它在 `git ls-files` 与工具输出里呈八进制转义串——本次评审过程本身又一次复现了 `交接文档:231-234` 记录的"中文串让 Bash 失败"坑。
- `spec/README.md:52` 自称"已归档变更共 22 个"，而 `find spec/changes/archive -mindepth 1 -maxdepth 1 -type d | wc -l` = **30**。

**4）`docker/mysql/migrations/` 是"手工脚本存放处"，但它没有任何自动执行者——这一点没写在目录里。**

- 实测：`git ls-files docker/mysql/migrations | wc -l` = **10**（`2026-WNN-add-*.sql` 形状）；`docker-compose.yml` 只挂载 `./docker/mysql/master/init`（`:40`）与 `./docker/mysql/slave/init`（`:65`）。
- 因此：**把脚本放进这个目录 ≠ 变更已生效**。这正是 `audit_log` 事故的形状——`docs/指导Agent交接文档.md:220`："schema.sql 是 `CREATE TABLE IF NOT EXISTS`，存量库不会自动补表（实测：`audit_log` 因此在 dev 上缺失……）"，`docs/需求决策记录.md:501` 同记 dev 库"压根没有 `audit_log` 表"。
- 反向先例也在：`docs/需求决策记录.md:385` 记两份 `db/migration/V*.sql` **从不执行**、已删除（实测 `src/main/resources/db` 已不存在），并记"另一份要建的 `idx_sweep_candidate` 与既有 `idx_submissions_sweep` 列组合完全相同"——**提案作者没查 `schema.sql` 就立了重复迁移**。
- **边界必须写清**：`docker/mysql/migrations/` 是 `需求决策记录.md:384` 明文认可的存量库手工脚本位置。**本提案不禁止它、不移动它、不改其中任何脚本**，只要求它自述触发方式与后果。

### 当前状态

事实有多个副本，读到最多的那个最旧；硬约定在深处且难在工具输出里被看见；无执行者的目录靠口口相传。

### 期望状态

1. **根入口只做路由**：`README.md` 不再承载可能过期的运行事实，改为"要查 X 去看哪个文件"；
2. **一条约定一个 canonical 出处 + 一个 agent 必经的索引**：索引只写规则一句话、出处指针与自查命令，不复制事实正文；
3. **每个 SQL 目录自述其执行者**：读到目录第一行就知道"放进这里会不会自动生效";
4. **判据优先于常量**：新增文档引用产生数值的命令，而不是抄数值。

## What Changes

1. **`README.md` 重写为路由表**：删除端口/口令/工程结构/下一步四类事实副本，改为指向 `application-dev.yml`、`spec/README.md`、`docs/指导Agent交接文档.md` 的入口指针（指针不含字面值）。
2. **新增仓库根 `AGENTS.md`**：4 条硬约定的一句话索引 + 每条的 canonical 出处 + 可机械执行的自查命令；"不要做"清单以指针形式引用 `交接文档:26-34`。
3. **新增 `docker/mysql/migrations/README.md`**：声明该目录无自动执行者、手工应用时机与顺序、不执行的后果、`audit_log` 先例。
4. **`spec/README.md` 登记与去常量**：能力地图新增 `agent-harness`、进行中变更表新增本变更行、把 `:52` 的"共 22 个"改成以 `find` 结果为准的表述。
5. **明确不做**：不改 `pom.xml`、`.mvn/`、`maven-settings.xml`、`frontend/`、`src/**`、`docker-compose.yml`、`schema.sql`，不改 `docker/mysql/migrations/*.sql` 任何一行。

## Impact

### 受影响的规范

- `spec/specs/agent-harness/spec.md` — **新建能力域**，本提案贡献 4 条 `ADDED` 需求（根入口只路由 / 硬约定必经索引 / 无执行者目录自述 / 判据优先于常量）。

### 受影响的文件（写入边界）

- 修改 `README.md`（60 行 → 路由表，净减少事实副本）
- 新增 `AGENTS.md`
- 新增 `docker/mysql/migrations/README.md`
- 修改 `spec/README.md`（能力地图 +1 行、进行中表 +1 行、`:52` 计数表述更正、工作流补一句"收尾时同步 AGENTS.md 指针"）

**不要触碰**：`src/**`、`pom.xml`、`.mvn/`、`maven-settings.xml`、`frontend/**`、`docker-compose.yml`、`schema.sql`、`docker/mysql/migrations/*.sql`、`spec/changes/add-frontend-*/*`（那是 E2 与前端阶段的地盘）。

### 与进行中阶段 19–23 的关系

- 阶段 19–23 的纪律是"一律不改后端"（`spec/README.md:29`）。本提案不碰 `src/main`、`src/test`、`pom.xml`，**与该纪律一致**；
- 但 `spec/README.md:19` 另有纪律"同一工作树不得并行跑两个子 agent"，故本提案**排在阶段 19 返修收口之后单独跑一轮**，或在独立工作树完成后由指导 agent 合并；
- 收益窗口明确：它直接消除"下一位前端子 agent 照 `README.md` 连 3306、再误判一次环境坏了"的第三次失败。

### 用户影响 / API 变更

- 无端点、无契约、无数据结构变化；`openapi.yaml` 与鉴权语义不受影响。

### 需要迁移

- [ ] 数据库迁移（**无**）
- [ ] API 版本提升（**无**）
- [x] 文档更新（`README.md` / `AGENTS.md` / `docker/mysql/migrations/README.md` / `spec/README.md`）
- [ ] 用户沟通（不适用）

## 时间线评估

小：约半天。四个文件、零代码、零构建配置；验收全部由 `grep` 与 `git ls-files` 机械判定，含一次变异验证。

## 风险

- **`AGENTS.md` 变成第四个副本（最高风险）**。缓解：规范需求 R2 的 Scenario「索引本身不得复制易变事实」+ R4「判据优先于常量」，并配自查命令 `grep -nE '13316|13317|root123|Tests run' AGENTS.md` 命中为 0；变异验证必须证明这条自查真能变红。
- **provider 依赖**：`AGENTS.md` 只对识别它的工具生效，人类新成员仍读 README。缓解：README 的路由表指向同一批 canonical 文件，两者互为镜像但都不复制事实。
- **删掉 README 里的端口会不会更难找人反而更易误判？** 不会——README 不写"13316"这个数，只写"dev 默认值以 `src/main/resources/application-dev.yml` 为准，本文件不复制其字面值"。数值所有权回到配置，配置改时不需要第三处跟改。
- **可能与未被枚举的既有注入机制重复**。本提案的事实核对限定在项目作用域：那次评审的资产快照以 `includeUserHome=false` 采集，同一 provider 实际生效的用户与插件资产层**没有被枚举**，因此无法排除"某处已有等价的约定注入"。缓解：任务 2 第 2 步只允许以**指针**形式引用正文；若实施时确认某个已配置机制已承载同一约定，`AGENTS.md` 应退化为一张更小的指针表而不是新增正文，并在 spec/README.md 记下来由谁承接。
- **治理文档计数再次漂移**：不在 `spec/README.md` 里保留新的硬编码计数，改成"以 `find spec/changes/archive -mindepth 1 -maxdepth 1 -type d | wc -l` 为准"，让计数变成可复算语句。
- **本提案不解决门禁命令与基线常量**（E1 只路由）。若只采纳 E1 而不采纳 E2，`README.md` 会指向一份仍写着互斥命令说法的 `spec/README.md`；故 E1 的路由条目对"唯一门禁命令"一项**必须留待 E2 回填**，本提案在 `AGENTS.md` 里对该项写"待 E2 落地"而**不抄任何一种命令**。
