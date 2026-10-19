# prod 迁移手工应用 runbook（W42 / W43 / W44）

> **适用**：存量 prod 库（数据目录建于 `2026-W42` 脚本合入之前的 `docker-compose.prod.yml` 部署）。
> 通用纪律（为什么没有自动执行者、A1–A4 核验口径定义、三件套完成标准）见 [docker/mysql/migrations/README.md](../docker/mysql/migrations/README.md) 与 [APPLIED.md](../docker/mysql/migrations/APPLIED.md)，本文不复制。
> **新空库不适用本流程**：首次 `docker compose -f docker-compose.prod.yml up` 的空数据目录由 `init` 脚本建库后，应用启动时 `schema.sql`（`CREATE TABLE IF NOT EXISTS`）一次建全五表，无需手工脚本。

## 0. 环境事实（从编排现场读，勿写死）

- 编排：`docker-compose.prod.yml`；主库容器 `exam-online-prod-mysql-master`，从库 `exam-online-prod-mysql-slave`（GTID 复制）。
- 库名：`exam_online`（compose `MYSQL_DATABASE`）。
- root 口令：prod 环境 `.env` 的 `MYSQL_ROOT_PASSWORD`（compose 强制必填），现场读，不进任何命令记录。
- 脚本零修改：三笔均为 `CREATE TABLE IF NOT EXISTS`，重复执行 no-op（dev 主/从库 2026-10-19 实测吻合，GTID 仅前移、对象零变化）。

## 1. 先判定是否需要本流程（只读，不动库）

```bash
docker exec exam-online-prod-mysql-master sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" exam_online -N -e "
   SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
   AND table_name IN (\"subjective_blind_reviews\",\"question_irt_params\",\"cat_quiz_sessions\",\"reconciliation_runs\",\"reconciliation_diffs\");"'
```

- **= 5**：五表已全（空库被 `schema.sql` 建全，或已应用过）——跳到第 7 步只做登记核对；
- **< 5**：继续第 2 步。

## 2. 应用前备份（prod 动 DDL 的前提）

```bash
docker exec exam-online-prod-mysql-master sh -c \
  'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines exam_online' \
  > prod-pre-w42w44-$(date +%Y%m%d-%H%M%S).sql
```

留档：备份文件 + `sha256sum` 校验和 + 当时 `git rev-parse --short HEAD`，与 [docker/mysql/backup/BACKUPS.md](../docker/mysql/backup/BACKUPS.md) 台账惯例对齐。

## 3. 按序应用（周期升序，失败即停）

宿主 Git Bash（stdin 直传）：

```bash
for s in 2026-W42-add-subjective-blind-reviews.sql 2026-W43-add-irt-adaptive-testing.sql 2026-W44-add-score-chain-reconciliation.sql; do
  docker exec -i exam-online-prod-mysql-master sh -c \
    'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" exam_online' < docker/mysql/migrations/$s \
    || { echo "FAIL at $s"; break; } && echo "OK $s"
done
```

Windows 无 stdin 重定向时备选：`docker cp docker/mysql/migrations/<脚本> exam-online-prod-mysql-master:/tmp/` 后 `docker exec … sh -c 'mysql … < /tmp/<脚本>'`（dev 清偿实测路径），完事清理 `/tmp` 副本。

## 4. 主库核验（验证对象，不是退出码）

口径 A3/A1/A2/A4 定义见 APPLIED.md。期望值：

| 表 | A1 列数 | 唯一索引 | 二级索引 | A4 |
|---|---|---|---|---|
| subjective_blind_reviews | 10 | uk_blind_review(submission_id,question_id,round_type) | idx_blind_exam_grader、idx_blind_sub_q | 自增块内 PRIMARY(id) |
| question_irt_params | 11 | uk_irt_question(question_id) | — | 同上 |
| cat_quiz_sessions | 15 | —（无唯一键） | idx_cat_session_student(student_id,id) | 同上 |
| reconciliation_runs | 15 | uk_recon_run_batch(batch_key) | idx_recon_run_exam | 同上 |
| reconciliation_diffs | 15 | uk_recon_diff_fingerprint(diff_type,submission_id,question_id,status) | idx_recon_diff_run、idx_recon_diff_status | 同上 |

逐表 `SHOW CREATE TABLE …\G` 与 `src/main/resources/schema.sql` 同名建表块逐列逐索引比对同形；`SHOW INDEX FROM …` 核对索引全集；A3 计数复跑应为 5。

## 5. 从库核验（不由人工直接应用，复制同步）

```sql
SHOW REPLICA STATUS\G   -- Replica_IO_Running/Replica_SQL_Running=Yes、Seconds_Behind_Source=0、错误列为空
```

主从 GTID `Executed_Gtid_Set` 对齐；第 4 步对象级核验在从库复跑一遍逐项一致。

## 6. 最小链路冒烟（README 纪律：必须碰真实链路）

| 脚本 | 冒烟动作（最小权限/最小动作） |
|---|---|
| W42 | 教师端打开主观题盲评列表（`GET /subjective?blind=true`）不 500 |
| W43 | 学生端发起一次自适应测验开局（触发惰性标定落 `question_irt_params`、会话落 `cat_quiz_sessions`，随后可立即终止会话） |
| W44 | 管理端触发一次对账 run（落 `reconciliation_runs`；无差异则 `reconciliation_diffs` 零行） |

任何一步出现被 `catch` 吞掉的 ERROR 日志（`audit_log` 先例）即视为失败，停下排查。

## 7. 台账登记（三件套之三）与提交

APPLIED.md 登记表当前只有 dev 主/从两列——prod 应用后**同笔扩两列**：表头加「prod 主库（exam-online-prod-mysql-master）/ prod 从库（exam-online-prod-mysql-slave）」；W42/W43/W44 三行 prod 列填「已应用（核验输出摘要）·时间 @ commit」，其余行 prod 列按实际填（未部署则占位注明）。提交一笔 `docs(migrations): W42/W43/W44 prod 已应用台账回填`（单文件纯台账，免全量门禁）并 push。

## 8. 应急回滚（仅在确认应用造成了损害时）

三脚本均为纯新增表、不动既有对象，正常无需回滚。确需回滚：

```sql
DROP TABLE IF EXISTS reconciliation_diffs, reconciliation_runs, cat_quiz_sessions, question_irt_params, subjective_blind_reviews;
```

回滚等同放弃对应功能链路（C5 盲评/C6 CAT/C7 对账），须同步评估应用侧影响并留档决策。
