# 数据保留与清理 Runbook（双闸门 · 全表矩阵 · 生产启用流程 · 指标联动）

> **定位**：本文件是数据保留（retention）口径的 canonical 事实源（harden-data-retention-scope，2026-10-18 落地）。
> 任何触碰清理调用点的改动（新增清理表 / 调整删除条件 / 调整保留窗口）先读本文；
> 矩阵与代码不一致时，先修代码或修文档，不允许两处各说各话。
>
> 机制出处：`add-data-retention`（三张考试生命周期辅助表）、`harden-security-config`（`audit_log` 按年龄）、
> `harden-data-retention-scope`（`exam_dlq_messages` 已重放死信 + 本 Runbook）。
> 实现唯一入口：`com.exam.monitoring.retention.RetentionService`（`purgeOnce()` 为唯一清理路径）。

## 一、机制与硬口径

1. **双闸门，缺一不可**：`exam.retention.enabled`（默认 `false`，任务不跑）+ `exam.retention.dry-run`
   （默认 `true`，只统计不删）。两者都显式打开才可能真删；此时启动必然打 WARN——
   **真删不可能被静默开启，看不到 WARN 就是没有生效**。
2. **宁可不删，不可错删**：清理面只允许进入"辅助数据"与"已处理完毕的留档"；
   答卷、成绩、快照、归属关系、错题消灭事实一律不在删除面（见 §二 矩阵）。
3. **三条清理路**，互不混用：
   - **考试生命周期**（`exam_behavior_logs` / `exam_submit_dedups` / `score_audit_logs`）：按 `exam_id` 删，
     只处理 `status >= 2 AND end_time < cutoff` 的终态旧考试（进行中一律不碰，哪怕 end_time 已过）；
   - **安全审计按自身年龄**（`audit_log`）：无 `exam_id`，只按 `created_time` 超窗清（走 `idx_audit_time`）；
   - **死信留档按状态+年龄**（`exam_dlq_messages`）：只按 `status = 'REPLAYED' AND created_time < cutoff`
     清（走 `idx_dlq_status_time` 最左前缀）。`PARKED`（超重投上限待人工排查）**绝不在删除面内**——
     它是唯一的事故现场证据。
4. **有界删除**：每批 `batch-size`（默认 1000）行、每张表每轮最多 `max-batches-per-run`（默认 20）批、
   每轮最多 `max-exams-per-run`（默认 100）场考试；每批独立短事务，不产生超大事务。
   单轮删不完属正常设计，下一轮继续（候选按 `end_time,id` 优先最老）。
5. **幂等与多实例**：不加分布式锁；两实例同时跑，重复的那次删 0 行，无副作用。
6. **单点异常不停摆**：单表/单考试失败记 ERROR 后继续其余目标。
7. **不声称磁盘释放**：InnoDB 的 DELETE 只标记页可复用，文件不变小；真正回收需离线 `OPTIMIZE`，
   属部署栈操作，不在本任务面内。

## 二、全表保留与清理矩阵（canonical）

### 2.1 清理面（允许 DELETE，共 5 张）

| 表 | 清理驱动 | 删除条件 | 命中索引（最左前缀） | 保留窗口配置（默认） | 指标 tag |
|---|---|---|---|---|---|
| `exam_behavior_logs` | 考试生命周期 | `exam_id = ?` | `idx_behavior_exam_student (exam_id, student_id)` | `retention-days: 180` | `table=exam_behavior_logs` |
| `exam_submit_dedups` | 考试生命周期 | `exam_id = ?` | `uk_submit_dedup UNIQUE (exam_id, student_id)` | `retention-days: 180` | `table=exam_submit_dedups` |
| `score_audit_logs` | 考试生命周期 | `exam_id = ?` | `idx_score_audit_exam (exam_id)` | `retention-days: 180` | `table=score_audit_logs` |
| `audit_log` | 自身年龄 | `created_time < ?` | `idx_audit_time (created_time)` | `audit-log.retention-days: 365` | `table=audit_log` |
| `exam_dlq_messages` | 状态+年龄 | `status='REPLAYED' AND created_time < ?` | `idx_dlq_status_time (status, created_time)` | `dlq.retention-days: 30` | `table=exam_dlq_messages` |

> 共同红线：**任何删除条件必须完全命中既有索引最左前缀**；`exam_dlq_messages` 的删除面
> 写死 `REPLAYED`——把 `PARKED` 纳入删除面属于违规改动，`DataRetentionIntegrationTest` 会击红。
> `audit_log` / `dlq` 的窗口默认值只是"给增长一个上界"的出档口径，真实保留期属合规决策，
> 须业务/安全口径确认后才改；确认前由双闸门保证不会静默删数据。

### 2.2 业务事实面（绝不清理，共 23 张）

| 类别 | 表 | 不清理的理由 |
|---|---|---|
| 账号与权限 | `users` / `roles` / `permissions` / `user_roles` / `role_permissions` | 当前归属与鉴权依据 |
| 题库与组卷 | `questions` / `tags` / `question_tags` / `papers` / `paper_questions` | 教学资产，可复用 |
| 快照 | `paper_snapshots` / `exam_snapshots` | 只读历史，答卷判分的依据源，删了等于篡改历史 |
| 考试与答卷 | `exams` / `exam_submissions` / `subjective_grades` | 成绩有可追溯义务（复核窗口、补考取分规则要回看） |
| 考务事实 | `exam_absence` / `exam_candidates` / `exam_reservations` / `score_review` | 缺考/名单/预约/复核申请都是事实记录，清理改变口径 |
| 归属关系 | `classes` / `user_class` | 清掉会改变应考名单口径，属业务破坏 |
| 学生资产 | `practice_attempt` / `student_question_favorites` | `practice_attempt` 是学生错题消灭事实；收藏是用户资产 |

### 2.3 未纳入且**禁止**发起清理的表（无时间索引，共 2 张）

| 表 | 现状 | 为什么禁止 |
|---|---|---|
| `user_messages` | 仅有 `idx_user_messages_user (user_id, id)`，无时间列索引 | 按 `created_time` 清 = 全表扫描；本仓库的失败口径是"宁可积压，不发全表扫描 DELETE" |
| `invite_codes` | 除主键外无任何二级索引 | 同上 |

> 若未来确实需要清理这两张表：**先补时间索引（属 DDL，走 `src/main/resources/schema.sql` 唯一入口 +
> `docker/mysql/migrations/` 存量脚本 + 人工应用验证），再单独立案接清理**。
> 不允许"先上无索引的 DELETE 再说"。

## 三、生产启用前置检查单（按序执行，全勾才许真删）

1. **索引存在性对账**：`schema.sql` 是 `CREATE TABLE IF NOT EXISTS`，存量库不会自动补索引。
   逐库确认 `idx_audit_time`、`idx_dlq_status_time` 已建（迁移台账见
   `docker/mysql/migrations/APPLIED.md`；缺了就先补迁移并人工应用验证）。
   没有索引的按年龄清理在"没有东西可删"的稳态也要读穿全表。
2. **双闸门确认**：生产配置此刻应为 `enabled=false`（或尚未配置）；确认现有
   `dry-run=true` 未被环境变量改掉。
3. **合规口径确认**：三档窗口（180 / 365 / 30 天）是否为业务/安全口径认可的真实值；
   不是就改配置再继续。
4. **试算一轮**（§四）：`enabled=true` + `dry-run=true` 跑至少一轮（cron 默认 03:30，
   可临时把 `exam.retention.cron` 调近以便当轮观察）。
5. **数字对账**（§四.3）：试算 candidate 数与 DB 侧 `COUNT(*)` 逐表吻合。
6. **真删切换**（§五）：改 `dry-run=false` 并重启，**启动日志必须出现"已启用真删除"WARN**；
   看不到 WARN = 配置未生效，停在试算态继续排查。
7. **备份先行**：真删前对 5 张清理面表做一次备份（`mysqldump` 单表即可）。
   已删数据不可恢复，本任务没有任何撤销手段。

## 四、dry-run 试算核验步骤

1. **应用日志**：每轮结束打单行汇总
   `保留清理完成 dryRun=true examsProcessed=... behavior(c/d)=.../... dedup=... audit=... securityAudit=... dlq=... total=...`
   dry-run 下所有 d（deleted）必须为 0；不为 0 直接判配置漂移，停用并排查。
2. **指标**：Micrometer 指标 `exam.retention.rows`（Prometheus 暴露名
   `exam_retention_rows_total`，tags `table` + `action`）。dry-run 下
   `action="candidate"` 有值、`action="deleted"` 不出现。
3. **DB 对账**（样例，cutoff = NOW() 减窗口；与 Mapper 条件逐字同源）：

   ```sql
   -- 考试生命周期三表：候选 = 终态旧考试的行数合计
   SELECT COUNT(*) FROM exam_behavior_logs ebl
     JOIN exams e ON e.id = ebl.exam_id
    WHERE e.status >= 2 AND e.end_time < DATE_SUB(NOW(), INTERVAL 180 DAY);
   -- audit_log
   SELECT COUNT(*) FROM audit_log
    WHERE created_time < DATE_SUB(NOW(), INTERVAL 365 DAY);
   -- exam_dlq_messages（注意：只数 REPLAYED，PARKED 不属于候选）
   SELECT COUNT(*) FROM exam_dlq_messages
    WHERE status = 'REPLAYED'
      AND created_time < DATE_SUB(NOW(), INTERVAL 30 DAY);
   ```

4. **解读**：candidate 长期为 0 = 稳态，无需启用真删；数量大 = 先看 §五 的窗口口径，
   确认不是把"要留的数据"算进来了。

## 五、真删切换步骤与指标联动

1. 配置 `exam.retention.enabled=true` + `exam.retention.dry-run=false`（环境变量或配置中心），重启。
2. 启动日志核对 WARN：`数据保留清理已启用真删除：enabled=true dry-run=false ...`——这是双闸门的机械证据。
3. 首轮（次日 03:30）观察：
   - 日志汇总行 d 值与 candidate 值的差值 = 本轮批上限截断的余量（属正常，下轮续删）；
   - `exam_retention_rows_total{action="deleted"}` 逐表增量 ≤ `batch-size × max-batches-per-run`；
   - 抽查 DB：`PARKED` 行数不变、窗口内 `REPLAYED` 行数不变、终态旧考试三表行数下降。
4. **告警联动**：保留清理**无专属告警规则**（裁决口径：删除面被"PARKED 禁删 + 业务事实表不在删除面"
   两道硬护栏约束，护栏漂移由测试与规范守护，不是运行时告警能拦的）。
   运维观测信号：`exam_retention_rows_total` 持续增长且 `deleted` 长期为 0 = 清理失效（闸门被关或异常频发），
   属人工巡检项；应用 ERROR 日志（`保留清理失败 table=...`）是单表异常的直接信号。
5. **回退**：改 `enabled=false`（或 `dry-run=true`）重启即停，无中途态、无缓存。
   已删的行不可恢复——所以 §三.7 的备份必须在切换前完成。

## 六、故障与常见问题

| 现象 | 处置 |
|---|---|
| 日志出现 `保留清理失败 table=xxx` | 单表异常隔离，其余表照常；按堆栈查因，不要重启了事 |
| `PARKED` 死信持续积压 | 这是消费侧问题不是保留问题：走 `DlqAdminController`（`/api/admin/mq/dlq/replay`）人工排查重投；**严禁**为"清空死信"调整删除面 |
| 试算数量与 DB 对不上 | 先核对窗口配置是否被环境变量覆盖，再核对 SQL 条件是否与 §四.3 同源 |
| 磁盘没变小 | 预期行为（§一.7）；需要回收空间走离线 `OPTIMIZE`，部署栈操作 |
| 想清理 `user_messages` / `invite_codes` | §二.3：先补时间索引（DDL 单独立案），再立案接清理；不允许顺手 DELETE |

## 七、测试守护与改动纪律

- 守护测试：`DataRetentionIntegrationTest`（dry-run 不删 / 仅终态旧考试 / `PARKED` 禁删 /
  超窗 `REPLAYED` 按批删 / 批上限 / 安全审计按年龄）——改动清理面前先让它红再让它绿。
- 改动红线（违反即违规）：
  1. 零 DDL：不改 `schema.sql` 即可完成的清理面变更才属本域；要动表先走建表唯一入口 + 存量迁移流程；
  2. 集成测试禁止 `@Sql` 自建表（H2 + `schema.sql` 唯一来源）；
  3. 删除条件必须命中既有索引最左前缀，禁止全表扫描式 DELETE；
  4. 新增清理表必须同笔登记进 §二矩阵并补 `DataRetentionIntegrationTest` 场景。
- 历史裁决备注：`exam_dlq_messages` 曾被裁决"明确不写 DELETE"（无 `exam_id`、当时无状态维度清理资格）；
  `harden-data-retention-scope` 推翻该裁决的依据是"清理资格以**已处理完毕**为准"——`REPLAYED` 即处理完毕，
  `PARKED` 永远不是。若未来再有推翻，须走新提案并更新本节。
