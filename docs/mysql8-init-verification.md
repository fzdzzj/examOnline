# MySQL 8 空库初始化与存量迁移 —— 真机验证记录

> 本文档是**证据文档**（verify-mysql8-init，前后端优化组合·提案⑥，收口遗留 #9）。
> 每条结论都配「**实际执行的命令 + 该次原始输出 + 当时短 revision**」三要素。
> 文中的表数、用例数、报错码**全部是现场跑出来的数值**，不引用任何其它文档里的数字。
> 本变更**不改任何生产文件**：`src/main/resources/schema.sql` 与迁移脚本均原样通过，证据全部落在本文件。

## 元信息

| 项 | 值 |
|---|---|
| 工作树 | `D:\code\examOnline-t6`（分支 `feature/verify-mysql8-init`，独立于主工作树） |
| 短 revision | `444cf2c`（整场验证期间 HEAD 未变；`git status --short` 除本文件外全程为空） |
| 验证日期 | 2026-09-22 |
| 结论 | **零缺陷** —— `schema.sql` 与 `docker/mysql/migrations/2026-W16-add-primary-keys.sql` 均无需改动 |

## 0. 验证环境（一次性，验证后已拆除）

| 项 | 值 | 取证命令 |
|---|---|---|
| 镜像 | `mysql:8.0`（与 `docker-compose.yml` dev 栈同款，本地已存在） | `docker image ls mysql` |
| 服务端版本 | `8.0.46` | `SELECT VERSION()` |
| 字符集 / 排序 | `utf8mb4` / `utf8mb4_0900_ai_ci` | `SELECT @@character_set_server, @@collation_server` |
| 容器名 | `exam-mysql8-verify`（一次性） | `docker run --name` |
| 端口映射 | `13318:3306` | `docker run -p` |
| root 口令 | `verify123`（仅存在于该一次性容器） | `-e MYSQL_ROOT_PASSWORD` |

口径说明（为什么是 13318 / 为什么 env 里只有 13318）：dev 栈主从占 13316 / 13317，宿主既有 MySQL 实例占 3306（PID 8832），这三者本次**一个都没碰**。13318 经 `netstat -ano | findstr LISTENING` 确认为空后使用。

```console
$ docker image ls mysql
IMAGE          ID             DISK USAGE   CONTENT SIZE   EXTRA
mysql:8.0      7dcddc01f13b        1.1GB          249MB   U
mysql:8.0.36   a53272402242        839MB          189MB

$ docker info                          # 引擎就绪后的服务端信息
  Server Version: 29.6.2
  Storage Driver: overlayfs

$ docker exec -e MYSQL_PWD=verify123 exam-mysql8-verify \
    mysql -uroot -e "SELECT VERSION() AS version, @@character_set_server AS charset, @@collation_server AS collation"
version	charset	collation
8.0.46	utf8mb4	utf8mb4_0900_ai_ci
```

起停命令（完整形态）：

```console
$ docker run -d --name exam-mysql8-verify \
    -e MYSQL_ROOT_PASSWORD=verify123 -e MYSQL_DATABASE=exam_online -e TZ=Asia/Shanghai \
    -p 13318:3306 mysql:8.0
e2a28837aa37cd11b497cd260555b8b6f9d2d957d0fdb152921a97aa3419040a

# 就绪等待（不用 sleep 死等，轮询 SQL）
$ for i in $(seq 1 30); do
    docker exec -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot -e "SELECT 1" >/dev/null 2>&1 \
      && { echo "MYSQL READY after ~$((i*5))s"; break; }; sleep 5; done
MYSQL READY after ~10s
```

### 0.1 环境前置障碍（与验证结论无关，但影响复跑，如实记录）

本机 Docker 引擎初始为关；本会话沙箱环境下**无法由 agent 自行拉起 Docker Desktop**，两个叠加原因都在日志里有原文：

1. `C:\Users\fzdzzj\AppData\Local\Docker\log\host\com.docker.backend.exe.log`：
   `backend crashed ... getting default data folder: unable to get 'ProgramData'`
   —— 沙箱注入的子进程环境缺 `PROGRAMDATA` / `APPDATA`，backend 启动早期即崩。
2. 补齐这两个变量后，backend 继续推进，但执行 `wsl.exe --version` 被**沙箱程序黑名单**拒绝：
   `[com.docker.backend.exe.wslexec][E] c:\windows\system32\wsl.exe --version failed: fork/exec C:\Windows\System32\wsl.exe: Access is denied.`
   → `engine linux/wsl failed to start: checking preconditions: checking WSL version`

最终由**人工在交互桌面启动 Docker Desktop** 使引擎就绪（`docker info` 出现 `Server Version`）。这不是本变更的缺陷，但后续任何「真机验证类」变更应把这条前置写进提示词，避免重复踩坑。

---

## 1. 开工基线门禁（判据 1 / 2）

命令（与本仓库既有的 launcher 直调方式一致；`.mvn/maven.config` 自动带 `-s maven-settings.xml`）：

```bash
cd D:/code/examOnline-t6
'D:\develop\jdk177\bin\java.exe' \
  -classpath 'D:\develop\Maven\apache-maven-3.9.4\boot\plexus-classworlds-2.7.0.jar' \
  -Dclassworlds.conf='D:\develop\Maven\apache-maven-3.9.4\bin\m2.conf' \
  -Dmaven.home='D:\develop\Maven\apache-maven-3.9.4' \
  -Dmaven.multiModuleProjectDirectory='D:\code\examOnline-t6' \
  org.codehaus.plexus.classworlds.launcher.Launcher -o clean test
```

原始输出（短 revision `444cf2c`）：

```console
[WARNING] Tests run: 290, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
[INFO] Total time:  04:31 min
```

- `Skipped: 1` 来自 `com.exam.support.OpenApiContractTest`（契约导出方法受 `exportContract` 开关控制），属设计使然，未做任何消除动作。
- 基线绿 → 继续执行；未触发「基线不绿就停下回报」。

---

## 2. 任务 1：空库真机初始化

### 2.1 执行 `schema.sql`

```console
$ docker exec -i -e MYSQL_PWD=verify123 exam-mysql8-verify \
    mysql -uroot exam_online < src/main/resources/schema.sql
EXIT=0
```

**原始输出为空**（上面两行就是全部输出：命令回显 + `EXIT=0`）。即 26 条 `CREATE TABLE` 全部执行成功、**零报错**（对照第 3.1 节：剥离主键的旧形态会在第一张表就报 1075）。

短 revision：`444cf2c`。

### 2.2 现场计数（不引用任何文档里的表数）

```console
$ docker exec -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot -e "SHOW TABLES FROM exam_online;"
Tables_in_exam_online
audit_log
classes
exam_absence
exam_behavior_logs
exam_candidates
exam_dlq_messages
exam_snapshots
exam_submissions
exam_submit_dedups
exams
invite_codes
paper_questions
paper_snapshots
papers
permissions
question_tags
questions
role_permissions
roles
score_audit_logs
score_review
subjective_grades
tags
user_class
user_roles
users

$ docker exec -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot -N \
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='exam_online';"
26
```

### 2.3 声明 vs 实建：集合差与主键完整性

```console
--- A) 声明 vs 实建 集合差（两侧都应为空）---
声明张数: 26  实建张数: 26
声明有而实建无:
实建有而声明无:

--- B) 无 PRIMARY KEY 的表（应为空）---
（空）

--- C) 有 AUTO_INCREMENT 列却不在任何主键里的表（应为空）---
（空）

--- D) AUTO_INCREMENT 列总数（现场计数）---
26
```

- 声明侧取自 `grep -oiE 'CREATE TABLE IF NOT EXISTS [a-z_]+' src/main/resources/schema.sql`，实建侧取自 `information_schema.tables`，两边都用 `sort -u` 后 `comm` 比对，**双向差集为空**。
- B/C 两条是「AUTO_INCREMENT 必须落在某个 key 上」在真机上的直接判据，两条都空 ⇒ 26 张表全部有主键、26 个自增列全部被主键覆盖。

### 2.4 抽样 `SHOW CREATE TABLE`（确认自增列与主键同块）

```console
$ SHOW CREATE TABLE exam_online.<t>;   # 摘 id 列行与主键行
users            ->  `id` bigint NOT NULL AUTO_INCREMENT, / PRIMARY KEY (`id`),
exam_submissions ->  `id` bigint NOT NULL AUTO_INCREMENT, / PRIMARY KEY (`id`),
subjective_grades->  `id` bigint NOT NULL AUTO_INCREMENT, / PRIMARY KEY (`id`),
user_class       ->  `id` bigint NOT NULL AUTO_INCREMENT, / PRIMARY KEY (`id`),
audit_log        ->  `id` bigint NOT NULL AUTO_INCREMENT, / PRIMARY KEY (`id`),
```

**任务 1 结论：真实空 MySQL 8.0.46 上执行唯一建表入口 `schema.sql`，26 张业务表全部建成、零报错，自增列全部有主键。**对应 spec-delta 场景「真机空库初始化实测」。

---

## 3. 任务 2：存量迁移真跑

### 3.1 先证前提：只删主键行的「旧形态」在 MySQL 8 上根本建不起来

构造方式：`awk` 剔除 `PRIMARY KEY (id),` 行，生成 `legacy-stripped.sql`（临时文件，放仓库外 `D:/verify-logs/`，**未改动 `schema.sql`**）。

```console
$ awk '!/^[[:space:]]*PRIMARY KEY \(id\),[[:space:]]*$/' src/main/resources/schema.sql > D:/verify-logs/legacy-stripped.sql
剥离后残留 PRIMARY KEY (id) 行数: 0

$ docker exec -i -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot exam_legacy_raw < legacy-stripped.sql
ERROR 1075 (42000) at line 7: Incorrect table definition; there can be only one auto column and it must be defined as a key
EXIT=1

$ SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='exam_legacy_raw';
0
```

**建表数 0**：脚本停在第一张表（`users`，`schema.sql` 第 7 行）——这就是 W16 之前「空库一条 CREATE TABLE 都执行不了」的真机复现，与迁移脚本头注的前提完全一致。

由此得到一条对任务 2 构造方式至关重要的结论：**「自增列 + 完全无索引」的旧库状态在 MySQL 8 上不是可存在状态**（MySQL 要求自增列必须是某个 key 的第一列）。因此真实存量旧库只可能是「自增列挂着一个非唯一二级索引、但缺主键」这一形态，本次据此构造。

### 3.2 构造可迁移的真实旧库

构造方式：`awk` 把主键行替换为普通二级索引 `KEY ix_legacy_id (id),`（临时文件 `D:/verify-logs/legacy-keys.sql`，同样不进仓库）。

```console
$ awk '{ if ($0 ~ /^[[:space:]]*PRIMARY KEY \(id\),[[:space:]]*$/) { print "    KEY ix_legacy_id (id)," } else print }' \
      src/main/resources/schema.sql > D:/verify-logs/legacy-keys.sql
替换出的 ix_legacy_id 行数: 26
残留 PRIMARY KEY (id) 行数: 0

$ git status --short          # 源文件零改动（无输出）

$ docker exec -i -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot exam_legacy < legacy-keys.sql
EXIT=0

$ 旧库表数 / 有主键的表 / 自增列数
tables_total	tables_with_pk	auto_cols
26	            0	            26

$ SHOW CREATE TABLE exam_legacy.users\G
  `id` bigint NOT NULL AUTO_INCREMENT,
  UNIQUE KEY `uk_users_username` (`username`),
  KEY `ix_legacy_id` (`id`)
```

旧库形态：**26 张表、0 张有主键、26 个自增列**，`users` 上没有任何主键——正是迁移脚本要处理的对象。

### 3.3 迁移前基线（数据不变性证明的基准）

```console
$ INSERT INTO users (username,password,name) VALUES ('legacy_a','x','A'),('legacy_b','x','B'),('legacy_c','x','C');
$ INSERT INTO tags (name,type) VALUES ('数学','SUBJECT'),('难度易','DIFFICULTY');
$ INSERT INTO exam_absence (exam_id,student_id,status) VALUES (9001,1001,0),(9001,1002,0),(9002,1003,0);

$ SELECT ... COUNT(*) + CHECKSUM TABLE exam_legacy.users, exam_legacy.tags, exam_legacy.exam_absence;
t	n
users	        3
tags	        2
exam_absence	3
Table	              Checksum
exam_legacy.users	     3830291561
exam_legacy.tags	     2786439583
exam_legacy.exam_absence 4047315425
```

### 3.4 首次执行迁移（判据：补上主键、零报错、数据不变）

```console
$ docker exec -i -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot exam_legacy \
    < docker/mysql/migrations/2026-W16-add-primary-keys.sql
EXIT=0                      # 25 条 ALTER 全部成功，无任何 ERROR

$ 迁移后：表数 / 有主键的表
tables_total	tables_with_pk
26	            25

$ 无主键的表
still_no_pk
audit_log

$ 行数 + CHECKSUM（与 3.3 逐字一致）
users 3 / tags 2 / exam_absence 3     —— 3830291561 / 2786439583 / 4047315425

$ SHOW CREATE TABLE exam_legacy.users\G
  `id` bigint NOT NULL AUTO_INCREMENT,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_username` (`username`),
  KEY `ix_legacy_id` (`id`)
```

25 张缺主键的表全部补上 `PRIMARY KEY (id)`，业务数据 checksum 前后完全一致。（`audit_log` 见 3.7。）

### 3.5 重复执行（判据：与头注声明的失败行为一致、且不改业务数据）

第一次重复执行用的是**默认客户端**：

```console
$ docker exec -i -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot exam_legacy \
    < docker/mysql/migrations/2026-W16-add-primary-keys.sql
ERROR 1068 (42000) at line 18: Multiple primary key defined
EXIT=1
```

即得到头注声明的 1068，但**默认客户端在首个错误处整体中止**，后面 24 条 ALTER 根本没执行。要复现头注所说的「逐条执行、跳过失败语句」，必须带 `--force`：

```console
$ docker exec -i -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot --force exam_legacy \
    < docker/mysql/migrations/2026-W16-add-primary-keys.sql
ERROR 1068 (42000) at line 18: Multiple primary key defined
ERROR 1068 (42000) at line 19: Multiple primary key defined
...                                   # 18→42 连续 25 行，每张表一条
ERROR 1068 (42000) at line 42: Multiple primary key defined
EXIT=0

同型报错共 25 条（grep -c 'ERROR 1068'）

$ CHECKSUM TABLE exam_legacy.users, exam_legacy.tags, exam_legacy.exam_absence;
exam_legacy.users	       3830291561
exam_legacy.tags	       2786439583
exam_legacy.exam_absence   4047315425
```

**25 条 1068 = 脚本里 25 条 `ALTER TABLE ... ADD PRIMARY KEY (id)` 一一对应**，与头注「表已有主键时对应 ALTER 会报 Multiple primary key defined (errno 1068)」逐条吻合；三轮操作（首跑 / 默认重跑 / `--force` 重跑）之后 checksum 三个值全程不变 ⇒ **不改业务数据**。

> 操作级发现（未改脚本，见第 4 节）：头注的「逐条执行、跳过失败语句即可」在实际操作中需要配 `--force`，否则默认客户端会在第一条 1068 处中止，操作者可能误读为「迁移失败」。

### 3.6 头注另一分支：表不存在（1146）

```console
$ docker exec -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot -e "CREATE DATABASE IF NOT EXISTS exam_legacy_empty DEFAULT CHARACTER SET utf8mb4;"
$ docker exec -i -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot --force exam_legacy_empty \
    < docker/mysql/migrations/2026-W16-add-primary-keys.sql | head -4
ERROR 1146 (42S02) at line 18: Table 'exam_legacy_empty.users' doesn't exist
ERROR 1146 (42S02) at line 19: Table 'exam_legacy_empty.roles' doesn't exist
ERROR 1146 (42S02) at line 20: Table 'exam_legacy_empty.permissions' doesn't exist
ERROR 1146 (42S02) at line 21: Table 'exam_legacy_empty.user_roles' doesn't exist

同型报错共 25 条（grep -c 'ERROR 1146'）
```

与头注「表不存在时会报 Table 'xxx' doesn't exist (errno 1146)」一致。

### 3.7 为什么迁移脚本只列 25 张表，而 `schema.sql` 现在有 26 张自增表

迁移脚本的 `ALTER` 清单共 25 条，缺的那张是 `audit_log`。真机验证它由**自己的** W16 迁移建表且**自带主键**：

```console
$ docker exec -i -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot exam_legacy_audit \
    < docker/mysql/migrations/2026-W16-add-audit-log.sql
EXIT=0

$ 该库表数 / 有主键的表
tables_total	tables_with_pk
1	            1              # audit_log 建出来就带 PRIMARY KEY
```

⇒ **「25」不是过期数字，而是该脚本的作用范围**：25（本脚本）+ 1（`audit_log`，由 `2026-W16-add-audit-log.sql` 带主键建出）= 26，与 2.2 现场点出的 26 张表、2.3 的 26 个自增列完全对得上。**非缺陷，无需改脚本。**

**任务 2 结论：真实 MySQL 8 存量库上首跑零报错补全主键，重复执行的 1068/1146 行为与头注逐条一致，三轮操作业务数据 checksum 不变。**对应 spec-delta 场景「存量库可补主键」「存量迁移真机实测」。

---

## 4. 缺陷与修复

**零缺陷。本变更未修改任何生产文件**（`schema.sql`、迁移脚本、`src/test/**` 均未动），仅新增本证据文档。

真跑暴露了两项**操作级 / 表述级**发现，均不足以构成脚本缺陷，故**未擅自改动仓库文件**，在此登记供收口决策：

1. **`--force` 未写进操作说明**（3.5）：头注说「逐条执行、跳过失败语句即可」，但 `mysql` 客户端默认遇错即停（实测 `EXIT=1` 且只打 1 条 1068）。若在真实存量库上重复执行，操作者看到的是「一条 1068 + 非零退出码」，容易误判为迁移失败。**建议**：在头注补一句「重复执行请带 `--force`」。
2. **冗余二级索引不会被自动清除**（3.4）：`ALTER TABLE ... ADD PRIMARY KEY (id)` 之后，`id` 上原有的普通索引仍在（`SHOW CREATE TABLE` 里 `PRIMARY KEY (id)` 与 `KEY ix_legacy_id (id)` 并存）。这是本次构造旧库的产物，真实旧库无此索引，故**不构成生产问题**；仅提示真实环境若存在同类冗余索引需人工评估。

两项都属「可选改进」，故本变更保持零代码改动。若指导 agent 决定采纳第 1 条，那是一笔独立的小改（头注 + 复跑门禁），不是本变更的收口阻塞项。

---

## 5. 环境清理确认

```console
$ docker ps -a --format "{{.Names}}\t{{.Status}}"      # 清理前：dev 栈全部 Exited，未被本次启动过
exam-grafana	      Exited (255) 5 days ago
exam-mysql-master	  Exited (137) 3 hours ago
exam-mysql-slave	  Exited (137) 3 hours ago
exam-mysql8-verify	  Up 4 minutes                    # ← 只有这一个在跑
exam-prometheus	      Exited (255) 5 days ago
exam-rabbitmq	      Exited (137) 3 hours ago
exam-redis	          Exited (255) 9 hours ago
（其余 myrag-* / sport-verify-* 均为本次未触碰的既有容器）

$ docker rm -fv exam-mysql8-verify
exam-mysql8-verify

$ docker ps -a --format "{{.Names}}\t{{.Status}}"      # 清理后：验证容器已消失，dev 栈状态逐行不变
（exam-mysql8-verify 已不在列表中；exam-mysql-master/slave/rabbitmq 仍为 Exited (137) 3 hours ago）

$ netstat -ano | findstr LISTENING | findstr :13318
（空）                                                  # 端口已释放

$ 悬空卷创建时间（确认无本次窗口内新建的残留卷）
2026-08-06T11:44:45Z  727536808d5c244fdc894ca36b45c506866f3aaf0842921c1c14b999c547611b
2026-09-12T01:09:58Z  af0c85989b563f9f82c1ad4021ff00e2317e8c8d4e29b7f735c3bcc55b489e89
2026-09-12T02:50:23Z  81589875c767754cf8948c1871889497c4d6c523074a583eb5a57715ca30dc86
2026-09-12T04:03:38Z  fbd2d2c2ccebfd39c3fe7faabae5e3224e160eea2fda53c3a34425c3095a882c
2026-09-12T09:55:38Z  aaa4fb2c39e3a9a3775969e07fe44fddc64280fbcf5663a59eee05d88185a70e
2026-09-20T05:12:18Z  1689f2320ca7e620310105cf51ecc0943a3fee482b80d5f2e50b2f30bb1d7e69
2026-09-20T05:19:10Z  50405448ba4d55ceee9a49e43c744fa3c59b271b23db17c3d1fb949a461076e5
2026-09-20T05:26:07Z  2ae23e2c2bf43b2a5522245ed0cb3c47fa3fd7ce758fd085d05d2a2bc53c2e2f
2026-09-20T05:43:33Z  cb060dc744cff2197bba4ebfd5c93b8a0f975ac955b5ad2e1e83c0e172b84cc7
2026-09-21T06:04:01Z  7913cd5c092bafa46395e84b6f50cfbcbb1e785f8f759948f1352351e51c54e4
（全部早于本次验证窗口；本次容器的匿名卷已随 `rm -fv` 一并删除）
```

未触碰项清单：`exam-mysql-master`（13316）、`exam-mysql-slave`（13317）、`exam-rabbitmq`、宿主 Redis（6379）、宿主既有 MySQL（3306，PID 8832）——全程零操作。

---

## 6. 收尾门禁复跑（判据 3）

命令与第 1 节完全相同，工作树未产生任何代码改动：

```console
[WARNING] Tests run: 290, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
[INFO] Total time:  06:37 min
```

`Failures=0` / `Errors=0` / `Skipped=1`，用例总数 290 **不低于**开工基线 290。短 revision 仍为 `444cf2c`（本文件尚未提交；提交后本期 HEAD 前进，门禁结论对应提交前的代码状态）。

---

## 7. 意外发现（供后续变更参考）

1. **`grep -c 'CREATE TABLE IF NOT EXISTS' schema.sql` 会多算 1**：`src/main/resources/schema.sql:401-402` 的一句注释里含该短语，故 grep 报 27 而实际建表语句是 **26** 条。任何「拿 grep 计数当表数」的判据都会带上这个 +1 偏差；现场计数应以 `information_schema.tables` 为准（2.2 实测 26）。
2. **`AUTO_INCREMENT` 计数与表数是同一个数（26）**：`schema.sql` 里每张表都有自增 `id`，不存在「天然不带自增列的表」。
3. **「自增列无索引」在 MySQL 8 上是不可存在状态**（3.1 实测 1075）：这既解释了 W16 修复的必要性，也说明「构造迁移前旧库」不能只删主键行——必须给 `id` 留一个 key（本次留二级索引），否则构造不出可迁移的旧库，测试会变成空转。
4. **默认 `mysql` 客户端遇错即停**（3.5）：批量 DDL 迁移脚本要「跑完全部语句」，必须显式 `--force`；`EXIT=1` 也不代表「一条都没成功」。判读迁移脚本的输出必须看逐条 error 行，不能只看退出码。
5. **沙箱环境下 Docker Desktop 无法由 agent 自启**（0.1）：`wsl.exe` 在程序黑名单里，且子进程环境缺 `PROGRAMDATA`/`APPDATA`。真机验证类变更需人工先把引擎起好。
6. **文档中的「25 张表」口径**（3.7）：迁移脚本头注与 `spec/README.md` 的「25 张表」并非过期副本，而是「本脚本作用范围 = 26 − `audit_log`」；`audit_log` 的主键由 `2026-W16-add-audit-log.sql` 内联提供，本脚本刻意不重复添加。

---

## 8. 复跑步骤（可机械重放）

```bash
# 0) 前置：Docker 引擎已就绪（docker info 有 Server Version）
# 1) 一次性实例
docker run -d --name exam-mysql8-verify -e MYSQL_ROOT_PASSWORD=verify123 \
  -e MYSQL_DATABASE=exam_online -p 13318:3306 mysql:8.0
# 2) 任务 1
docker exec -i -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot exam_online \
  < src/main/resources/schema.sql
docker exec -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot -e "SHOW TABLES FROM exam_online;"
# 3) 任务 2：先证前提（应报 1075），再造旧库、跑迁移
awk '!/^[[:space:]]*PRIMARY KEY \(id\),[[:space:]]*$/' src/main/resources/schema.sql > /tmp/legacy-stripped.sql
awk '{ if ($0 ~ /^[[:space:]]*PRIMARY KEY \(id\),[[:space:]]*$/) { print "    KEY ix_legacy_id (id)," } else print }' \
    src/main/resources/schema.sql > /tmp/legacy-keys.sql
docker exec -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot \
  -e "CREATE DATABASE exam_legacy DEFAULT CHARACTER SET utf8mb4;"
docker exec -i -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot exam_legacy < /tmp/legacy-keys.sql
docker exec -i -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot exam_legacy \
  < docker/mysql/migrations/2026-W16-add-primary-keys.sql                       # 期望 EXIT=0、零报错
docker exec -i -e MYSQL_PWD=verify123 exam-mysql8-verify mysql -uroot --force exam_legacy \
  < docker/mysql/migrations/2026-W16-add-primary-keys.sql                       # 期望 25 条 1068
# 4) 拆除
docker rm -fv exam-mysql8-verify
```
