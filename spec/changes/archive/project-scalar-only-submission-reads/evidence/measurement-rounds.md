# 测量与控制台原件转录（project-scalar-only-submission-reads）

> 原始控制台产物（`*.log`）与逐轮 JSON 直写仓库外 `D:\code\examOnline-measure\project-scalar-reads\`（阶段 4 已拷入本目录 `evidence/`；`*.log` 受仓库 `.gitignore` 忽略，与既有归档同例——在库件见 `evidence-sha256.txt` 清单）。本文件在仓库内转录关键控制台行；逐轮原始 JSON、机械裁决与捕获原文已直接入库（`rounds-*.json`、`bytes-*.json`、`scalar-reads-capture-cap1.json`、`adjudication.json`、`verdict-console.txt`、`seed-counts.json`）。

## 1. 阶段 1 捕获（mvn-cap1.log）

按 `evidence/PREREGISTRATION.md` §2.1 的命令模板在仓库根运行（类名以 `IT` 结尾、不进 Surefire；`-Dmeasure.rev=f6e336c -Dmeasure.label=cap1 -Dmeasure.out=D:\code\examOnline-measure\project-scalar-reads`）。输出结尾：

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 15.77 s -- in com.exam.scalar.measure.ScalarReadsAttributionMeasureIT
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

产物 `scalar-reads-capture-cap1.json`（在库）：五站点目标语句 SQL 原文、绑定参数与 ms id 逐字落盘（s1/s2 = `com.exam.submission.mapper.ExamSubmissionMapper.selectList`；s3/s4/s5 = `com.exam.grading.mapper.GradingSubmissionMapper.selectList`），并自述捕获机制（StatementHandler.prepare 拦截 + DefaultParameterHandler 同序取值，无手写近似）与护栏机制。

## 2. 阶段 2 容器（container-start / container-setup / container-stop）

`container-start.log`：

```
startedAtIso=2026-09-29T11:37:31.614Z
docker run exit=0
stdout=6c713bec2af269d5f64c611542cdd54c795f9c2089cf326bb75e578a82b568e8
ready=true pingAttempts=4
image=mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b (exit 0)
containerId=6c713bec2af269d5f64c611542cdd54c795f9c2089cf326bb75e578a82b568e8 (exit 0)
port=127.0.0.1:13411
db=scalar_reads_measure
tmpfs=/var/lib/mysql
```

`container-setup.log`：`CREATE DATABASE IF NOT EXISTS scalar_reads_measure` exit 0；`SELECT VERSION()` = `8.0.46`；整库执行仓库 `src/main/resources/schema.sql` exit=0；`SHOW CREATE TABLE exam_submissions\G` 与 `SHOW INDEX` 全文落盘——6 个既有索引（`PRIMARY(id)`、`uk_exam_student(exam_id,student_id)`、`idx_submissions_sweep(status,deadline_time)`、`idx_submissions_exam_submit(exam_id,submit_time)`、`idx_submissions_grading(exam_id,grading_status)`、`idx_submissions_republish(status,answers_missing)`），无任何候选/新增索引。

`seed.log`（三形状；全文在库 `seed-counts.json`）：

```
=== seed shape=200 at=2026-09-29T11:37:55.144Z exit=0 ok=true
counts: s1=200 e=200 f=1 all=401
lengths: answers min/max=2048/2048 paperJson min/max=20480/20480 nonNull=401
=== seed shape=1000 ... counts: s1=1000 e=1000 f=1 all=2001  lengths: answers min/max=2048/2048 paperJson min/max=20480/20480 nonNull=2001
=== seed shape=3000 ... counts: s1=3000 e=3000 f=1 all=6001  lengths: answers min/max=2048/2048 paperJson min/max=20480/20480 nonNull=6001
```

`container-stop.log`（全文）：

```
atIso=2026-09-29T11:41:11.438Z
docker rm -f scalar-reads-mysql: exit=0 stdout=scalar-reads-mysql
 stderr=
--- docker ps -a --filter name=scalar-reads-mysql (零匹配证据):
stdout=[]
stderr=[]
```

## 3. 轮次与字节（rounds-*.log / bytes-*.log）

`rounds-*.log` 行格式（样例，`rounds-s1-n200.log` 首三轮；全量在库 `rounds-*.json`）：

```
=== ROUND site=s1 n=200 arm=OLD    round=1 attempt=1 ns=12149204 exit=0 execExit=0 at=2026-09-29T11:37:59.043Z
=== ROUND site=s1 n=200 arm=PROJ   round=1 attempt=1 ns=7498327  exit=0 execExit=0 at=2026-09-29T11:37:59.201Z
=== ROUND site=s1 n=200 arm=OLDrep round=1 attempt=1 ns=11040538 exit=0 execExit=0 at=2026-09-29T11:37:59.333Z
```

`bytes-*.log`（应传字节载体；失败尝试保留不删，样例 `bytes-s1-n200.log`）：首次尝试 `exit=1`（`AS rows` 别名触发的语法错误，原样留证）后同口径成功行：

```
=== site=s1 n=200 arm=OLD    stmt=1 bytes=1018161 rows=45 exit=0
--- arm=OLD    total bytes=1018161 rows=45 side(stdoutBytes=1019106, stdoutLines=45, exit=0)
=== site=s1 n=200 arm=PROJ   stmt=1 bytes=1305    rows=45 exit=0
--- arm=PROJ   total bytes=1305    rows=45 side(stdoutBytes=1440, stdoutLines=45, exit=0)
=== site=s1 n=200 arm=OLDrep stmt=1 bytes=1018161 rows=45 exit=0
```

（`side` 为佐证口径，不参与裁决；裁决只取机械 LENGTH 载体值。五站点×三形状×三臂的全部数值在库 `bytes-n*.json` 与 `verdict-console.txt`。）

## 4. 阶段 3 护栏先红后绿（guard-red / guard-red2 / guard-green）

`guard-red.log`（第一次，取样错误原样留证）：

```
[ERROR] Tests run: 2, Failures: 1, Errors: 1, Skipped: 0 -- in com.exam.scalar.guard.ScalarProjectionGuardTest
org.opentest4j.AssertionFailedError: 列投影后目标语句返回实体的 answers/paper_json 必须为 null ==> expected: <0> but was: <2>
[ERROR] ... markAbsenceProjectsScalarColumnsOnly » DataIntegrityViolation ... NULL not allowed for column "paper_id"
```

（种子改为含 `paper_id` 后复红。）

`guard-red2.log`（第二次，旧实现下两用例均失败于同一判据）：

```
org.opentest4j.AssertionFailedError: 列投影后目标语句返回实体的 answers/paper_json 必须为 null ==> expected: <0> but was: <1>
org.opentest4j.AssertionFailedError: 列投影后目标语句返回实体的 answers/paper_json 必须为 null ==> expected: <0> but was: <2>
[ERROR]   ScalarProjectionGuardTest.markAbsenceProjectsScalarColumnsOnly:215 ...
[ERROR]   ScalarProjectionGuardTest.myExamsProjectsScalarColumnsOnly:145 ...
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

`guard-green.log`（加投影后）：

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 14.68 s -- in com.exam.scalar.guard.ScalarProjectionGuardTest
[INFO] BUILD SUCCESS
```

## 5. 门禁（gate-post-impl.log / gate-impl-commit.log）

两次均为仓库根 `JAVA_HOME=/d/develop1/jdk21 ./mvnw clean test`：

- `gate-post-impl.log`（未提交工作树：HEAD=f6e336c + 本变更 src 改动）→ `Tests run: 325, Failures: 0, Errors: 0, Skipped: 1` / `BUILD SUCCESS` / 退出码 0 / `Total time: 02:15 min`。
- `gate-impl-commit.log`（实施提交 `9182a5d` 上复跑）→ `Tests run: 325, Failures: 0, Errors: 0, Skipped: 1` / `BUILD SUCCESS` / `EXIT=0` / `Total time: 02:16 min`；启动时 `git status --porcelain` 与 `rev` 见 `gate-impl-commit.meta.txt`（仅两行未跟踪项，src 树干净）。

两次均首跑即绿，未触发已知偶发四类协议。
