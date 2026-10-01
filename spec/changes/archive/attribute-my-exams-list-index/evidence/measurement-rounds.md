# 测量与控制台原件转录（attribute-my-exams-list-index）

> 原始测量产物直写仓库外 `D:\code\examOnline-measure\my-exams-index\stage3\raw\`；
> 逐轮原始数据 `rounds.json`（含全部 70 轮 read/write 原始 EXPLAIN/EXPLAIN ANALYZE 文本）、
> 容器日志 `container-start.log`、`container-setup.log`、`container-stop.log` 已拷入本目录 `evidence/`。
> 证据哈希清单见 `evidence-sha256.txt`（`sha256sum -c` 须 rc=0）。
>
> **返修登记（2026-09-30 第 1 笔）**：本文按冻结件 §6 解析规则重算后重写。旧版缺陷有二：
> rowsSum 取的是 EXPLAIN ANALYZE 行内**第一个** `rows=`（属 cost 段，违背冻结规则"只认 actual 段，
> 不认 cost 段"），且未按字面量反斜杠 n 切分节点导致只有根节点行被解析。本版 rowsSum 与 B2 比值两列
> 已换成重算值；补齐 §7 要求的 EXPLAIN 原文转录；容器 ID 更正为权威值；撤销不可证实的
> "销毁前后 docker ps 逐字一致"论断；登记被废弃的首轮尝试。
> actualTimeMs、elapsedUs、type、key 各列与返修前逐位相同（全部 70 轮未重测，
> rounds.json sha256 = `b423138fa79811419e3fc1a133da25ea16093424e7ff19e40ffd9c5eb25b0931` 未变）。

## 1. 阶段 3 容器环境与生命周期（全文留证）

- **容器配方**：
  - 镜像：`mysql:8.0`（本地镜像 ID `7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`，digest `mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`）
  - 启动命令：`docker run -d --name my-exams-index-mysql -p 127.0.0.1:13320:3306 -e MYSQL_ROOT_PASSWORD=*** --tmpfs /var/lib/mysql mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci`（口令转写为 `***`）
  - 端口：`127.0.0.1:13320`（避让清单核验通过）
  - 数据库名：`my_exams_index_measure`
  - 版本：`8.0.46`（`SELECT VERSION()` 确认）
- **容器 ID（返修更正）**：`9f7917d23e05626f4091c54111cbadc425066c07c43fca89e7330c8f57c6b9f5`
  - 依据一：`rounds.json` 全部 70 轮读路径记录与 40 条写路径记录的 `containerId` 字段经脚本逐条提取，
    110/110 一致等于该值，无第二值。
  - 依据二：`container-start.log` 第 4 次启动 `startedAtIso=2026-09-30T14:01:11.895Z`，
    `containerId=9f7917d23e05…`。前 3 次启动依次为 `c50035da…`（13:57:18.844Z，版本探测失败）、
    `a4d03617…`（13:57:57.480Z）、`c1bf16be…`（13:58:38.094Z，承载被废弃的首轮尝试，见 §2）。
- **建表验证**：
  - 执行 `src/main/resources/schema.sql` 退出码 0。
  - `CREATE TABLE IF NOT EXISTS` 匹配数 27（含 1 条历史注释说明，实建 26 表）。
  - 信息库表统计数：26 表。
- **销毁与隔离验证（返修改写）**：
  - `docker rm -f my-exams-index-mysql` 退出码 0。
  - 零匹配核对：`docker ps -a --filter name=my-exams-index-mysql` 仅输出表头，匹配数 0（`container-stop.log`）。
  - 库外 `docker-ps-before.txt` / `docker-ps-after.txt` 的 mtime（`ls --full-time` 实测）：
    before = 2026-09-30 22:07:03.281 +08:00，after = 2026-09-30 22:06:47.351 +08:00；
    销毁时刻（`container-stop.log` 首行 Stopping）= 2026-09-30T14:06:45.797Z（即本地 22:06:45.797，
    与 `rounds.json` mtime 22:06:45.796 同秒）。**before 的 mtime 晚于 after 与销毁时刻，
    故该对文件不构成事前基线**——原"销毁前后 `docker ps` 输出逐字一致（`sport-verify-*` 5 容器状态
    端口分毫不差）"的论断据此撤销。
  - "其余项目容器未被本次测量触碰"的结论改由指导 agent 于已提交状态 `83459aa` 独立执行
    `docker ps -a` 复核成立：5 只 `sport-verify-*` 容器均 Up、端口未变（当次实测记录，非本文作者复跑）。

## 2. 被废弃的首轮尝试（登记；返修新增）

- `container-setup.log` 内存在**两套 n1 造数+写路径序列**：第一套在 container-start.log 第 3 次启动的
  容器（`c1bf16be5479a9e685777fc3f44eb75b4dda371f9a6e3e139a0a392023787880`，13:58:38.094Z）上执行；
  第二套（本卡保留数据）在第 4 次启动的容器
  （`9f7917d23e05626f4091c54111cbadc425066c07c43fca89e7330c8f57c6b9f5`，14:01:11.895Z）上执行，之后才有 n2。
- 第一套**整轮作废**，作废原因是解析器缺陷：当时产出裁决的脚本违背冻结 §6（取每行第一个 `rows=`，
  命中的是 cost 段；且未按字面量反斜杠 n 切分节点，仅根节点行被解析），首轮数据在缺陷解析器下
  不具备可信裁决形态。首轮的读路径数值未进入本卡任何判据，亦未与第二套数据做任何择优。
- 第一套 n1 热写路径 elapsed_us（`container-setup.log` 原文转录）：

| 轮次 | A0 (μs) | A0rep (μs) | A1 (μs) | A2 (μs) |
|---|---|---|---|---|
| r1 | 36,982 | 24,464 | 26,794 | 48,914 |
| r2 | 159,154 | 55,882 | 54,282 | 54,604 |
| r3 | 83,117 | 68,707 | 34,308 | 37,691 |
| r4 | 45,557 | 30,262 | 24,566 | 24,227 |
| r5 | 47,074 | 55,549 | 25,274 | 26,376 |

- 指导 agent 重算结论：首套 A0∪A0rep 写带上界 = 159,154 μs → 判定阈值 ×1.05 = 167,111.7 μs；
  A1 max = 54,282 μs、A2 max = 54,604 μs，均低于阈值 ⇒ **B4 在首套数据下同样通过**。
  作废未产生择优效果（无挑轮收益）。

## 3. 逐形状有效性验证（B5① 造数即实）

| 形状 | 指标 | 实测值 | 设计值 | 判定 |
|---|---|---|---|---|
| **n1** | exam_submissions 行数 | 100,500 | 100,500（10k 生 × 10 份 + 500 热写池） | PASS |
| n1 | paper_json 字符总长 | 411,648,000 | 411,648,000 (100,500 × 4096) | PASS |
| n1 | answers 非空行数 | 40,000 | 40,000 (25% status=2 + 15% status=3) | PASS |
| n1 | answers 字符总长 | 81,920,000 | 81,920,000 (40,000 × 2048) | PASS |
| n1 | exam_candidates 行数 | 5,000 | 5,000 (50 场 × 100 人) | PASS |
| n1 | 目标学生答卷行数 (id=965000000001) | 10 | 10 | PASS |
| n1 | 目标学生补考候选行数 | 1 | 1 | PASS |
| **n2** | exam_submissions 行数 | 400,000 | 400,000（40k 生 × 10 份，覆盖 500 热写池） | PASS |
| n2 | paper_json 字符总长 | 1,638,400,000 | 1,638,400,000 (400,000 × 4096) | PASS |
| n2 | answers 非空行数 | 160,000 | 160,000 (25% status=2 + 15% status=3) | PASS |
| n2 | answers 字符总长 | 327,680,000 | 327,680,000 (160,000 × 2048) | PASS |
| n2 | exam_candidates 行数 | 20,000 | 20,000 (200 场 × 100 人) | PASS |
| n2 | 目标学生答卷行数 (id=965000000001) | 10 | 10 | PASS |
| n2 | 目标学生补考候选行数 | 1 | 1 | PASS |

B5 其余项：
- B5②：每次臂切换后 `ANALYZE TABLE exam_submissions; ANALYZE TABLE exam_candidates;` 退出码全为 0。
- B5③：传统 EXPLAIN 的 type / key / rows 全轮次均可机械解析。
- B5④：开测前实跑 `MyExamsSqlCaptureIT`，捕获 JSON 中 T1 与 T2 语句原文与 `PREREGISTRATION.md §0` 逐字比对完全一致。

## 4. 逐形状逐臂测量数据表

**列口径（与冻结件 §5/§6 一致）**：
- rowsSum = 该轮 EXPLAIN ANALYZE **各节点 actual rows 机械求和**；解析规则（冻结 §6）：对每行匹配
  `/actual time=[0-9.]+\.\.[0-9.]+ rows=([0-9]+)/` 取捕获值求和，只认 actual 段，不认 cost 段。
- B2 扫描行数比 = rowsSum(基线臂) / rowsSum(候选臂)，逐轮计算，判据为全部 5 轮 ≥ 5.0。
- 推导核对（机械提取自 rounds.json 原文）：n1 A0 的 100,510 = Filter 节点 actual rows 10 +
  Table scan 节点 actual rows 100,500；n2 A0 的 400,010 = 10 + 400,000；
  C0 的 2 = Filter 节点 actual rows 1 + Covering index skip scan 节点 actual rows 1。
- 已知口径特征：逐节点求和对"同一行途经多个节点"存在重复计数，对 1 行结果最明显
  （C0 的目标行 1 行在两个节点各计一次 → 2）。因此 C1 对 C0 的比值 2 受该口径抬高，
  **C1 不采纳不依赖 B2**——其 B1 对称子门（C0 type=ALL 且 key=NULL 且最快轮 ≥ 5 ms）已先失败
  （C0 实为 type=range / uk_candidate_exam_student，最快轮 0.11 / 0.338 ms，见 §6）。

### 4.1 n1 读路径（5 轮 × 7 臂逐轮轮转）

- 噪声带上界：`band(A0 ∪ A0rep) = 333 ms`；`band(C0 ∪ C0rep) = 0.198 ms`。

| 臂 | 目标 | type | key | actualTimeMs (r1..r5) | rowsSum (r1..r5) | B2 比 = rowsSum(基线)/rowsSum(候选) (r1..r5) |
|---|---|---|---|---|---|---|
| A0 | T1 | ALL | NULL | [260, 303, 304, 275, 265] | [100510, 100510, 100510, 100510, 100510] | 基线 (1.0) |
| A0rep | T1 | ALL | NULL | [333, 267, 272, 229, 311] | [100510, 100510, 100510, 100510, 100510] | 基线 (1.0) |
| **A1** | T1 | ref | idx_submissions_student | **[0.072, 0.0602, 0.0874, 0.144, 0.124]** | **[10, 10, 10, 10, 10]** | **[10051, 10051, 10051, 10051, 10051]** |
| A2 | T1 | ref | idx_submissions_student_list | [0.0177, 0.0216, 0.0243, 0.0343, 0.0346] | [10, 10, 10, 10, 10] | [10051, 10051, 10051, 10051, 10051] |
| C0 | T2 | range | uk_candidate_exam_student | [0.188, 0.11, 0.183, 0.174, 0.168] | [2, 2, 2, 2, 2] | 基线 (1.0) |
| C0rep | T2 | range | uk_candidate_exam_student | [0.103, 0.124, 0.167, 0.198, 0.191] | [2, 2, 2, 2, 2] | 基线 (1.0) |
| C1 | T2 | ref | idx_candidates_student | [0.0135, 0.0165, 0.0191, 0.0272, 0.0193] | [1, 1, 1, 1, 1] | [2, 2, 2, 2, 2] |

### 4.2 n1 热写路径（5 轮 × 4 臂，100 INSERT + 100 UPDATE）

- 噪声带：`max(A0 ∪ A0rep) = 145,129 μs`；写放大判定阈值（×1.05）= `152,385.45 μs`。

| 轮次 | A0 (μs) | A0rep (μs) | A1 (μs) | A2 (μs) | 阈值 (μs) | A1 判定 | A2 判定 |
|---|---|---|---|---|---|---|---|
| r1 | 70,904 | 31,681 | 22,038 | 37,959 | 152,385.45 | PASS | PASS |
| r2 | 117,638 | 42,266 | 40,885 | 31,344 | 152,385.45 | PASS | PASS |
| r3 | 145,129 | 45,693 | 35,545 | 21,882 | 152,385.45 | PASS | PASS |
| r4 | 37,839 | 30,459 | 31,159 | 39,624 | 152,385.45 | PASS | PASS |
| r5 | 33,877 | 25,298 | 35,209 | 28,091 | 152,385.45 | PASS | PASS |

### 4.3 n2 读路径（5 轮 × 7 臂逐轮轮转）

- 噪声带上界：`band(A0 ∪ A0rep) = 1442 ms`；`band(C0 ∪ C0rep) = 0.567 ms`。

| 臂 | 目标 | type | key | actualTimeMs (r1..r5) | rowsSum (r1..r5) | B2 比 = rowsSum(基线)/rowsSum(候选) (r1..r5) |
|---|---|---|---|---|---|---|
| A0 | T1 | ALL | NULL | [1119, 1228, 1295, 1327, 1442] | [400010, 400010, 400010, 400010, 400010] | 基线 (1.0) |
| A0rep | T1 | ALL | NULL | [1099, 1103, 1132, 1152, 1152] | [400010, 400010, 400010, 400010, 400010] | 基线 (1.0) |
| **A1** | T1 | ref | idx_submissions_student | **[0.15, 0.0968, 0.0932, 0.162, 0.0761]** | **[10, 10, 10, 10, 10]** | **[40001, 40001, 40001, 40001, 40001]** |
| A2 | T1 | ref | idx_submissions_student_list | [0.0184, 0.0203, 0.0355, 0.0408, 0.0223] | [10, 10, 10, 10, 10] | [40001, 40001, 40001, 40001, 40001] |
| C0 | T2 | range | uk_candidate_exam_student | [0.338, 0.486, 0.482, 0.486, 0.461] | [2, 2, 2, 2, 2] | 基线 (1.0) |
| C0rep | T2 | range | uk_candidate_exam_student | [0.404, 0.524, 0.371, 0.567, 0.393] | [2, 2, 2, 2, 2] | 基线 (1.0) |
| C1 | T2 | ref | idx_candidates_student | [0.0134, 0.0178, 0.0194, 0.0197, 0.0149] | [1, 1, 1, 1, 1] | [2, 2, 2, 2, 2] |

### 4.4 n2 热写路径（5 轮 × 4 臂，100 INSERT + 100 UPDATE）

- 噪声带：`max(A0 ∪ A0rep) = 112,626 μs`；写放大判定阈值（×1.05）= `118,257.3 μs`。

| 轮次 | A0 (μs) | A0rep (μs) | A1 (μs) | A2 (μs) | 阈值 (μs) | A1 判定 | A2 判定 |
|---|---|---|---|---|---|---|---|
| r1 | 46,988 | 32,406 | 63,531 | 40,041 | 118,257.3 | PASS | PASS |
| r2 | 89,696 | 49,713 | 25,627 | 41,989 | 118,257.3 | PASS | PASS |
| r3 | 112,626 | 33,273 | 41,098 | 35,548 | 118,257.3 | PASS | PASS |
| r4 | 55,516 | 23,866 | 34,077 | 54,867 | 118,257.3 | PASS | PASS |
| r5 | 88,102 | 61,117 | 23,291 | 46,899 | 118,257.3 | PASS | PASS |

## 5. EXPLAIN 原文转录（§7 要求；返修新增）

- 转录规则：每形状 × 每臂取**第 1 轮**，EXPLAIN（traditional，TSV）与 EXPLAIN ANALYZE 原文逐字转录；
  其余轮次原文见库外 `rounds.json`。
- 原文形态说明：`rounds.json` 中 EXPLAIN ANALYZE 的**节点分隔符是字面量反斜杠 n（两字符序列）**，
  转录按原样保留（因此每个计划在 `EXPLAIN` 表头行之后呈单行）。该形态正是旧解析器缺陷的根源
  （旧脚本只按真实换行切分，导致只有根节点行被解析）。
- 必含元素核对：n1 A0 的 Table scan 行（actual rows=100500）、n2 A0 的 Table scan 行
  （actual rows=400000）、n1 C0 的 Covering index skip scan 行、A0 传统 EXPLAIN 的 TSV 数据行
  （type=ALL、key=NULL）、C0 传统 EXPLAIN 的 TSV 数据行（type=range、
  key=uk_candidate_exam_student）——均已包含在下文转录中。

### 5.1 n1 各臂第 1 轮

**n1 A0 — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_submissions	NULL	ALL	NULL	NULL	NULL	NULL	62265	10.00	Using where
```

**n1 A0 — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Filter: (exam_submissions.student_id = 965000000001)  (cost=41808 rows=6227) (actual time=3.67..260 rows=10 loops=1)\n    -> Table scan on exam_submissions  (cost=41808 rows=62265) (actual time=3.66..257 rows=100500 loops=1)\n
```

**n1 A0rep — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_submissions	NULL	ALL	NULL	NULL	NULL	NULL	62265	10.00	Using where
```

**n1 A0rep — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Filter: (exam_submissions.student_id = 965000000001)  (cost=40750 rows=6227) (actual time=2.93..333 rows=10 loops=1)\n    -> Table scan on exam_submissions  (cost=40750 rows=62265) (actual time=2.92..330 rows=100500 loops=1)\n
```

**n1 A1 — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_submissions	NULL	ref	idx_submissions_student	idx_submissions_student	8	const	10	100.00	NULL
```

**n1 A1 — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Index lookup on exam_submissions using idx_submissions_student (student_id=965000000001)  (cost=9.74 rows=10) (actual time=0.0696..0.072 rows=10 loops=1)\n
```

**n1 A2 — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_submissions	NULL	ref	idx_submissions_student_list	idx_submissions_student_list	8	const	10	100.00	Using index
```

**n1 A2 — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Covering index lookup on exam_submissions using idx_submissions_student_list (student_id=965000000001)  (cost=2.03 rows=10) (actual time=0.0142..0.0177 rows=10 loops=1)\n
```

**n1 C0 — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_candidates	NULL	range	uk_candidate_exam_student	uk_candidate_exam_student	16	NULL	500	100.00	Using where; Using index for skip scan
```

**n1 C0 — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Filter: (exam_candidates.student_id = 965000000001)  (cost=192 rows=500) (actual time=0.0685..0.188 rows=1 loops=1)\n    -> Covering index skip scan on exam_candidates using uk_candidate_exam_student over student_id = 965000000001  (cost=192 rows=500) (actual time=0.0372..0.157 rows=1 loops=1)\n
```

**n1 C0rep — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_candidates	NULL	range	uk_candidate_exam_student	uk_candidate_exam_student	16	NULL	500	100.00	Using where; Using index for skip scan
```

**n1 C0rep — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Filter: (exam_candidates.student_id = 965000000001)  (cost=192 rows=500) (actual time=0.0286..0.103 rows=1 loops=1)\n    -> Covering index skip scan on exam_candidates using uk_candidate_exam_student over student_id = 965000000001  (cost=192 rows=500) (actual time=0.0272..0.102 rows=1 loops=1)\n
```

**n1 C1 — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_candidates	NULL	ref	uk_candidate_exam_student,idx_candidates_student	idx_candidates_student	8	const	1	100.00	Using index
```

**n1 C1 — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Covering index lookup on exam_candidates using idx_candidates_student (student_id=965000000001)  (cost=1.03 rows=1) (actual time=0.0113..0.0135 rows=1 loops=1)\n
```

### 5.2 n2 各臂第 1 轮

**n2 A0 — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_submissions	NULL	ALL	NULL	NULL	NULL	NULL	232027	10.00	Using where
```

**n2 A0 — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Filter: (exam_submissions.student_id = 965000000001)  (cost=178488 rows=23203) (actual time=3.4..1119 rows=10 loops=1)\n    -> Table scan on exam_submissions  (cost=178488 rows=232027) (actual time=3.39..1107 rows=400000 loops=1)\n
```

**n2 A0rep — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_submissions	NULL	ALL	NULL	NULL	NULL	NULL	232027	10.00	Using where
```

**n2 A0rep — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Filter: (exam_submissions.student_id = 965000000001)  (cost=178092 rows=23203) (actual time=6.29..1099 rows=10 loops=1)\n    -> Table scan on exam_submissions  (cost=178092 rows=232027) (actual time=6.29..1088 rows=400000 loops=1)\n
```

**n2 A1 — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_submissions	NULL	ref	idx_submissions_student	idx_submissions_student	8	const	10	100.00	NULL
```

**n2 A1 — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Index lookup on exam_submissions using idx_submissions_student (student_id=965000000001)  (cost=10.7 rows=10) (actual time=0.146..0.15 rows=10 loops=1)\n
```

**n2 A2 — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_submissions	NULL	ref	idx_submissions_student_list	idx_submissions_student_list	8	const	10	100.00	Using index
```

**n2 A2 — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Covering index lookup on exam_submissions using idx_submissions_student_list (student_id=965000000001)  (cost=2.03 rows=10) (actual time=0.0148..0.0184 rows=10 loops=1)\n
```

**n2 C0 — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_candidates	NULL	range	uk_candidate_exam_student	uk_candidate_exam_student	16	NULL	2009	100.00	Using where; Using index for skip scan
```

**n2 C0 — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Filter: (exam_candidates.student_id = 965000000001)  (cost=805 rows=2009) (actual time=0.0641..0.338 rows=1 loops=1)\n    -> Covering index skip scan on exam_candidates using uk_candidate_exam_student over student_id = 965000000001  (cost=805 rows=2009) (actual time=0.0295..0.303 rows=1 loops=1)\n
```

**n2 C0rep — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_candidates	NULL	range	uk_candidate_exam_student	uk_candidate_exam_student	16	NULL	2009	100.00	Using where; Using index for skip scan
```

**n2 C0rep — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Filter: (exam_candidates.student_id = 965000000001)  (cost=805 rows=2009) (actual time=0.0371..0.404 rows=1 loops=1)\n    -> Covering index skip scan on exam_candidates using uk_candidate_exam_student over student_id = 965000000001  (cost=805 rows=2009) (actual time=0.0352..0.402 rows=1 loops=1)\n
```

**n2 C1 — traditional EXPLAIN（TSV 原文）：**

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	exam_candidates	NULL	ref	uk_candidate_exam_student,idx_candidates_student	idx_candidates_student	8	const	1	100.00	Using index
```

**n2 C1 — EXPLAIN ANALYZE（原文）：**

```
EXPLAIN
-> Covering index lookup on exam_candidates using idx_candidates_student (student_id=965000000001)  (cost=1.08 rows=1) (actual time=0.0113..0.0134 rows=1 loops=1)\n
```

## 6. 机械裁决结论

- 脚本与重算命令（返修第 1 笔）：
  `node spec/changes/archive/attribute-my-exams-list-index/evidence/analyze-index-arms.cjs
  D:/code/examOnline-measure/my-exams-index/stage3/raw/rounds.json
  spec/changes/archive/attribute-my-exams-list-index/evidence/adjudication.json`（退出码 0；
  输入为保留的 `rounds.json`，**未重测**）。
- 解析器缺陷更正：旧脚本违背冻结 §6——取每行第一个 `rows=`（命中的是 cost 段），且未按字面量
  反斜杠 n 切分（仅根节点行被解析）；已按 §6 原文修正：先按字面量反斜杠 n / 真实换行切行，
  再对每行匹配 `/actual time=[0-9.]+\.\.[0-9.]+ rows=([0-9]+)/` 求和；actualTimeMs 仍取根节点
  actual time 终值。
- **整卡裁决 (verdict)**：**`GO`**。
- **答卷渠道采纳 (adoption.submissions)**：**`A1`**（单列索引 `idx_submissions_student (student_id)`，
  通过全部 B1–B5 判据，最小充分索引优先）。
- **补考渠道采纳 (adoption.candidates)**：**`null`**（未采用）。完整理由：
  ① C0 基线亚毫秒——最快轮 0.11 ms（n1）/ 0.338 ms（n2）< 5.0 ms，B1 对称子门失败，不构成代价瓶颈；
  ② 在冻结解析规则下 C1 的 B2 亦未过——比值逐轮恒定 2.0 < 5.0（受 §4 所述逐节点求和口径影响，
  且 ① 已先行否决）。依据冻结裁决规则 3，C1 未采纳**不影响整卡 GO/NO-GO**。
- 返修改判说明：旧版此处曾按 cost 段口径把 C1 的 B2 记为 pass（比值 500 / 1959~2009），
  按冻结 §6 重算后改判 pass=false（比值 2.0）。该改判不改变 verdict 与 adoption
  （reasons 第 3 条为脚本机械产出的对应登记）。

## 7. 库外原件登记

| 库外路径（`D:\code\examOnline-measure\my-exams-index\stage3\raw\`） | 描述 |
|---|---|
| `rounds.json` | 阶段 3 全量 70 轮 read/write 原始 JSON（含 EXPLAIN 与 EXPLAIN ANALYZE 原文字段） |
| `container-start.log` | 容器启动环境留证原件 |
| `container-setup.log` | 数据库建表、验证与每轮 ANALYZE 日志（含被废弃首轮的两套 n1 序列，见 §2） |
| `container-stop.log` | 容器销毁零匹配与容器对比日志 |
| `docker-ps-before.txt` | 销毁对照片（mtime 晚于销毁时刻，不构成事前基线，见 §1） |
| `docker-ps-after.txt` | 销毁后容器状态列表 |
