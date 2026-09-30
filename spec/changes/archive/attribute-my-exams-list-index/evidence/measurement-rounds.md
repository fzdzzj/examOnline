# 测量与控制台原件转录（attribute-my-exams-list-index）

> 原始测量产物直写仓库外 `D:\code\examOnline-measure\my-exams-index\stage3\raw\`；
> 逐轮原始数据 `rounds.json`（含全部 70 轮 read/write 原始 EXPLAIN/EXPLAIN ANALYZE 文本）、
> 容器日志 `container-start.log`、`container-setup.log`、`container-stop.log` 已拷入本目录 `evidence/`。
> 证据哈希清单见 `evidence-sha256.txt`（`sha256sum -c` 须 rc=0）。

## 1. 阶段 3 容器环境与生命周期（全文留证）

- **容器配方**：
  - 镜像：`mysql:8.0`（本地镜像 ID `7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`，digest `mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`）
  - 启动命令：`docker run -d --name my-exams-index-mysql -p 127.0.0.1:13320:3306 -e MYSQL_ROOT_PASSWORD=*** --tmpfs /var/lib/mysql mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci`
  - 容器 ID：`c1bf16be5479a9e685777fc3f44eb75b4dda371f9a6e3e139a0a392023787880`
  - 端口：`127.0.0.1:13320`（避让清单核验通过）
  - 数据库名：`my_exams_index_measure`
  - 版本：`8.0.46`（`SELECT VERSION()` 确认）
- **建表验证**：
  - 执行 `src/main/resources/schema.sql` 退出码 0。
  - `CREATE TABLE IF NOT EXISTS` 匹配数 27（含 1 条历史注释说明，实建 26 表）。
  - 信息库表统计数：26 表。
- **销毁与隔离验证**：
  - `docker rm -f my-exams-index-mysql` 退出码 0。
  - 零匹配核对：`docker ps -a --filter name=my-exams-index-mysql` 仅输出表头，匹配数 0。
  - 避让核对：销毁前后 `docker ps` 输出逐字一致（`sport-verify-*` 5 容器状态端口分毫不差）。

## 2. 逐形状有效性验证（B5① 造数即实）

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

## 3. 逐形状逐臂测量数据表

### 3.1 n1 读路径（5 轮 × 7 臂逐轮轮转）

- 噪声带上界：`band(A0 ∪ A0rep) = 333 ms`；`band(C0 ∪ C0rep) = 0.198 ms`。

| 臂 | 目标 | type | key | actualTimeMs (r1..r5) | rowsSum (r1..r5) | B2 扫描行数比 (r1..r5) |
|---|---|---|---|---|---|---|
| A0 | T1 | ALL | NULL | [260, 303, 304, 275, 265] | [6227, 6227, 5825, 6026, 5624] | 基线 (1.0) |
| A0rep | T1 | ALL | NULL | [333, 267, 272, 229, 311] | [6227, 6227, 5825, 6026, 5624] | 基线 (1.0) |
| **A1** | T1 | ref | idx_submissions_student | **[0.072, 0.060, 0.087, 0.144, 0.124]** | **[10, 10, 10, 10, 10]** | **[622.7, 622.7, 582.5, 602.6, 562.4]** |
| A2 | T1 | ref | idx_submissions_student_list | [0.018, 0.022, 0.024, 0.034, 0.035] | [10, 10, 10, 10, 10] | [622.7, 622.7, 582.5, 602.6, 562.4] |
| C0 | T2 | range | uk_candidate_exam_student | [0.188, 0.110, 0.183, 0.174, 0.168] | [500, 500, 500, 500, 500] | 基线 (1.0) |
| C0rep | T2 | range | uk_candidate_exam_student | [0.103, 0.124, 0.167, 0.198, 0.191] | [500, 500, 500, 500, 500] | 基线 (1.0) |
| C1 | T2 | ref | idx_candidates_student | [0.014, 0.017, 0.019, 0.027, 0.019] | [1, 1, 1, 1, 1] | [500.0, 500.0, 500.0, 500.0, 500.0] |

### 3.2 n1 热写路径（5 轮 × 4 臂，100 INSERT + 100 UPDATE）

- 噪声带：`max(A0 ∪ A0rep) = 145,129 μs`；写放大判定阈值（×1.05）= `152,385 μs`。

| 轮次 | A0 (μs) | A0rep (μs) | A1 (μs) | A2 (μs) | 阈值 (μs) | A1 判定 | A2 判定 |
|---|---|---|---|---|---|---|---|
| r1 | 70,904 | 31,681 | 22,038 | 37,959 | 152,385 | PASS | PASS |
| r2 | 117,638 | 42,266 | 40,885 | 31,344 | 152,385 | PASS | PASS |
| r3 | 145,129 | 45,693 | 35,545 | 21,882 | 152,385 | PASS | PASS |
| r4 | 37,839 | 30,459 | 31,159 | 39,624 | 152,385 | PASS | PASS |
| r5 | 33,877 | 25,298 | 35,209 | 28,091 | 152,385 | PASS | PASS |

### 3.3 n2 读路径（5 轮 × 7 臂逐轮轮转）

- 噪声带上界：`band(A0 ∪ A0rep) = 1442 ms`；`band(C0 ∪ C0rep) = 0.567 ms`。

| 臂 | 目标 | type | key | actualTimeMs (r1..r5) | rowsSum (r1..r5) | B2 扫描行数比 (r1..r5) |
|---|---|---|---|---|---|---|
| A0 | T1 | ALL | NULL | [1119, 1228, 1295, 1327, 1442] | [23203, 24003, 24803, 24803, 24803] | 基线 (1.0) |
| A0rep | T1 | ALL | NULL | [1099, 1103, 1132, 1152, 1152] | [23203, 24003, 24803, 24803, 24803] | 基线 (1.0) |
| **A1** | T1 | ref | idx_submissions_student | **[0.150, 0.097, 0.093, 0.162, 0.076]** | **[10, 10, 10, 10, 10]** | **[2320.3, 2400.3, 2480.3, 2480.3, 2480.3]** |
| A2 | T1 | ref | idx_submissions_student_list | [0.018, 0.020, 0.036, 0.041, 0.022] | [10, 10, 10, 10, 10] | [2320.3, 2400.3, 2480.3, 2480.3, 2480.3] |
| C0 | T2 | range | uk_candidate_exam_student | [0.338, 0.486, 0.482, 0.486, 0.461] | [2009, 1959, 1959, 1959, 2009] | 基线 (1.0) |
| C0rep | T2 | range | uk_candidate_exam_student | [0.404, 0.524, 0.371, 0.567, 0.393] | [2009, 1959, 1959, 1959, 2009] | 基线 (1.0) |
| C1 | T2 | ref | idx_candidates_student | [0.013, 0.018, 0.019, 0.020, 0.015] | [1, 1, 1, 1, 1] | [2009.0, 1959.0, 1959.0, 1959.0, 2009.0] |

### 3.4 n2 热写路径（5 轮 × 4 臂，100 INSERT + 100 UPDATE）

- 噪声带：`max(A0 ∪ A0rep) = 112,626 μs`；写放大判定阈值（×1.05）= `118,257 μs`。

| 轮次 | A0 (μs) | A0rep (μs) | A1 (μs) | A2 (μs) | 阈值 (μs) | A1 判定 | A2 判定 |
|---|---|---|---|---|---|---|---|
| r1 | 46,988 | 32,406 | 63,531 | 40,041 | 118,257 | PASS | PASS |
| r2 | 89,696 | 49,713 | 25,627 | 41,989 | 118,257 | PASS | PASS |
| r3 | 112,626 | 33,273 | 41,098 | 35,548 | 118,257 | PASS | PASS |
| r4 | 55,516 | 23,866 | 34,077 | 54,867 | 118,257 | PASS | PASS |
| r5 | 88,102 | 61,117 | 23,291 | 46,899 | 118,257 | PASS | PASS |

## 4. 机械裁决结论

- 执行脚本：`node spec/changes/attribute-my-exams-list-index/evidence/analyze-index-arms.cjs`（退出码 0）。
- **整卡裁决 (verdict)**：**`GO`**。
- **答卷渠道采纳 (adoption.submissions)**：**`A1`**（单列索引 `idx_submissions_student (student_id)`，通过全部 B1–B5 判据，最小充分索引优先）。
- **补考渠道采纳 (adoption.candidates)**：**`null`**（未采用；原因：C0 基线耗时 0.11–0.48ms，最快轮 < 5.0ms，不满足 B1 瓶颈门槛，不构成代价瓶颈；依据指导主 agent 裁决 2，C0 子门未过只 gate C1 自身，不否决整卡）。

## 5. 库外原件登记

| 库外路径（`D:\code\examOnline-measure\my-exams-index\stage3\raw\`） | 描述 |
|---|---|
| `rounds.json` | 阶段 3 全量 70 轮 read/write 原始 JSON（含 EXPLAIN 与 EXPLAIN ANALYZE 原文字段） |
| `container-start.log` | 容器启动环境留证原件 |
| `container-setup.log` | 数据库建表、验证与每轮 ANALYZE 日志 |
| `container-stop.log` | 容器销毁零匹配与容器对比日志 |
| `docker-ps-before.txt` | 启动前容器占用基线列表 |
| `docker-ps-after.txt` | 销毁后容器状态对比列表 |
