# `docker/mysql/migrations/` —— 手工迁移脚本（**没有自动执行者**）

## 先说结论：放进本目录 ≠ 变更已生效

本目录下的 `2026-WNN-add-*.sql` **不会被任何启动路径或测试路径自动执行**。脚本数量以
`git ls-files docker/mysql/migrations | grep -c '\.sql$'` 为准，本文件不写数——写死的那个数每次新增脚本都会过期。

原因有三条，都可自查：

1. `docker-compose.yml` 只把 **`./docker/mysql/master/init`** 与 **`./docker/mysql/slave/init`** 挂进容器的
   `/docker-entrypoint-initdb.d`（`grep -n 'docker/mysql' docker-compose.yml` 只出这两处命中）。**本目录没有被挂载**。
   而那两个 init 目录里的脚本只在**数据目录为空**（首次建库）时执行，内容只有复制账号与主从配置，不含任何业务表——
   所以也不要图省事把业务 DDL 塞进 init 目录。
2. 应用侧建表走 `src/main/resources/schema.sql`，而它是 `CREATE TABLE IF NOT EXISTS` 的幂等脚本：
   **存量库不会因为应用启动而补上新表/新列**。
3. 集成测试用的是每轮重建的 H2，`schema.sql` 每次都从头建全表——**"存量库缺表"这类缺口在测试里永远看不见**。

规范出处：`docs/需求决策记录.md` 第十八节（`:384-385`，标题「建表与迁移的唯一事实源」）。
本目录是被该决策**明文认可**的存量库手工脚本存放处：本文件不禁止它、不移动它、不改其中任何脚本，只要求它自述触发方式与后果。

## 已经真实发生过的先例：`audit_log`

`harden-security-config` 把 `audit_log` 写进 `schema.sql` 的同时也写了本目录的 W16 迁移脚本，但**没人对手上那个存量库执行它**。
后果（`docs/需求决策记录.md:501-505`、`docs/指导Agent交接文档.md` 的 dev 启动环境小节都记着）：

- dev 库压根没有 `audit_log` 表；
- 每次登录的审计写入异常被 `catch` 掉，只剩一条 ERROR 日志，**没有任何东西变红**；
- 新加的审计读接口在 dev 上直接 500；
- H2 测试每轮重建 schema，全绿，**看不见这个缺口**。

同一段记录里还有一个反向教训：**写脚本前先查 `schema.sql` 是否已有等价对象**。
曾有一份 `db/migration` 脚本要建的 `idx_sweep_candidate` 与既有索引 `idx_submissions_sweep` 列组合完全相同——
提案作者没查 `schema.sql` 就立了重复迁移，而它因为无人执行所以一直没暴露（那两个 inert 文件已删除）。
新增索引前必须核对既有索引是否已覆盖该列组合（同节 `:386`）。

## 什么时候需要本目录的脚本

| 场景 | 要不要写脚本 |
|---|---|
| 新建一个库（空库首次启动） | **不要**。`schema.sql` 一次建全，本目录脚本一律无需执行 |
| 已存在、且建库时间晚于目标对象进 `schema.sql` 的时间 | 通常不需要；先 `SHOW CREATE TABLE` / `SHOW INDEX` 核对，缺什么补什么 |
| **存量库**需要一次 `CREATE TABLE IF NOT EXISTS` 覆盖不到的变更（补列、补索引、补历史库缺失的表） | **要**，脚本放本目录，并按下面的流程**手工应用到每一个已存在的库** |
| 想让某个 dev/测试库"看起来和新库一样" | 更稳的做法是重建该库让 `schema.sql` 跑一遍，而不是攒一堆手工脚本 |

## 手工应用流程（顺序即语义，别跳步）

1. **先改 `src/main/resources/schema.sql`**（新库的唯一事实源，见仓库根 `AGENTS.md` 约定 1）。
   只往本目录扔脚本而不改 `schema.sql`，等于让新库和存量库长期分叉。
2. **查等价对象**：`grep -n '<表名或索引名>' src/main/resources/schema.sql`，确认不是重复对象；是重复就别写脚本。
3. **写脚本**：命名沿用 `2026-W<周>-add-<对象>.sql`；头部按既有脚本的格式写明「适用：已建库的 MySQL 环境 / 新建库由 `schema.sql` 一次建全，无需本脚本 / 幂等性说明」。
4. **决定顺序**：跨多个脚本时按文件名周期**升序**应用（同一张表上后写的脚本可能依赖先写的对象）。
5. **应用到每个存量库**（dev 主库；从库若也承载读流量则同样要补）。命令行用容器名，不要用端口拼连接串：

   ```bash
   docker exec -i exam-mysql-master mysql -uroot -p exam_online \
     < docker/mysql/migrations/<你的脚本>.sql
   ```

   口令取该库的实际值（dev 默认值见 `src/main/resources/application-dev.yml`，本文件不复制）。
6. **执行后必须验证**，验证的是对象而不是"命令没报错"：
   ```sql
   SHOW CREATE TABLE <表名>\G
   SHOW INDEX FROM <表名>;
   SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '<表名>';
   ```
   再跑一次真正**用到**该对象的接口或链路（`audit_log` 那次的教训就是异常被 `catch` 吞了，只能靠 ERROR 日志发现）。
7. **记录**：在决策记录或回报里写清「哪个库、什么时间、执行了哪几个脚本、验证输出是什么、当时的 commit」。
   只写"脚本已提交"不构成迁移完成的证据——按 `AGENTS.md` 约定 4，那种回报判违规。
   结构化记录（哪个环境应用过哪些脚本、核验 SQL、新环境 runbook）集中承载于本目录 `APPLIED.md`，
   应用完成 = 应用 + 执行后验证 + 更新该登记表三件套齐。

## 本目录的红线

- **不改既有脚本**：它们是历史记录，已经应用过的库不会因为你重写了脚本而回到一致状态。要补东西就新写一个周期号的脚本。
- **不要在本文件里维护脚本清单或计数**：`git ls-files docker/mysql/migrations` 就是清单，它每次都准。
- **不要把希望寄托在"下次有人 up 一遍就好了"**：没有那个下次；init 目录只在空数据目录时执行一次。
