# 提案：备份与恢复演练（mysqldump 脚本化 + 双环境实测 + 丢失边界文档化，生产化 P5，add-backup-restore-drill）

> 状态：已立项待执行。阶段 0 查证基于 main `fa06223`（2026-10-12）现场重核。范围按用户裁决定版：**dev 主库 + prod 编排库双覆盖**、**脚本 + 宿主 cron 文档化（不加 compose 定时服务）**。台账候选 P5 定性属实：全仓零 mysqldump/xtrabackup/backup 痕迹，`docker/` 无备份目录。

## Why

- **真缺口（`fa06223` 现场核）**：业务数据零备份机制——dev 主从（`exam-mysql-master`/`exam-mysql-slave`，GTID 复制）与 P3 交付的 `docker-compose.prod.yml` 库均无任何 dump/恢复手段；「主从」只防单库损坏，不防误删（`DROP TABLE` 会被忠实复制到从库）。数据资产级风险是生产化三部曲（部署 P3 / 告警 P6）之后最后一块硬缺口。
- **既有事实可复用（现场核）**：dev 主库已开 `--log-bin=mysql-bin` + `binlog-do-db=exam_online`（binlog 事实存在，PITR 属后续不承诺）；`docker/mysql/migrations/` 的「**无自动执行者**」纪律与 `APPLIED.md` 人工执行台账形态可直接复制为本案脚本域纪律与 `BACKUPS.md` 台账；`docker/certs/out/` 的 gitignore 产物目录先例可复制为备份产物目录。
- **需求文档口径对照与收窄（如实）**：`docs/examOnline需求规格说明书.md` 期望「每日全量 + 实时 binlog + 异地备份/容灾」——本案收窄为**单机环境的全量 dump + 恢复演练**；binlog 事实文档化但不承诺 PITR 演练；**异地容灾显式排除**（单机 docker 环境外基建）。admin UI 备份面维持此前 survey 的范围外裁决（脚本域不做页面）。
- **丢失边界文档化（台账口径，不新造承诺）**：Redis 草稿（compose 已 `appendonly yes`，易失可重建语义）、RabbitMQ 持久化消息（durable 队列 + DLQ 现状）——按 data-consistency 域既有结论引用对齐。

## What Changes

1. **`docker/mysql/backup/backup.sh`**：mysqldump 全量备份脚本——`--single-transaction --source-data`（GTID 一致性）+ triggers/routines/events → gzip → sha256 落 `docker/mysql/backup/out/`（产物不入库）；目标参数化（`--env dev|prod` 映射 `exam-mysql-master` 与 `docker-compose.prod.yml` 的固定 `container_name`，`--container` 可覆盖——容器名是版本库事实非机器绑定）；凭据 `${MYSQL_ROOT_PASSWORD:?required}` 环境变量形态（对齐 compose 参数化先例，脚本零硬编码口令）；幂等可被宿主 cron 调用；轮转保留最近 N 份（默认 7，`--keep` 覆盖）。
2. **`docker/mysql/backup/restore.sh`**：恢复脚本——目标须显式参数（容器 + 库名），恢复前打印 dump 元信息（表数/大小/sha256）并要求显式确认参数（防误恢复生产库）；恢复后校验（恢复库表数 vs dump 表数、关键行抽样对照）。
3. **`docker/mysql/backup/README.md`（SOP + 边界声明）**：何时备份、轮转策略、宿主 crontab 示例（**无自动执行者**声明——脚本入库≠备份已运行，同 migrations 纪律）、恢复步骤、演练流程；丢失边界三段（Redis/RabbitMQ/binlog）按 Why.3 口径；异地容灾显式排除声明。
4. **`docker/mysql/backup/BACKUPS.md`（执行台账，对齐 `APPLIED.md` 先例）**：每次执行人工登记（日期、环境、文件名、sha256、恢复演练结论、执行者）。
5. **既有文件受控修改**：`.gitignore` 追加 `docker/mysql/backup/out/`；`README.md` 登记行（要查什么去哪儿查表或部署章节，顺手补 certs 步骤序一行——P3 观察项顺手收敛）；`spec/README.md` 三处常规登记。`docker-compose.yml`、`docker/mysql/migrations/**`、`docker/observability/**`、`application*.yml` 零触碰。
6. **spec 基线**：`spec/specs/reliability/spec.md` 末尾 ADDED「数据库备份与恢复演练」Requirement（六 Scenario 见 spec-delta）。
7. **三件套随实施笔一并入库**。

## Impact

- **规范**：reliability 能力域 ADDED 1 Requirement（六 Scenario）；既有 Requirement 零改动。
- **代码**：零业务代码（`src/`、`frontend/`、`pom.xml`、`schema.sql`、`openapi.yaml`、`.github/` 零触碰）；零新增测试（对齐 P3 纯脚本域先例——脚本可执行性以真实实测留证，不以测试代偿）；**用例数零变化，`gate-baseline.json` 合法持平零改动**。
- **用户/API**：无任何可见变化。
- **数据与部署**：备份脚本对目标库只读（mysqldump --single-transaction 不锁表）；恢复演练**只在隔离一次性容器**进行，dev/prod 库本体零写入、共享资源零触碰；备份产物落本机 gitignored 目录不入侵仓库。
- **流程影响（新义务）**：`BACKUPS.md` 台账要求此后每次人工备份后登记（对齐 migrations APPLIED.md 纪律）。
- **外发动作授权**：无 push 之外的外发；push（feature/main）按惯例派发即授权。
- **非目标（显式排除）**：PITR（按时间点恢复）演练；异地备份/容灾；admin UI 备份管理面；RabbitMQ/Redis 各自的备份实现（仅文档化边界）；定时 sidecar 服务。

## 验收与停止条件

- **dev 主库全链实测**：真实执行 `backup.sh --env dev` 产 dump（sha256 留证）→ **一次性隔离 MySQL 容器**内 `restore.sh` 真实恢复 → 校验一致（恢复表数 = dump 表数 = 主库表数；关键行抽样逐值一致）。
- **prod 编排库全链实测**：全新卷起 `docker-compose.prod.yml` 栈 → 备份 → 清卷重起 → 恢复 → 26 表 + admin 行回归 + 登录 200（复用 P3 验证口径）→ down -v 零残留。
- **轮转实测**：预置过期旧 dump 文件，验证 `--keep` 轮转删除恰按预期。
- **门禁**：双端门禁实施笔前后两次持平全绿（零业务代码零用例变化）；gate-baseline 零改动自证。
- **停止条件**：① mysqldump/`--source-data` 在任一环境容器内不可用或版本不兼容，两轮排查仍失败 → 停止上报（不得换 xtrabackup 等新工具域）；② 任一环境恢复演练两轮失败 → 停止上报，不得放宽校验口径消化；③ 脚本须修改 compose 既有文件或业务代码才能工作 → 停止；④ 脚本被要求引入机器绝对路径或硬编码口令 → 停止；⑤ 边界文档化发现 data-consistency 域既有结论与 HEAD 事实冲突 → 停止上报裁决。
