# 预登记判据（写定于正式测量前，冻结不改）

> 冻结时刻：2026-09-29（任何测量开始前）。本文件写定后不再修改；测量与裁决按本文件的算子机械复算。若现场事实与本文件前提冲突，如实记录冲突并停，不为 GO 改写本文件。
> 修订记录：2026-09-29（任何测量开始前）订正 §2.1 一处事实笔误——站点 2 的返回行数应为 **n**（应考名单 n+5 人 ∩ E 上 n 行答卷），原写 n−5；判据算子未变，sha256 同步重记。
> 冻结时的仓库入口：`f6e336c`（分支 `feature/update-monitor-overview-submission-projection`）。本文件 sha256 在冻结后记入 `evidence/preregistration.sha256.txt`。
> 「测量」的起点定义：第一次运行本变更的捕获 IT 或容器脚本之前。写测试代码与脚本本身不算测量。

## 0. 对象与站点清单

被考察对象：`exam_submissions` 表上的**标量只读站点**——生产代码只用该行上的标量字段、却按实体默认全列取数（`paper_json` LONGTEXT、`answers` LONGTEXT 因此被整行搬过网络并逐行映射）。五个站点及其锚点（冻结时就地核对，行号会漂，以方法与语句为锚）：

| # | 站点（入口方法） | 取数语句锚点 | 实体 | 实际调用 getter（机械清单） | 站点读语句数（预期） |
|---|------------------|--------------|------|-----------------------------|----------------------|
| 1 | `ExamTakingService.myExams` | `submissionMapper.selectList(Wrappers.lambdaQuery().eq(studentId).in(examId, 50 场))` | `ExamSubmission` | `getExamId` / `getStatus` / `getDeadlineTime` | 1 |
| 2 | `AbsenceService.markAbsence` | `submissionMapper.selectList(Wrappers.lambdaQuery().eq(examId).in(studentId, expectedIds))` | `ExamSubmission` | `getStudentId`（仅此一个） | 1 |
| 3 | `ScoreService.myScore` | `gradingSubmissionMapper.selectOne(Wrappers.lambdaQuery().eq(examId).eq(studentId))` | `GradingSubmission` | `getTotalScore` / `getStatus` / `getObjectiveScore` / `getSubjectiveScore` / `getPartialGraded` | 1 |
| 4 | `ScoreReviewService.handle`（AGREE 且 `adjustedTotalScore != null`） | `gradingMapper.selectOne(Wrappers.lambdaQuery().eq(examId).eq(studentId))` | `GradingSubmission` | `getId`（仅此一个） | 1 |
| 5 | `MakeupScoreService.collectFamilyScores`（由 `finalScore` 进入） | 逐家族考试 `gradingMapper.selectOne(Wrappers.lambdaQuery().eq(examId).eq(studentId).isNotNull(totalScore).last("LIMIT 1"))` | `GradingSubmission` | `getTotalScore` / `getSubmitTime` | 2（主考 + 1 场补考） |

**排除项（不评估、不改动，写进 evidence，不得顺手实施）**：`MonitorService.overview`（前序变更已 NO-GO，结论有效）；一切需要 `answers`/`paper_json` 做逐题得分的路径（判分、成绩预览、导出个人报告、复核展示、补发扫描）。

**两臂定义**：

- OLD 臂 = 现状 SQL（实体默认全列；SELECT 列表以捕获的 OLD 语句原文为准，机械解析，不手写）。
- PROJ 臂 = 在冻结的**同一谓词与排序**下把 SELECT 列表限定为下表冻结列（列名即 `.select(Entity::getX, ...)` 的字段）：

| # | PROJ 冻结列（按冻结顺序） |
|---|---------------------------|
| 1 | `exam_id, status, deadline_time` |
| 2 | `student_id` |
| 3 | `status, objective_score, subjective_score, total_score, partial_graded` |
| 4 | `id` |
| 5 | `total_score, submit_time` |

PROJ 臂在测试上下文中的施加方式：测试侧 `Executor.query` 拦截器在 `proceed()` 前对参数里的 `LambdaQueryWrapper` 调用 `.select(...)`（仅本测试上下文、仅目标语句、仅 PROJ 臂），生成的 SQL 与阶段 3 生产实现的 `.select(...)` 同源；容器侧执行的是捕获 SQL 的机械 `?`→字面量替换版，不手写。

## 1. 造数形状与分布

- 形状：`n ∈ {200, 1000, 3000}`；每题行内 `answers` 字符串长度恰 2048、`paper_json` 恰 20480（沿用补考归因轮的生成口径：`answers = {"1001":"A…A"}` 填充到 2048 字符；`paper_json` 同构填充到 20480 字符；全 ASCII ⇒ 字符数=字节数）。
- 分数生成（与既有 IT 同口径）：`score(i) = BigDecimal(400 + (i*7919)%300, 1)`，值域 40.0–69.9；`gcd(7919,300)=1` ⇒ 300 个取值、大量并列。
- 行状态：`status=3`（已批改）、`grading_status=1`、`objective_score=total_score=score(i)`、`subjective_score=0.0`、`partial_graded=0`、`version=0`，`answers`/`paper_json` 非空。

**夹具区块（H2 测试上下文；容器侧只需答卷行，见下）**：

- 站点 1 区块：50 场已发布考试（`id = 970_100_000 + k`，k=0..49；`published=1`、`start_time = T0 + k 分钟`（两两不同且晚于其它区块，保证分页 top-50 恰为本区块）、`end_time = start + 60min`、`duration_minutes=60`、`created_by=973_999_999`）。真实登录学生 `973_100_000`：k=0..44 共 45 行本人答卷（k 0..19 `status=2`；k 20..29 `status=1` 且 `deadline_time = T0 − 60min`（已超时分支，落 FINISHED）；k 30..34 `status=1` 且 `deadline_time` 为未来但考试 `status=2`（落 FINISHED）；k 35..39 `status=3`；k 40..44 `status=1`、`deadline_time = T0 + 60min`、考试 `status=1`（落 ONGOING，`remainingSeconds` 为调用时钟相关字段））；k=45..49 无本人行（k=45,46 考试 `status=1` → UPCOMING 可进入；k=47,48 考试 `status=0` → UPCOMING 不可进入；k=49 考试 `status=2` → FINISHED）。填充行 `974_000_000 + j` 使该区块答卷恰为 n 行（本人 45 + 填充 n−45，按 `exam_id = 50 场轮转` 分散）。**本期站点的返回行数被分页上界锁定为 45，与 n 无关**——形状维度对本站点只考察表规模，字节侧三形状近似相同，如实登记。
- 站点 2/3/4/5 共享区块：主考 `E(n) = 970_200_000 + n`（`status=4` 已发布、`published=1`、`class_id = 972_200_000 + n`、`makeup_score_rule='takeLatest'`、`parent_exam_id` 为 NULL、`start_time = T0 − 10 天`）；补考 `F(n) = 970_300_000 + n`（`parent_exam_id = E(n)`、`status=3`、`start_time = T0 − 9 天`）；应考名单 = `973_200_000 + i`（i=0..n+4，共 n+5 人，`user_class` 行）；E 上答卷 n 行（i=0..n−1，`submit_time = T0 − 2h`）；F 上仅 i=2 一行（`total_score=30.0`、`submit_time = T0 − 1h`）。目标：站点 3 用 i=0（本人分 `score(0)=40.0`；预期名次 = n − floor((n−1)/300)：n=200→200、n=1000→997、n=3000→2991）；站点 4 用 i=1（`adjustedTotalScore=55.0`；执行后该行 `total_score` 应为 55.0）；站点 5 用 i=2（主考行 `score(2)=63.8`、补考行 30.0 且时间更晚 ⇒ `takeLatest` 预期恰为 30.0；若 `submit_time` 漏读会退化为取最高 63.8，可分辨）。缺考分支：名单末 5 人（i=n..n+4）无答卷 ⇒ 站点 2 预期 `inserted=5`。
- 登录身份（仅 IT）：站点 1 为 STUDENT 层级合成身份（`973_100_000`）；站点 2 不校验身份（直接调用，仍设置身份保持一致性）；站点 3 为 `973_200_000`（STUDENT）；站点 4 为 ADMIN 层级（`OwnershipGuard` 放行）；站点 5 不校验身份。
- 基准时刻 `T0`：一次造数的秒级时间戳，连同各区块行数、`SUM(LENGTH(paper_json)+LENGTH(answers))` 逐形状落盘。
- **容器侧种子**：只需 `exam_submissions` 行（站点 SQL 只触达该表）：站点 1 区块 n 行 + E(n) n 行 + F(n) 1 行 = 2n+1 行/形状；长字段用 `CONCAT('{"1001":"', REPEAT('A', 2037), '"}')` 与 `CONCAT('{"snapshot":"', REPEAT('B', 20465), '"}')` 生成，种毕以 `LENGTH` 机械核对（恰 2048 / 20480，否则测量无效）。
- **IT 侧复位（写站点）**：站点 2 每臂前 `DELETE FROM exam_absence WHERE exam_id = E(n)`；站点 4 每臂前把复核行复位为 `status=0, result=NULL, handle_time=NULL, handler_id=NULL`、并把 i=1 答卷行 `total_score` 复位为原值（`score(1)=51.9`）。两臂的臂前快照必须逐字段相同（护栏），否则测量无效。

## 2. 臂、轮次与测量量

### 2.1 测试上下文（S1/S4 语义层；单次两臂对拍）

- 运行：`mvnw.cmd -q test -Dtest=ScalarReadsAttributionMeasureIT -DfailIfNoTests=false -Dmeasure.rev=<sha7> -Dmeasure.label=<label> -Dmeasure.out=D:\code\examOnline-measure\project-scalar-reads`（类名以 IT 结尾，不进 Surefire 全量门禁）。
- 逐站点 × 逐形状：OLD 臂执行一次、复位（写站点）、PROJ 臂执行一次；两臂的输出/生效快照与语句账目落盘。
- **S1 输出口径**：站点 1 = `List<ExamListItem>` 按页序的规范化 JSON；站点 2 = 返回值 `int` + `exam_absence` 行集（缺考学生集合与状态）；站点 3 = `MyScoreResponse` 规范化 JSON；站点 4 = 生效快照（复核行 `status/result/handler_id/apply_time/created_time` + 答卷行 `total_score` 及其余列，见 2.3 排除清单）；站点 5 = `BigDecimal`（`compareTo` 与 `toPlainString` 双录）。
- **调用时钟相关（T 类）字段冻结清单**（两臂比较规则：同为 null 或同非 null 且数值差 ≤ 1 秒；其余字段严格相等）：站点 1 = `remainingSeconds`；站点 2 = `exam_absence.marked_time / created_time`（比对集不选该两列）；站点 4 = `score_review.handle_time`、`exam_submissions.updated_time`（比对集不选该两列）。
- **运行时护栏（每次两臂执行都适用）**：目标语句返回的实体在测试侧被「读取即失败」包装（`answers`/`paperJson` 的 getter 一经调用即抛断言错）；PROJ 臂实体长字段必须为 null，而库内同谓词行该列非 null（机械计数 > 0）；捕获 SQL 的 SELECT 列表在 PROJ 臂等于冻结列且 ≠ OLD 列表。
- **S4 语句账目**：`Executor.query` 拦截器按语句 id 记账（条数/行数）——目标语句两臂条数与行数相同（站点 1 = 45 行；站点 2 = n 行（应考名单 n+5 人 ∩ E 上 n 行答卷）；站点 3/4 = 1 行；站点 5 = 2 条各 1 行）。

### 2.2 真引擎（S2/S3/S4 字节与时间层；一次性本地容器）

- 环境：`mysql:8.0` 一次性本地容器（与 docker-compose.yml 同主版本），tmpfs 数据目录、127.0.0.1 高位端口、独立库名、`--default-character-set=utf8mb4`（规避遗留 #16），整库执行仓库 `src/main/resources/schema.sql`（退出码 0）；记录镜像 digest、`SHOW CREATE TABLE exam_submissions`、`SHOW INDEX`；用毕 `docker rm -f` 销毁并留 `docker ps -a` 零匹配证据；不连共享 dev、不写 dev 库。
- 每站点 × 每形状 = 3 个臂位：`OLD`（全列）/ `PROJ`（投影）/ `OLDrep`（与 OLD 完全同文的等价副本臂，用于漂移噪声带）。每臂 2 次预热（不计时）+ 5 个计时轮。
- 轮序逐轮轮转（左轮转）：第 r 轮（r=1..5）的臂序 = `[OLD, PROJ, OLDrep]` 左移 (r−1) mod 3 位。
- 每轮测量：容器内 `date +%s%N` 包住 `timeout 120 mysql -uroot --default-character-set=utf8mb4 <库> < <臂 SQL 文件> > /dev/null`，记录耗时（ns）、退出码、ISO 时间戳；非 0 退出/超时的轮原样保留为 failed 并同位置补跑一次记 retry；某臂成功轮 < 5 ⇒ 该形状测量无效（M3）。
- 应传字节（主判据载体，机械、非计时、每臂每形状测一次，数据只读故确定）：对臂 SQL 的**完全相同谓词**执行 `SELECT COALESCE(SUM(Σ_columns COALESCE(LENGTH(CAST(col AS CHAR)),0)),0) AS bytes, COUNT(*) AS rows FROM exam_submissions WHERE <谓词>`，列集合 = 该臂捕获 SQL 的 SELECT 列表（机械解析）；另记同臂 `mysql -N -B` 原样输出字节数（`wc -c`）与行数（`wc -l`）作**佐证**（不参与裁决）；站点 5 的两条语句按逐条求和。
- SQL 条数（S4）：臂文件语句数（站点 1–4 = 1；站点 5 = 2），两臂相同；返回行数两臂相同。
- 裁决只使用「每臂在每形状的**同一口径绝对量**」：S2 用单一确定性的 LENGTH 字节和之比；S3 用逐轮 wall-clock 与噪声带比较；**不跨轮相减求占比**。

### 2.3 测量有效性（M 组，全须成立；任一不成立 ⇒ 该形状测量无效，如实停并记录，不作收益结论）

- **M1 捕获有效性**：五站点目标语句的 SQL 原文与 ms id 逐字落盘；容器执行文本与捕获文本仅差 `?`→字面量的机械替换（替换次数 == 参数个数，前后文本均落证据）；PROJ 臂捕获 SELECT 列表 == 冻结列。
- **M2 容器有效性**：`schema.sql` 整库执行退出码 0；镜像 digest 与 `SHOW CREATE TABLE exam_submissions` 落盘；逐形状各区块 `COUNT(*)` 等于设计值（站点 1 区块 n、E(n) 区块 n、F(n) 区块 1）；长字段长度机械核对恰为 2048/20480；`answers`/`paper_json` 非空行数等于区块行数。
- **M3 轮次完整性**：每站点 × 形状 × 臂的 5 个成功轮齐备，时间戳连续可对账；原始输出逐字保存。

## 3. 判据算子与裁决（逐站点独立；允许部分 GO）

- **S1（语义等价）** ⇔ 两臂输出（2.1 口径）逐字段相同（T 类字段按 2.1 规则）且当次 IT 护栏断言全过，3 形状全过。
- **S2（字节收益）** ⇔ 对每个形状：`ratio = bytes_LENGTH(OLD) ÷ bytes_LENGTH(PROJ) ≥ 5.0`（冻结倍数，不得下调）。措辞纪律：S2 不成立写「**字节收益不成立**」。
- **S3（单侧无回归）** ⇔ 对每个形状：PROJ 臂**每一轮** wall-clock ≤ 噪声带上界，其中噪声带上界 = `max(OLD ∪ OLDrep 的全部成功轮 wall-clock)`（每一轮都要满足，不得改为中位数比较）。措辞纪律：S3 不成立写「**不稳定/无净收益**」。
- **S4（形状不变）** ⇔ 每个形状：两臂的站点读语句条数相同且返回行数相同（IT 账目与容器 LENGTH 计数双侧一致）。
- **裁决主式**：`GO_site ⇔ (∀形状: M1∧M2∧M3) ∧ (∀形状: S1∧S2∧S3∧S4)`。M 组不成立 ⇒ 该站点按「测量无效」如实登记、不实施；S 组不成立 ⇒ 按上表措辞如实登记、不实施。逐站点独立裁决，允许部分 GO；GO 的站点才进入阶段 3，且只改取数形态这一类。
- 措辞纪律总则：S2 与 S3 的失败措辞不得混用；不得为凑 GO 把「每一轮」换成中位数、不得下调 5.0 倍数、不得挑轮；测量环境（一次性本地容器、单机、MockMvc/H2 测试上下文）的结论不外推生产 MySQL/Tomcat，不构成交卷或监考 P99 结论。

## 4. 证据落位与复算

- 原始产物（脚本、逐轮原始输出、IT JSON、容器日志）直写仓库外 `D:\code\examOnline-measure\project-scalar-reads\`（不写 C 盘、不留在 target）；收口前把关键件拷贝入本目录 `evidence/`。
- 机械复算脚本：`analyze-scalar-reads.cjs`（与本文件同批入库；输入 = IT JSON + 容器逐轮 JSON；输出 = 逐站点 `adjudication.json` 与 `verdict-console.txt`），裁决由脚本按本文件算子输出，不人脑算。
- 证据清单 `evidence/evidence-sha256.txt`，收口 `sha256sum -c` 须 rc=0。
