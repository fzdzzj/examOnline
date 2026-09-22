# 子 agent 提示词 —— `verify-mysql8-init`（提案⑥：MySQL 8 空库初始化与存量迁移真机验证）

> 用法：整份复制发给子 agent。自包含。
> 先读 `spec/changes/verify-mysql8-init/proposal.md`、`tasks.json`、`specs/data-access/spec-delta.md`，再读仓库根 `AGENTS.md`（四条硬约定，本变更全程受约定 1 与约定 4 约束）。
> 本变更属**验证类**：证据价值高、改动面预期为零。若真跑暴露缺陷，修复是收口的一部分，不是意外。

---

## 现状

- 仓库主工作树 `D:\code\examOnline`；**你在独立工作树 `D:\code\examOnline-t6`（分支 `feature/verify-mysql8-init`）里干活**——指导 agent 已建好并 checkout（基于主仓 HEAD）。开工先 `git -C D:/code/examOnline-t6 rev-parse HEAD` 与 `git status --short` 自检；**不要改动主工作树 `D:\code\examOnline` 下的任何文件**。
- **指导 agent 现场复算的几条事实（2026-09-22 19:0x，别再信文档里的旧数字）**：
  - `src/main/resources/schema.sql` 现场计数：`CREATE TABLE IF NOT EXISTS` **27** 处、`AUTO_INCREMENT` **26** 处、`PRIMARY KEY (id)` **26** 处（⇒ 有 1 张表天然不带自增列，正常）。而 `docker/mysql/migrations/2026-W16-add-primary-keys.sql` 头注与 `spec/README.md` 写的「25 张表」是**过期副本**——**不要引用它，也不要为了对上这个数去改文档或加表**；一切以现场 `SHOW TABLES` / 当场计数为准。
  - **Docker 引擎当前是关的**（`docker info` 报 `dockerDesktopLinuxEngine ... cannot find the file specified`）→ 见环境纪律第 1 条。
  - 宿主 `6379` 被既有 Redis 占用（本变更用不到 Redis，**不要起 compose**）；dev 栈容器（`exam-mysql-master` / `exam-mysql-slave` / `exam-rabbitmq`）当前**未运行**，本变更也不需要它们。
- 阶段 17（`fix-schema-mysql-pk`，已归档）为 `src/main/resources/schema.sql` 全部自增表补了 `PRIMARY KEY (id)`，但**证据止于文本约定测试（`SchemaSqlMysqlCompatibilityTest`）+ H2 全量绿**：从未在真实空 MySQL 8 实例上执行过 `schema.sql`，`docker/mysql/migrations/2026-W16-add-primary-keys.sql` 存量迁移也从未在真实存量库上跑过（遗留 #9）。
- 你要做的就是把这两件事在真 MySQL 8 上各做一遍，把证据入档。**验证通过前，不得声称「MySQL 8 新环境可启动」已端到端验证。**

## 验收判据（不是常量）

1. **开工时**跑一次后端全量门禁（`mvn -o clean test`，**必须带 `clean`**——残留 surefire 报告会让计数虚高），把该次 `Tests run / Failures / Errors / Skipped` 连同**实际执行的命令**与当时短 revision 记录在案（写进回报）。`Skipped: 1` 是契约导出方法受 `exportContract` 开关控制，属设计使然，**不要试图消除**。门禁命令的拼装上下文见 `docs/指导Agent交接文档.md` §6.1/§6.2（特定 shell 的历史绕行办法，换环境先自检 `mvn -version` 用的哪个 JDK）。
2. **基线不绿就停下回报**（`Failures>0` / `Errors>0` / `Skipped≠1`）。
3. 收尾时再跑同一条命令：`Failures=0`、`Errors=0`、`Skipped=1`、用例总数不少于开工记录值。本变更**预期零代码改动**，此判据主要防你为修问题而破坏别的。

## 环境纪律（违反即返工）

1. **Docker 引擎必须先可用**：本机 Docker Desktop 的守护进程**时开时关**（2026-09-22 19:00 实测为关）。开工第一步跑 `docker info`；不通就启动 `D:\develop1\DockerDesktop\Docker Desktop.exe`，轮询 `docker info` 直到出现 `ServerVersion`（首次约 30–60s）。**起不来就停下回报**——不许拿 H2 结果、文本约定测试或「之前 H2 全绿」替代真机证据。
2. **绝对不动既有 dev 栈**：`exam-mysql-master`（宿主 13316）、`exam-mysql-slave`（13317）、`exam-rabbitmq`、宿主 Redis（6379）一个都不许停、不许改、不许往里写。dev 主库有真实数据与历史遗留表 `rep_test`（不要删），污染了就是事故。
3. **用一次性容器做验证**：`docker run` 起一个新的 `mysql:8.0` 容器（**与 `docker-compose.yml` 的 dev 栈同款镜像**；先 `docker image ls mysql` 确认本地有没有，缺就 pull），**新端口**（先 `docker ps --format "{{.Names}} {{.Ports}}"` + `netstat -ano | findstr LISTENING` 确认没被占；从 13318 往上找）、**新容器名**（如 `exam-mysql8-verify`）。root 口令自定（如 `verify123`），记进证据即可。
4. **验证完必须拆**：容器与卷 `docker rm -f` + `docker volume rm`，确认端口已释放再回报。残留实例会让下一次「连不上」变成互踩。
5. 容器健康等待：用 `mysqladmin ping` 或 `docker exec ... mysql -uroot -p... -e "SELECT 1"` 轮询就绪（首次初始化约 20–30s），不要 sleep 死等。

## 实施（对应 tasks.json 的三个任务）

### 任务 1：空库真机初始化

1. 起一次性 mysql:8 容器（见上）。
2. 人为执行 `src/main/resources/schema.sql`：`docker exec -i <容器> mysql -uroot -p<口令> exam_online < src/main/resources/schema.sql`（先建库；charset 按需对齐 dev 库）。
3. 验证：**全部业务表建成、执行无报错**（表数用 `SHOW TABLES` 现场数，**不要引用任何文档里的表数**）；再抽 3–5 张含 `AUTO_INCREMENT` 的表 `SHOW CREATE TABLE` 确认主键同块。
4. 记录：实际命令 + 该次原始输出（含 `SHOW TABLES` 结果）+ 当时短 revision。

### 任务 2：存量迁移真跑

1. **按迁移前形态构造缺主键的存量库**：把当前 `schema.sql` 的 `PRIMARY KEY (id)` 行剥掉生成**临时 SQL 文件**（放 `target/` 或系统临时目录，**不进仓库、不改 `schema.sql` 本身**），在同一个或重建的一次性容器里执行，得到「迁移前的旧库」。
2. 执行 `docker/mysql/migrations/2026-W16-add-primary-keys.sql`，验证缺主键的表都补上了 `PRIMARY KEY (id)`。
3. **再重复执行一次**：确认「主键已存在」类失败（1068）与脚本头注声明一致、且**业务数据未变**（前后 `SELECT COUNT(*)` 或 checksum 对比，抽 2–3 张表即可）。
4. 全程记录命令 + 原始输出。

### 任务 3：证据与登记

1. 验证记录入 `docs/mysql8-init-verification.md`（**新增**）：按「命令 + 该次原始输出 + 当时短 revision」三要素组织，含任务 1、2 的全部证据与容器清理确认。
2. **遗留 #9 的登记收口由指导 agent 做**——你只写证据文档，不改 `spec/README.md`。

## 若真跑暴露缺陷（这才是本变更最有价值的产出）

1. 修 `src/main/resources/schema.sql`（**唯一建表入口**）：保持 H2/MySQL 双兼容、`AUTO_INCREMENT` 块与 `PRIMARY KEY (id)` 同块的既有约定（AGENTS.md 约定 1）。
2. 若存量迁移脚本也有缺陷：修 `docker/mysql/migrations/2026-W16-add-primary-keys.sql`，头注释同步预期报错说明。
3. 同步既有约定测试（`SchemaSqlMysqlCompatibilityTest`）若有必要。
4. 修完必须：H2 全量门禁复跑（判据见上）+ **真机重新执行一遍任务 1/2**，两边的绿都要有才算修复。
5. **修不了的停下回报**，最多修复尝试 2 次。

## 写入边界

允许新增 / 修改：

- `docs/mysql8-init-verification.md`（新增，唯一必产文件）
- `src/main/resources/schema.sql`、`docker/mysql/migrations/**`（**仅当真跑暴露缺陷时**）
- `src/test/**`（仅当需同步既有约定测试时）

禁止其它路径。**特别禁止**：`pom.xml`、`openapi.yaml`、`frontend/**`、`spec/**`（含 `tasks.json`、`spec/README.md`——回勾与登记是指导 agent 的事）、`docker-compose.yml`、既有 dev 容器与 dev 库的任何改动。

## Commit

- 分支 `feature/verify-mysql8-init`，**你必须自己 commit**（证据文档一笔；若修了缺陷，修复一笔 + 测试同步一笔），中文描述、前缀 `docs(data-access)` / `fix(data-access)` / `test(data-access)`。
- 每次提交后立即 `git rev-parse HEAD` 与 `git status --short`；HEAD 若变 unborn，从 `.git/logs/HEAD` 取 sha 按仓库约定补 ref，**不要用 `git update-ref`**。
- **禁止 `stash` / `reset` / `checkout -- .` / `clean`**（历史事故见交接文档「已知坑」表）。

## 回报格式（按此六段，不要写散文）

1. **开工基线**：门禁命令 + 原始输出四数字 + 当时短 revision
2. **任务 1 证据**：容器起停命令、`schema.sql` 执行命令与原始输出、`SHOW TABLES` 现场计数、抽样 `SHOW CREATE TABLE`
3. **任务 2 证据**：缺主键旧库构造方式（剥离方法）、迁移执行输出、重复执行的失败行为与业务数据不变证明
4. **缺陷与修复**（若有）：暴露了什么、怎么修的、H2 复跑 + 真机重验的双绿证据；没有就写「零缺陷」
5. **环境清理确认**：容器与卷已删、端口已释放的命令与输出
6. **意外发现**：方言差异、文档与代码不一致处

## 禁止

- 禁止动既有 dev 容器 / dev 库 / 宿主 Redis / RabbitMQ；
- 禁止把「脚本已提交」当「迁移已生效」回报（AGENTS.md 约定 4）；
- 禁止引用文档里的表数、用例数当结果——一律现场跑、现场数；
- 禁止为「看起来完成」在没真跑的位置写验证结论；
- 禁止勾 `tasks.json` 或动 `spec/**`；
- 禁止残留一次性容器。
