# 预登记判据（写定于正式测量前，冻结不改）

> 冻结时刻：2026-09-29（测量开始前）。本文件写定后不再修改；测量与裁决按本文件的算子机械复算。若现场事实与本文件前提冲突，如实记录冲突并停，不为 GO 改写本文件。

## 0. 对象与固定口径

- **被归因语句**：`ScoreService.myScore` 调用链中 MyBatis 语句 id 为 `com.exam.grading.mapper.GradingSubmissionMapper.selectCount` 的 COUNT（MyBatis-Plus 生成，表 `exam_submissions`）。以拦截器现场捕获的 SQL 原文为准，不手写。
- **绑定参数（主臂）**：`exam_id = 930000000 + n`（n ∈ {50, 200, 1000, 3000}，沿用 `RankAttributionMeasureIT` 的 EXAM_ID_BASE/SIZES）、`status = 3`（ExamSubmission.STATUS_GRADED）、`X = 40.0`（本人总分 = score(0) = BigDecimal.valueOf(400, 1)，即 unscaled 400、scale 1；分数值域 40.0–69.9、300 个取值，与既有 IT 同口径；REAL_STUDENT_INDEX=0 对应本人分最低的形状）。捕获 IT 现场从返回值与种子行核对该值，不由人手填。
- **辅助臂（只记录不裁决）**：`X_mid = BigDecimal.valueOf(400 + ((⌊n/2⌋+1)×7919)%300, 1)`（中位分参数化），用于展示 X 选择性对两臂的影响方向。
- **造数规则（与 RankAttributionMeasureIT 同口径）**：exam_submissions 逐份直插 exam_id=930000000+n、status=3(已批改)、grading_status=1、total_score=objective_score=score(i)=BigDecimal(400+(i×7919)%300,1)、subjective_score=0.0、student_id=930000000+n×100000+i（本工具不登录，全部合成 id；index 0 亦是合成行，myScore 不读 users）。exams 行直插 id=930000000+n、status=4、published=1、paper_id=930000001。差异声明：RankAttributionMeasureIT 的 index 0 是真实登录用户行，本工具全部为合成行——对 COUNT 的 SQL 形态与 X=400.0 无影响。paper_json/answers 保持 NULL（同既有 IT 口径）；生产已批改行这两列非空、行更宽，回表代价只高不低，故本测量对候选收益的估计偏保守，方向如实记录。
- **环境**：一次性本地 Docker 容器 `mysql:8.0`（与 docker-compose.yml 同主版本），tmpfs 数据目录，127.0.0.1 高位端口，独立库名，不连共享 dev、不写 dev 库、不碰数据卷；证据落盘后 `docker rm -f` 销毁。整库执行仓库 `src/main/resources/schema.sql`（MySQL 客户端带 `--default-character-set=utf8mb4`，规避遗留 #16 的字符集静默失真）。
- **轮次**：读路径每形状×每臂 = 2 次预热（不计入）+ 1 次 EXPLAIN + 5 次「普通 COUNT 取值 + EXPLAIN ANALYZE」配对轮（≥3 轮要求按 5 轮执行）；全部逐轮原样记录（原始输出逐字保存、逐轮 ISO 时间戳），不挑轮、不取单次最好值。漂移复测（Phase C）= DROP 候选索引后每形状 2 轮基线重测。写路径每操作×每臂 3 轮。

## 1. 测量有效性判据（M 组——全须成立；任一不成立 ⇒ 测量无效，如实停并记录，不作任何收益结论）

- **M1 捕获有效性**：拦截器捕获到且仅捕获到一条 `GradingSubmissionMapper.selectCount` 于每次 myScore 调用；其 SQL 文本逐字记录；容器侧执行的 SQL 与捕获 SQL 仅相差 `?`→字面量的机械替换（替换由脚本完成，替换前后文本均落证据）；每次调用的 myScore 语义自检成立（返回 rank == 独立推算的 count(total_score > X)+1、reviewing=false、totalScore == X）。
- **M2 容器有效性**：schema.sql 在容器整库执行退出码 0；`SHOW CREATE TABLE exam_submissions` 含全部 6 个既有索引名且不含候选索引（基线态）/含候选索引（候选态）；逐形状 `COUNT(*)`（按 exam_id）精确等于 n；基线臂 EXPLAIN 的 Extra **不含** "Using index"（若基线已覆盖 ⇒ 归因对象不成立 ⇒ NO-GO，结论写「无回表可消除」）。
- **M3 轮次完整性**：预登记的每形状×每臂轮数全部存在且被记录（轮数不足即无效），时间戳连续可对账；原始 docker exec 输出逐字保存。

## 2. 主导因素判据（D 组——在 M 组全成立时才裁决）

- **D1（回表消除·机制）**：候选臂同一条 COUNT 的 EXPLAIN 显示 "Using index"（覆盖扫描，无回表）；基臂 EXPLAIN 无 "Using index"（与 M2 一致）。
- **D2（收益超过同代码顺序漂移；主臂 X=40.0）**，逐形状：
  - `speedup_low(n) = min(基线 5 轮 actual time) ÷ max(候选 5 轮 actual time)`（最保守收益倍数）；
  - `drift_band(n) = max(基线全程轮次) ÷ min(基线全程轮次)`，其中「基线全程轮次」= 基线臂 5 轮 ∪ Phase C 漂移复测 2 轮（同容器同数据同索引集的基线，早测与晚测的并集）；
  - `D2(n) 成立 ⇔ speedup_low(n) > drift_band(n)`；
  - **门禁形状：n=1000 与 n=3000 必须同时成立**。n=50/200 只记录不裁决——小形状绝对耗时在亚毫秒级、波动天然占主导，这是预登记的口径选择，不是事后放宽；
  - 措辞纪律：`speedup_low > 1` 但 `≤ drift_band` ⇒ 写「稳定性不达标：波动吞没收益」，**不写**「收益不存在」；`speedup_low ≤ 1` ⇒ 写「无可见收益」。辅助臂（X_mid）读数只作方向佐证，不进裁决。
- **D3（结果等价）**：两臂同形状同 X 的 COUNT 返回值逐轮完全一致（机械比对）；既有 `MyScoreRankEquivalenceTest` E1–E8 在门禁中全绿作为等价性佐证（本笔不改任何查询，属回归确认）。
- **D4（写路径代价有界）**：答卷 INSERT、`casSummarize` 原文、`casSubmit` 原文三类操作各 3 轮，候选臂/基线臂墙钟比值：每类 median ≤ 1.30 且单轮 max ≤ 2.00。任一类超界 ⇒ D4 不成立 ⇒ **NO-GO**（一个附加索引在交卷/汇总热路径上 >30% 的中位写放大不可接受，不进入实施提案；不设「读收益大可豁免」条款）。
- **裁决主式**：`GO ⇔ M1∧M2∧M3∧D1∧D2(1000)∧D2(3000)∧D3∧D4`。GO ⇒ 写另一份**独立实施提案**（含 schema.sql 变更草案、`docker/mysql/migrations/` 脚本草案、实体零改动论证、等价性论证与「脚本提交≠生效」的应用/验证责任说明）；否则 NO-GO，本目录按未采用草案归档。

## 3. 写路径对照的固定语句（生产原文，逐字取自 Mapper 注解）

- 汇总 CAS（`GradingSubmissionMapper.casSummarize`）：
  `UPDATE exam_submissions SET status = 3, subjective_score = #{subjectiveScore}, total_score = #{totalScore}, partial_graded = #{partialGraded}, version = version + 1, updated_time = CURRENT_TIMESTAMP WHERE id = #{id} AND status IN (2, 3)`
  ——每轮对 3000 行 status=2 的专用造数行执行（2→3 首次汇总，total_score 从 NULL 首写：候选臂每轮 3000 次索引条目插入，基线臂 0 次）；轮间以非计时复位语句还原（status=2、total_score=NULL、subjective_score=NULL、partial_graded=0、version=0）。
- 交卷 CAS（`ExamSubmissionMapper.casSubmit`）：
  `UPDATE exam_submissions SET status = #{newStatus}, submit_time = #{submitTime}, submit_type = #{submitType}, version = version + 1, updated_time = CURRENT_TIMESTAMP WHERE id = #{id} AND status = #{expectedStatus}`
  ——每轮对 3000 行 status=1 的专用造数行执行 1→2；轮间非计时复位（status=1、submit_time=NULL、submit_type=NULL、version=0）。status 已被 idx_submissions_sweep / idx_submissions_republish 维护，候选索引是第三个含 status 的索引，代价如实计入。
- 答卷 INSERT：生产形态为 MyBatis-Plus 实体插入（与 `RankAttributionMeasureIT.INSERT_SUBMISSION` 同列集）：`INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, status, objective_score, subjective_score, total_score, grading_status, partial_graded, version, created_time, updated_time) VALUES (…)`，每轮 3000 行新 exam_id（互不撞 uk_exam_student），单事务；臂末非计时 DELETE 本臂全部 INSERT 行，使两臂起测时表行数一致。
- 计时口径：mysql 客户端执行整轮 SQL 文件的墙钟（同文件规模、同客户端、同容器）；绝对值含客户端与解析开销，**只取臂间比值**，不取绝对值下生产结论。

## 4. 证据落位

- 测量原始产物直写仓库外 `D:\code\examOnline-measure\myscore-count-attr\`（规避 target/ 被 clean 清掉的历史坑），收口前拷贝关键件入本目录 `evidence/`；`mvnw clean test` 门禁在证据落位后执行；收口复算 `evidence-sha256.txt` 并 `sha256sum -c` 全 OK。
- 机械复算脚本（`analyze-count-attrib.cjs`）与本文件同批入库，裁决由脚本按本文件算子输出，不人脑算。
