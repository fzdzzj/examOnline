# PREREGISTRATION —— attribute-my-exams-list-index 判据冻结（任何测量之前写定）

- 冻结时刻：2026-09-30T21:14:07+08:00（写定后不再修改；冻结时刻必须早于阶段 3 首跑时刻，核对方式：本文件 mtime 与 tasks.json 记录 + 逐轮记录里的 atIso）
- 被测 revision：`4c007c5`（工作树 D:\code\examOnline，分支 feature/update-monitor-overview-submission-projection）
- 本文件冻结后任何判据/阈值/形状/臂/配方的修改都按卡面停手条款 6 处理：停手回报，不自行修改。

## 0. 测量对象（冻结 SQL 原文，逐字含空白）

来源：`MyExamsSqlCaptureIT`（捕获命令与结果见 tasks.json 阶段 1 证据；捕获 JSON：
仓库外 `D:/code/examOnline-measure/my-exams-index/raw/my-exams-boundsql.json`，rev=4c007c5，
5 条语句）。以下两条为目标语句，**逐字冻结**（MyBatis-Plus 模板空白原样保留）：

- **T1（答卷渠道，A 臂目标）**：
  `SELECT   exam_id,status,deadline_time   FROM exam_submissions      WHERE  (student_id = ?)`
- **T2（补考渠道，C 臂目标）**：
  `SELECT   exam_id   FROM exam_candidates      WHERE  (student_id = ?)`

两条目标语句各恰有一个 `?`。测量替换规则（冻结）：把 `?` 原位替换为目标学生字面量
（见 §4 目标学生），其余字符（含全部空白）逐字不动。B5④ 在阶段 3 开测前复跑捕获 IT，
以其 JSON 里对应语句的 sql 字段与本节文本逐字比对（不等 → 停手条款 5）。
其余三条捕获语句（user_class 渠道 / exams 班级绑定 / exams 主键 IN）非本卡目标，不冻结、不测量。

## 1. 形状（固定两个，不许增减；量级出处见仓库外 stage1-volume-anchors.md）

- **n1（常态量级，§20 exam_bench 口径）**：exam_submissions 100,000 行；对应 500 场考试；
  10,000 名学生 × 每人恰 10 份答卷；exam_candidates 5,000 行；paper_json 全行非空、
  恰 4096 字符；answers 仅已交卷/已批改行非空、恰 2048 字符（进行中行 NULL）；
  状态分布 60% 进行中 / 25% 已交卷 / 15% 已批改。
- **n2（n1 的 ×4 放大形状）**：exam_submissions 400,000 行；2000 场考试；40,000 名学生 ×
  每人恰 10 份答卷；exam_candidates 20,000 行；字段长度与状态分布同 n1。
  放大倍数 ×4 冻结于此，用于判"基线代价是否随表线性增长"与收益点是否落在容量包络
  （§20 锚：25k 进行中 / >5000 并发）之内/外。
- 学生数、每人答卷数、candidates 行数、状态分布为**派生设计值**（文档无锚，登记于
  stage1-volume-anchors.md §E，不冒充文档锚）；答卷总量、场次、paper_json 长度、
  批量口径 100、包络锚为**文档锚**。
- 两形状都有"本人答卷数 = 常态值（10 份）"的目标学生；目标学生同时是 1 场补考的候选人。
- 形状执行顺序冻结：先 n1 后 n2；两形状之间 **DROP DATABASE → CREATE DATABASE →
  整库重放 schema.sql → 重新造数**，不允许在残留数据上续测。

## 2. 臂定义（固定 7 个，不许增减）

| 臂 | exam_submissions 索引状态 | exam_candidates 索引状态 | 目标语句 |
|---|---|---|---|
| A0 | 基线（schema.sql 原样 5 索引） | 基线 | T1 |
| A0rep | 同 A0（重复臂，估噪声带） | 同 A0 | T1 |
| A1 | 基线 + `idx_submissions_student (student_id)` | 基线 | T1 |
| A2 | 基线 + `idx_submissions_student_list (student_id, exam_id, status, deadline_time)`（**不含** A1 的单列索引：同轮切换时先 DROP A1 再 ADD A2） | 基线 | T1 |
| C0 | 基线 | 基线（同 A0 状态，为 T2 提供基线读数） | T2 |
| C0rep | 同 C0（重复臂） | 同 C0 | T2 |
| C1 | 基线 | 基线 + `idx_candidates_student (student_id, exam_id)` | T2 |

- 索引切换一律 `ALTER TABLE … ADD INDEX / DROP INDEX`；**每次切换后对 exam_submissions 与
  exam_candidates 各跑一次 `ANALYZE TABLE`**（§20 口径），切换与 ANALYZE 退出码留证。
- 臂状态切换序列（每轮内）：基线(A0,A0rep) → +A1 → 换 A2 → 回基线(C0,C0rep) → +C1 → 回基线(下一轮 A0)。
- 前缀索引语法、FORCE INDEX 均不使用（H2/MySQL 双兼容约束，卡面 §7；本卡若 GO，
  进 schema.sql 的 KEY 行以本表索引定义为准）。

## 3. 轮次与轮转（冻结）

- **读路径**：每形状 5 轮 × 7 臂，**逐轮左轮转**：第 r 轮依次测 A0 → A0rep → A1 → A2 →
  C0 → C0rep → C1（A/C 臂分别测 T1/T2），不许把同臂 5 轮连跑后挑轮。
  每轮每臂记录：形状、轮次、臂名、EXPLAIN（traditional）原文、EXPLAIN ANALYZE 原文、
  机械累加的 actual rows 总和（解析规则见 §6）、根节点 actual time 终值（ms）、
  ISO 时刻、容器 ID、revision。
- **热写路径**（B4 用，仅 exam_submissions；exam_candidates 在本卡范围内无被测热写路径，
  B4 对 C 臂不适用——冻结为适用范围）：每形状 5 轮 × 4 臂 [A0 → A0rep → A1 → A2] 逐轮轮转。
  每轮一批：**100 行 INSERT（含 paper_json 4096）+ 100 行 UPDATE（写 answers 2048 +
  submit_time/submit_type/status）**，批量口径 100 = docs/submit-loadtest-report.md
  消费者批次锚。计时 = 服务端窗：批前 `SET @t0=NOW(6)`、批后 `SET @t1=NOW(6)`、
  `SELECT TIMESTAMPDIFF(MICROSECOND,@t0,@t1)`，逐轮记录 elapsed_us + ISO 时刻 + 容器 ID + revision。
- 计时环境：一次性 mysql:8.0 容器、tmpfs 数据目录、独立库 `my_exams_index_measure`、
  127.0.0.1 高位空闲端口、utf8mb4；记录镜像 digest / 容器 ID / SELECT VERSION()；
  用毕 `docker rm -f` + `docker ps -a --filter name=` 零匹配留证。**H2 不得作为任何代价证据来源。**

## 4. 造数配方（精确公式，冻结；两形状同一套公式只换规模参数）

- ID 空间（n1；n2 括号内）：
  - 考试：`960000000001 + e`，e ∈ [0,500)（[0,2000)）；本测量不向 exams 表插行
    （T1/T2 为单表语句，EXPLAIN 计划不触及未引用表；B5① 只核被造两表——冻结为此口径）。
  - 学生：`965000000000 + i`，i ∈ [1,10000]（[1,40000]）。
  - 答卷：学生 i 的第 k 份（k ∈ [0,10)）→ 考试 `960000000001 + ((i*13 + 3*k) mod 500)`
    （n2: mod 2000）。同一学生 10 份的 exam 两两不同（步长 3 与 500/2000 互素保证）；
    uk_exam_student(exam_id, student_id) 不冲突。
  - 状态：全局行序 p = (i−1)*10 + k（从 0 起）：p < 60% → status=1（answers NULL）；
    60%–85% → status=2；85%–100% → status=3；status≥2 行 answers 非空。
  - 补考候选：最后 50（200）场考试 × 每场学生 idx [1,100] → 5000（20000）行；
    目标学生 idx=1 在其中（第 1 场补考的候选人）。
  - **目标学生：i=1 → student_id = 965000000001**，恰 10 份答卷 + 1 条候选。
  - 热写 UPDATE 池：学生 idx [10001,10050]（n2 同为 50 人）× 10 份 = 500 行，造数时 status=1；
    第 r 轮 UPDATE 其中第 r 个 100 行切片（r ∈ [1,5]，同一臂内各轮切不同切片，臂间复用同一池）。
  - 热写 INSERT 流：student_id = `966000000000 + c`，c 为形状内单调计数器（每轮 +100），
    exam_id 固定 `970000000001`，status=1，含 paper_json。
- 长字段构造（生成本地断言长度精确相等，不等即停）：
  - paper_json（4096）：`{"questions":[` + 8 个 `{"id":j}`（j=1..8，逗号连接）+ `],"pad":"`
    + 'B'×4000 + `"}`（14 + 71 + 9 + 4000 + 2 = 4096）。
  - answers（2048）：`{"1001":"` + 'A'×2037 + `"}`（= 2048，沿用已归档监考测量夹具公式）。
- 造数后、任何测量前：`ANALYZE TABLE exam_submissions; ANALYZE TABLE exam_candidates;`。

## 5. 判据 B1–B5 与裁决（冻结；两形状分开全过才算，任一形状不过 → NO-GO）

记 actualTimeMs(arm, shape, r) 为该轮 EXPLAIN ANALYZE 根节点 actual time 终值；
rowsSum(…) 为该轮各节点 actual rows 机械累加。

- **B1 事实认定（A0 下，逐形状）**：T1 的 EXPLAIN 在 exam_submissions 行上 type=ALL 且 key=NULL；
  且 A0 五轮 actualTimeMs 的**最快轮 ≥ 5.0 ms**。type≠ALL 或最快轮 < 5 ms → 直接 NO-GO，
  如实登记"结构上无 student_id 打头索引，但代价不构成问题"。
  对称适用：C0 下 T2 的 EXPLAIN type=ALL 且 key=NULL 且最快轮 ≥ 5 ms——该子门**只 gate
  C1 的采用**（见裁决规则），不 gate 整卡（整卡 B1 = A0/T1 门）。
- **B2 主判据（确定性）**：扫描行数比 = rowsSum(基线臂) / rowsSum(候选臂)，逐轮计算。
  A1/A2 对 A0；C1 对 C0。判据：**全部 5 轮**比值 ≥ 5.0（不取中位数、不挑轮）。
- **B3 时间单侧无回归**：噪声带 = max(A0 五轮 ∪ A0rep 五轮) 的 actualTimeMs（C1 用
  max(C0 ∪ C0rep)）。判据：候选臂**每轮** actualTimeMs ≤ 噪声带（逐轮，逐形状）。
- **B4 写放大**：热写批（§3 口径）下，A1/A2 **每轮** elapsed_us ≤ max(A0 五轮 ∪ A0rep 五轮)
  × 1.05。超出 → 该臂 NO-GO（§20 反向判据：收益点在包络外、代价是交卷热写路径永久写放大）。
- **B5 测量有效性**：
  1. 造数即实：逐形状 `SELECT COUNT(*)` 与 `SUM(LENGTH(paper_json))`/`SUM(LENGTH(answers))`
     机械核：exam_submissions 行数 == 设计值；paper_json 总长 == 设计行数 × 4096；
     answers 非空行数 == 设计非空行数；answers 总长 == 非空行数 × 2048；
     exam_candidates 行数 == 设计值；目标学生答卷数 == 10（COUNT 核）。
  2. 每次臂切换后 ANALYZE TABLE 已跑（留证）。
  3. EXPLAIN 的 type / key / rows 三列可机械解析（脚本解析失败即 B5 fail）。
  4. 阶段 3 开测前复跑 MyExamsSqlCaptureIT，其 T1/T2 与 §0 冻结文本**逐字相同**。
- **裁决（冻结的操作化，含两处卡面未明说处的解释，特此显式披露）**：
  1. **形状耦合**：两形状的 B1(A0/T1)、B5 全过，且答卷渠道存在通过两形状 B2/B3/B4 的臂，
     才 GO；任一形状不过 → NO-GO（卡面"索引是全局对象，不允许部分 GO"按**形状维度**理解）。
  2. **答卷渠道选臂**：A1 通过两形状 B2+B3+B4 → 采用 A1；否则 A2 通过 → 采用 A2；
     否则答卷渠道无采用臂 → 整卡 NO-GO（§20 反对超需索引：最小充分索引优先）。
  3. **补考渠道**：C1 通过两形状 B2+B3 **且** C0 子门（B1 对称式）成立 → C1 并入同批采用；
     否则 C1 不采用、**不影响整卡 GO/NO-GO**。解释：卡面 B4 原文即含"该臂 NO-GO"的
     逐臂概念，且卡面裁决句只把形状（而非渠道）耦合进 verdict；把小表渠道的失败用来
     否决已实测成立的答卷渠道修复，与卡面 §3 的主旨和 §20 的代价收益逻辑相悖。
     此解释在冻结时写定，若裁决实际落到该分叉，回报时以 ⑨ 过程披露显著呈现。
  4. verdict 与 reasons 由 `analyze-index-arms.cjs` 机械产出（见 §6），不由人给。

## 6. 机械复算（analyze-index-arms.cjs 与 adjudication.json）

- 脚本：`evidence/analyze-index-arms.cjs`（Node ≥18，纯解析与比较，无网络无交互）。
- 输入：argv[2] = 逐轮记录 JSON（测量驱动产出，含逐轮 raw EXPLAIN / EXPLAIN ANALYZE 原文、
  elapsed_us、形状/臂/轮次/时刻/容器/revision）；argv[3] = 输出路径。
- 解析规则（冻结）：actual rows 累加 = 对 EXPLAIN ANALYZE 每行匹配
  `/actual time=[0-9.]+\.\.[0-9.]+ rows=([0-9]+)/` 取捕获值求和（只认 actual 段，不认 cost 段）；
  actualTimeMs = 首行（根节点）匹配到的 `..` 后那个数。
- 输出 adjudication.json（冻结格式）：
  `{verdict: "GO"|"NO-GO", reasons: string[], adoption: {submissions: "A1"|"A2"|null, candidates: "C1"|null},
    shapes: {n1: {b1: {…pass…}, b2: {arm: {ratios, pass}}, b3: {…}, b4: {…}, b5: {…}}, n2: {…}},
    perArm: {shape: {arm: {rounds: [{round, rowsSum, actualTimeMs, elapsedUs?}…], band, ratios…}}}}`
- 脚本退出码 0 = 解析与裁决完成（无论 verdict）；解析不完整 = 非零并如实报错。

## 7. 证据落位（冻结）

- 原始大文件留仓库外：`D:\code\examOnline-measure\my-exams-index\stage3\`（逐轮 raw、
  容器 setup/destroy 日志、造数校验输出）。
- 归档进 evidence/：本文件、preregistration.sha256.txt、容器与销毁日志关键段、
  EXPLAIN 原文转录、`adjudication.json`、`analyze-index-arms.cjs`、`measurement-rounds.md`
  （逐轮数值表 + EXPLAIN 原文转录 + 库外路径登记）、`evidence-sha256.txt`。
- 测量期间不改 src/main、schema.sql、docker/mysql/migrations/（若 GO，实施发生在测量与
  裁决完成之后，属阶段 4，不属于测量现场）。
