# `docker/mysql/backup/` —— MySQL 备份与恢复脚本（**没有自动执行者**）

> 生产化 P5 交付：`backup.sh`（全量 dump）+ `restore.sh`（隔离恢复演练）+ 本 SOP +
> `BACKUPS.md`（人工执行台账）。对应 reliability 能力域「数据库备份与恢复演练」Requirement。

## 先说结论：脚本入库 ≠ 备份已运行

本目录的脚本**不会被任何启动路径、compose 或测试路径自动执行**。它和
`docker/mysql/migrations/` 的「无自动执行者」纪律同源：放进仓库只是交付了**能力与流程**，
真正跑备份必须在宿主上用 `crontab`（或人工）调用，且每次执行要在 `BACKUPS.md` 登记。

- `grep -n 'docker/mysql' docker-compose.yml` 只出 `master/init` 与 `slave/init` 两处，
  本 `backup/` 目录**不在任何挂载/init/启动路径**中，天然无自动执行者。
- 脚本入库不等于备份已运行。任何声称「备份文件在仓库/CI 里 = 已备份」的回报判违规。

## 什么时候备份、轮转策略

- **备份命令（零硬编码口令，凭据经环境变量注入）**：
  ```bash
  # dev 主库
  MYSQL_ROOT_PASSWORD=<口令> ./backup.sh --env dev
  # prod 编排主库（凭据缺省即失败，见 docker-compose.prod.yml）
  MYSQL_ROOT_PASSWORD=<口令> ./backup.sh --env prod
  ```
- 产物落本目录 `out/`（gitignored，不入库）：`<db>-<时间戳>.sql.gz` + `<时间戳>.sha256`。
- **轮转**：默认保留最近 7 份（`--keep N` 覆盖），过期副本连同其 `.sha256` 一并删除。
- **幂等**：脚本可被宿主 cron 反复调用（同名冲突由时间戳避免，无需清理即可复跑）。
- 备份是全量 dump（`--single-transaction --source-data`），对目标库**只读、不锁表**
  （InnoDB MVCC 一致性快照）。

## 恢复演练（隔离一次性容器）

> 恢复是**破坏性写操作**。红线：**不得对 dev/prod 本体做恢复演练**——演练一律在
> **一次性隔离 MySQL 容器**（独立卷、一次性容器名）进行，目标容器与库名显式指定，
> 缺 `--confirm` 即拒绝。恢复脚本自带三重防护（见 `restore.sh` 头注）。

隔离演练流程（示意，假设一份 `20261012-010203.sql.gz` 在 `out/`）：

```bash
# 1) 起一次性隔离 MySQL（独立卷，与 dev/prod 网络与数据完全隔离）
docker run -d --name backup-drill-isolated \
  -e MYSQL_ROOT_PASSWORD=<口令> -e MYSQL_DATABASE=restore_target \
  -v backup-drill-vol:/var/lib/mysql mysql:8.0
# 2) 等待 healthy 后恢复
MYSQL_ROOT_PASSWORD=<口令> ./restore.sh \
  --dump out/exam_online-20261012-010203.sql.gz \
  --container backup-drill-isolated --db restore_target --confirm
# 3) 核对脚本输出的表数与抽样行数，检验 sha256 一致；演练后销毁容器与卷
docker rm -f backup-drill-isolated; docker volume rm backup-drill-vol
```

恢复脚本会：先打印 dump 元信息（大小/sha256/建表数）并校验 sha256，再要求显式
`--confirm`；恢复后输出目标库表数与关键表抽样行数，供人工比对。

### 宿主 crontab 示例

以下为**手工配置示例**（本仓库无任何自动执行者；是否需要定时、定在何时由部署方决定）：

```cron
# 每日 02:30 备份 dev 主库，保留 7 份；日志落宿主本地（不入库）
30 2 * * * cd /path/to/examOnline && MYSQL_ROOT_PASSWORD=xxx ./docker/mysql/backup/backup.sh --env dev >> /var/log/backup-dev.log 2>&1
# 每日 03:30 备份 prod 编排主库
30 3 * * * cd /path/to/examOnline && MYSQL_ROOT_PASSWORD=xxx ./docker/mysql/backup/backup.sh --env prod >> /var/log/backup-prod.log 2>&1
```

> cron 里写口令属宿主本地配置，不写进任何仓库文件（脚本 require 的是环境变量注入）。

## 丢失边界（三段，不对齐即视为与 HEAD 冲突）

> 本段是对「数据丢失时哪些数据可以/不可恢复」的**如实边界陈述**，引用既有实现事实，
> 不新造任何持久化承诺。边界口径来源与仓库事实核对见下，冲突即需上报裁决。

### 1. MySQL 业务库 —— 本脚本覆盖

- 全量 dump + 恢复演练覆盖 **MySQL 业务库**（dev 主库 / prod 编排库，`exam_online`）。
- 主从只防「单库（主机）损坏」，**不防误删**：GTID 复制会把 `DROP TABLE` 忠实同步到从库，
  所以备份/恢复能力只对**真正能落地的那份数据**（主库库本体）负责。

### 2. Redis 草稿 —— 易失、可重建，不在脚本范围

- 既有事实：`docker-compose.yml` 的 redis 服务 `command: ["redis-server", "--appendonly", "yes"]`
  （appendonly 开启即 RDB/AOF 落盘，但语义仍是**易失可重建**）：Redis 承载的是草稿缓存与
  限流令牌桶等，答案终态以 MySQL `exam_submissions` 为准。
- 边界：**本脚本不备份 Redis、不承诺 Redis 数据恢复**。Redis 丢失 = 草稿/中间态可重建，
  不构成业务终态损失（终态在 MySQL，由本脚本覆盖）。
- 对齐：anti-cheat/data-consistency 域把 Redis 当作「可失败、可降级」的辅助层
  （如监考草稿批读 MGET、限流 fail-open），与「草稿可重建」语义一致。

### 3. RabbitMQ 消息 —— durable 队列持久化但按保留策略留档，不在脚本范围

- 既有事实：交卷队列与死信队列用 `QueueBuilder.durable(...)`（持久化），`docker-compose.yml`
  的 rabbitmq 容器有持久卷；reliability 域「死信队列不设过期与容量上限」以「留档 + 人工处置」
  替代自动清理。
- 边界：**本脚本不备份 RabbitMQ、不承诺从备份恢复**任何在途/死信消息。消息的可恢复性
  靠 RabbitMQ 自身持久化 + 消费端幂等（答案终态落库后即由 MySQL 覆盖），不靠本备份。

### 4. binlog —— 事实存在但 PITR **显式排除**

- 既有事实：dev/prod 主库均开 `--log-bin=mysql-bin` + `--binlog-do-db=<库>`（GTID 复制所需），
  dump 的 `--source-data` 会把当时的 binlog 位点/GTID 写进注释。
- 边界：**本脚本不承诺也不演练按时间点恢复（PITR）**——只做全量 dump 与全量恢复；
  binlog 是否补备份/pitr 属后续裁量，不以本脚本成立。

### 5. 题干图片持久卷（question-images-data）—— 备份与恢复指引

- **既有事实**：生产部署（`docker-compose.prod.yml`）将题干图片持久化于 Docker 命名卷 `question-images-data`（挂载于应用 `/data/question-images`）。图片为用户上传的业务数据资产。
- **冷备命令（宿主一次性容器导出压缩包）**：
  ```bash
  # 备份到本目录 out/ 下
  docker run --rm \
    -v question-images-data:/data:ro \
    -v "$(pwd)/docker/mysql/backup/out":/backup \
    alpine tar czf /backup/question-images-$(date +%Y%m%d-%H%M%S).tar.gz -C /data .
  ```
- **恢复命令（在维护模式/停止 app 容器后，恢复到命名卷）**：
  ```bash
  # 恢复指定的图片备份文件到数据卷
  docker run --rm \
    -v question-images-data:/data \
    -v "$(pwd)/docker/mysql/backup/out":/backup \
    alpine sh -c "rm -rf /data/* && tar xzf /backup/<备份文件名>.tar.gz -C /data"
  ```

## 显式排除（非目标）

- **异地容灾/异地备份**：本脚本假设自备份产物留在宿主机本机 gitignored 目录；跨机/跨机房
  复制、灾备站点属单机 docker 环境之外的基础设施，显式不在本刻度。
- **admin UI 备份管理面**：不做任何页面/接口，纯脚本 + 宿主 cron + 人工台账。

## 执行与登记纪律（对齐 migrations APPLIED.md）

- 每次真实备份或恢复演练，按 `BACKUPS.md` 台账登记：日期、环境、文件名、sha256、演练结论。
- 登记为**人工义务**：脚本入库 ≠ 备份已运行；只提交脚本不跑、或只跑不登记，均不构成完成。
- 台账是「何时真正备份/演练过」的唯一真值来源，与归档 evidence 互为对照、不重复。