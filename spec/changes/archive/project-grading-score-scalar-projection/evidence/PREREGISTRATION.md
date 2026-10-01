# 预登记判据（写定于正式测量前，冻结不改）

> 冻结时刻：2026-10-01（任何捕获 IT 或容器脚本运行前）。本文件写定后不再修改；测量与裁决按本文件算子机械复算。若现场事实与本文件前提冲突，如实记录冲突并停手，不为 GO 改写本文件；装置级调整（如 tmpfs 大小）须预授权并在回报中披露。
> 冻结时的仓库入口：`d6232e5`（分支 `feature/update-monitor-overview-submission-projection`）。本文件 sha256 在冻结后记入 `evidence/preregistration.sha256.txt`。
> 「测量」的起点定义：第一次运行本变更的捕获 IT 或容器脚本之前。写测试代码与脚本本身不算测量。

## 0. 对象、站点定义与归因结论

被考察对象 = 判分/成绩域三条读路径上的 5 个读语句单元 + 2 个待核单元。站点 msId 冻结：

- `M1 = com.exam.grading.mapper.GradingSubmissionMapper.selectList`（progress 主语句相位）
- `M2 = com.exam.grading.mapper.SubjectiveGradeMapper.selectList`（progress 主观行相位）
- `M3 = com.exam.grading.mapper.GradingSubmissionMapper.selectList`（summarize 主语句相位）
- `M4 = com.exam.grading.mapper.SubjectiveGradeMapper.selectList`（summarize 主观行相位）
- `M5 = com.exam.grading.mapper.GradingSubmissionMapper.selectList`（publishPreview 相位）
- 写参照（非投影目标）：`com.exam.grading.mapper.GradingSubmissionMapper.casSummarize`

端点 → 单元归属（冻结）：`progress = {M1, M2}`、`summarize = {M3, M4}`、`publishPreview = {M5}`。同一条 msId 在不同端点相位下对应不同单元，由臂运行的 RunState 端点上下文区分（单线程串行，无歧义）。

- **OLD 臂** = 现状：实体默认全列取数（SELECT 列表以捕获的 OLD 语句原文为准，机械解析，不手写。`GradingSubmission` 实体映射 15 列——表 `exam_submissions` 的 `paper_json`/`start_time`/`deadline_time`/`submit_type`/`answers_missing` 不在实体映射内；`SubjectiveGrade` 实体映射 16 列）。
- **PROJ 臂** = 冻结列投影（列名 = 机械重导的消费字段，**实体声明序**；施加方式为测试侧 `Executor.query` 拦截器在 `proceed()` 前对目标语句的 `LambdaQueryWrapper` 调用 `.select(...)`，与实施阶段生产写法同源，测量期间不改一行 `src/main`）：
  - M1 = `id,grading_status`
  - M2 = `submission_id,score`
  - M3 = `id,grading_status,objective_score`
  - M4 = `submission_id,question_id,score`
  - M5 = `student_id,objective_score,subjective_score,total_score,partial_graded`
- **与指导草案不一致处（机械证据裁定，显著登记）**：M5 草案列集漏 `objective_score`；现码 `ScoreService.publishPreview` 存在 `item.setObjectiveScore(submission.getObjectiveScore())`（ScoreService.java L250，2026-10-01 现场引文）。按卡面 1.1 规则「以机械证据为准」，冻结列集含 `objective_score`。M1–M4 重导结果与草案一致。
- **M6 `ScoreExportService.forEachSubmissionPage` = 排除**：其 pageConsumer 中 `exportQuestionDetail`/`exportQuestionStats` 把整页传入 `resolveQuietly → QuestionScoreResolver.resolveBatch`，后者对页内每份答卷执行 `paperReader.parseAnswers(submission.getAnswers())`（QuestionScoreResolver.java L104；单份路径 L62 同）——同一 helper 的消费方合法读取长字段，投影将破坏逐题得分明细与题目统计导出。不改。
- **M7 `ObjectiveGradingService.loadSubjectiveGrades` = 排除**（卡面默认）：判分热写路径预取，消费字段仅 `submission_id/question_id/id`（upsert 只读 `existing.getId()`），但写路径正确性优先、收益场景（重判）频度低，未主张纳入。不改。
- **确认不是缺口（不纳入）**：`ScoreExportService.rankBySubmissionId` 已带 `.select(getId, getTotalScore)`；`ObjectiveGradingService.doGrade` 读 `getAnswers()`；`QuestionScoreResolver` 消费 `getAnswers()` 与 `comment`；`SubjectiveGradeMapper` 工作台自定义 @Select 显式列清单含 `g.student_answer`。均正当，取数语句不动。
- **残余语义边界（如实登记）**：M2/M4 同表同 msId 但谓词不同（M2 按 `exam_id = ?`、M4 按 `submission_id IN (...)`），列集不同（{submission_id,score} vs {submission_id,question_id,score}）——两站点独立冻结、独立裁决；实施时各自 `.select(...)` 互不影响（同一 Service 类内两条语句各自构造 wrapper）。

## 1. 造数形状与分布

- 形状 n ∈ {200, 1000, 3000}（每场答卷数），与 `project-scalar-only-submission-reads`、`reattribute-monitor-overview-projection` 同口径。量级锚（grep 锚引用，不写裸行号）：`docs/需求决策记录.md` §二十（grep 锚 `exam_bench`）＝独立 scratch 库 10 万答卷 / 500 场考试；`loadtest/README.md` §4（grep 锚 `5000 笔`）＝单场 5000 人交卷压测包络；前序两卡 PREREGISTRATION 同形状。
- 每形状三场考试（三端点各一场，互不共用，避免 summarize 写副作用污染其余端点）：
  - `P(n) = 981_100_000 + n`（progress，exams.status=2 已结束）；`S(n) = 981_200_000 + n`（summarize，臂前 exams.status=2）；`V(n) = 981_300_000 + n`（preview，exams.status=3 已批改）。`published=1`、`duration_minutes=60`、`created_by = owner = 981_000_001`、`version=0`。
  - 学生 `sid(n,i) = 982_000_000 + n*100_000 + i`（i = 0..n−1），答卷 (exam, student) 唯一；**users 行只为 V(n) 学生插入**（用户名 `mu{n}_{i}`、名 `生{i}`；P/S 不插 users——两条路径不读 users）。
  - exam_snapshots 行**只为 S(n)** 插入（exam_json=`{}`，paper_json 冻结构型：2 道简答题 `questionId ∈ {981_999_901, 981_999_902}`、type=4、score=50.0，root 含 paperId/title/totalScore）——`paperReader.readByExamId` 只读 exam_snapshots（与 exam_submissions.paper_json 无关，如实登记）。
- 答卷行分布（全部行 `answers` 非空、`paper_json` 非空——三场考试答卷均为已交卷/已批改态，与真实链路「交卷落答案」一致）：
  - P(n)：status=2（SUBMITTED）；`grading_status = (i%10==9) ? 2 : (i%10==8) ? 0 : 1`（覆盖 graded/failed/pending 三分支）；objective_score=score(i)。
  - S(n)：status=2；grading_status=1；objective_score=score(i)；subjective_score/total_score/partial_graded 臂前为 NULL/NULL/0（由 summarize 写入，轮间复位）。
  - V(n)：status=3（GRADED）；objective_score=score(i)、subjective_score=subjective(i)、total_score = 二者之和（**全行非空**，满足谓词 `total_score IS NOT NULL`）；`partial_graded = (i%4==0) ? 1 : 0`。
  - `score(i) = 40 + (i * 7919 % 300) / 10`（BigDecimal 1 位小数，i=0 → 40.0 全场最低）；`subjective(i) = (i % 7) * 10`（0..60，1 位小数）。
- subjective_grades（简答题数冻结 **2** ⇒ 一卷一题一条，每场 2n 行）：仅 P(n) 与 S(n) 各 2n 行，question_id ∈ {981_999_901, 981_999_902}（q=0,1）；`score = ((i+q)%4 != 0) ? score(i)/2 : NULL`（有终分/未批混合，驱动 partial 与 subjectiveGraded 分支）；question_number = q+1；grader_id/graded_time 仅 score 非空行填写。
- 长字段（全 ASCII ⇒ 字符数 = 字节数；answers/paper_json 沿用归档口径，student_answer 及以下为**设计选择，显式标注**）：
  - `answers` 恰 2048 字节：`{"1001":"` + 2037×`A` + `"}`；
  - `paper_json`（exam_submissions 列）恰 20480 字节：`{"snapshot":"` + 20465×`B` + `"}`；
  - `student_answer` 恰 **512** 字节：512×`C`（设计值：与工作台展示的简答作答量级相符，未找到文档锚，冻结为设计值）；
  - `suggested_detail` 恰 64 字节：64×`D`（设计值）；`comment` 恰 64 字节、**仅 score 非空行非空**（设计值）。
- T0 = 一次造数的时刻。逐形状落盘 V1 机械核对：三场答卷行数各 == n、subjective 行数各 == 2n（P/S）、users 行数 == n（仅 V 段）、`answers` 非空行 == 3n 且 LENGTH 极值 == 2048、`paper_json` 非空行 == 3n 且 LENGTH 极值 == 20480、`student_answer` 非空行 == 4n 且 LENGTH 极值 == 512、comment 非空行 == score 非空行数（机械查询取证，不靠日志叙述）。

## 2. 测量装置

### 2.0 引擎与容器（一次性，用毕销毁）

- MySQL：一次性 `mysql:8.0` 本地容器（tmpfs `/var/lib/mysql` size=3g【装置级参数，调整须预授权并披露】、`127.0.0.1:13321`、库名 `grading_score_projection_measure`、utf8mb4；整库执行仓库 `src/main/resources/schema.sql` 须退出码 0；记镜像 digest、`SELECT VERSION()`、`SHOW CREATE TABLE exam_submissions`/`subjective_grades`、`SHOW INDEX`）；用毕 `docker rm -f` 并留 `docker ps -a` 零匹配证据；不连共享 dev、不写 dev 库、不碰 sport-verify-* 容器。
- Redis：一次性 `redis:7-alpine` 本地容器（`127.0.0.1:16379`、db15，经 `REDIS_PORT` 环境变量注入 IT 上下文）。本机 6379 属本仓库测试档 Redis，不碰。用毕销毁留零匹配证据并清 db15 键留计数。
- IT 上下文：`GradingScoreProjectionMeasureIT`（类名 IT 结尾，不进 Surefire 全量门禁；`@SpringBootTest + @ActiveProfiles("test")`）；`@DynamicPropertySource` 把 `spring.datasource.dynamic.datasource.{master,slave}` 指向容器 MySQL（URL 参数与 dev 同款：`useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true&useSSL=false&allowPublicKeyRetrieval=true`）；缺失 `-Dmeasure.mysql.url` 时 IT 直接失败（fail fast），不在 H2 上悄悄跑。MySQL 口令经环境变量注入（不落盘、不进命令日志）。**master 与 slave 指向同一容器**（progress 挂 `@DS("slave")`，本装置下 slave==master 同引擎——单机单容器边界，如实披露）。
- 定时任务隔离（沿用既有手法）：`@TestPropertySource` 把 `exam.schedule.fixed-delay-ms/initial-delay-ms` 拉到 3600000（测试档 `exam.taking.sweep` 已为 3600000），测量窗口内不存在定时写入或目标语句级联流量。
- 端点调用方式（**装置设计选择，显式标注**：沿用 `ScalarReadsAttributionMeasureIT` 的进程内直调先例）：SecurityUtil.set(owner LoginUser) 后直调 `gradingQueryService.progress(P(n))` / `scoreService.summarize(S(n))` / `scoreService.publishPreview(V(n))`，计时窗口 = `System.nanoTime` 包住 Service 调用（Spring 代理、@DS 路由、@Transactional、MyBatis、拦截器包装全部在窗口内——三臂同摊，不提供归因增量）。不采用 MockMvc：过滤链开销两臂同摊且与本卡归因对象无关；S1 oracle 以 Service 返回 DTO 全字段为准。
- 命令（相位化脚本 `run-grading-score-projection-mysql.mjs`；原始产物直写 `D:\code\examOnline-measure\grading-score-projection\`，不写 C 盘、不留在 target）：
  `--phase start`（MySQL 容器）→ `--phase start-redis` → `--phase setup`（建库 + 整库 schema.sql + SHOW 落证）→ `--phase rounds`（以 -D 注入 MySQL URL、以环境变量注入口令运行 IT，IT 内含全部形状/端点/臂/轮）→ `--phase bytes`（容器侧机械字节账 + V1 复核）→ `--phase stop`（销毁两容器 + 清 Redis 键，留零匹配证据）。

### 2.1 三臂、轮次与计时

- 每端点每形状三臂位：**OLD**（全列）/ **PROJ**（冻结投影，覆盖该端点全部单元）/ **OLDrep**（与 OLD 完全同文的等价副本臂，用于漂移噪声带）。PROJ 臂对 progress 同时施加 M1+M2、对 summarize 同时施加 M3+M4、对 preview 施加 M5。
- 每臂 2 次预热（不计时，summarize 预热前同样执行复位）+ 5 个计时轮；第 r 轮（r=1..5）臂序 = `[OLD, PROJ, OLDrep]` 左移 (r−1) mod 3 位。并发 = 1（逐轮串行、左轮转）。
- 每轮记录：ns（System.nanoTime）、退出码、ISO 时间戳、attempt（1；失败同位补跑一次记 attempt=2）、该轮全部捕获语句清单、目标语句条数/行数、casSummarize 逐次参数（summarize）。失败轮原样保留（不重跑取胜）；某臂某端点某形状成功计时轮 < 5 ⇒ 该形状测量无效（M3）。
- 退出码语义（冻结）：0 = 调用无异常且端点 sanity 成立——progress：返回非 null 且 `submittedCount == n`；summarize：`summarized == n && skipped == 0 && examGraded == true`；preview：`summarizedCount == n` 且 items.size() == n。非 0 = 异常或 sanity 不成立。
- **summarize 复位算子（冻结原文；每臂每轮计时窗口前执行，窗口外）**：
  `UPDATE exam_submissions SET status = 2, subjective_score = NULL, total_score = NULL, partial_graded = 0, grading_status = 1, version = 0 WHERE exam_id = <S(n)>`；
  `UPDATE exams SET status = 2, version = 0 WHERE id = <S(n)>`。
  progress/preview 为只读端点，臂间轮间不复位（只读保证臂前状态一致）。

### 2.2 S1 语义层口径

- **progress / preview（只读端点）**：每形状在计时段之外执行 OLD/PROJ 各一次，响应 DTO 经 Jackson `valueToTree` 规范化后**严格相等**：progress = `GradingProgressResponse` 全部 7 字段（submittedCount/gradedCount/failedCount/pendingCount/subjectiveTotal/subjectiveGraded/partialGradedCount）；preview = `ScorePreviewResponse` 全部字段（examId/examTitle/summarizedCount/partialGradedCount/items）且 items 逐项（studentId/studentName/objectiveScore/subjectiveScore/totalScore/rank/partialGraded）与顺序参与比较。每个计时轮另按退出码语义做 sanity。
- **summarize（写端点）**：每个计时轮记录 `SummarizeStats`（summarized/skipped/examGraded）与该轮捕获的 `casSummarize` 逐次参数序列（msId、次数、逐次 `id/subjectiveScore/totalScore/partialGraded` 字符串化）；同形状同轮跨三臂**完全相同**（次数 == n 且逐参相等）即写行为不变。响应 oracle（三元组）逐轮跨臂相等。
- **运行时护栏（两臂每次执行均适用）**：目标语句返回实体一律经「读取即失败」Mockito spy 包装——`GradingSubmission.getAnswers()`、`SubjectiveGrade.getStudentAnswer()` 一经调用即抛 AssertionError（canary 自证包装确会抛，失败即测量无效）；OLD 臂实体 `answers` 非 null 计数须 > 0（长字段确经传输层）；PROJ 臂返回实体长字段必须为 null 且库内同谓词行长字段非 null 计数 > 0（证明是投影而不是没数据）；包装成本三臂同摊。

### 2.3 容器侧字节算子（S2 载体，确定性、非计时）

- 对每单元每臂的捕获语句（取 `targetStatements[0]` 的 `literalSql`，即机械 `?`→字面量替换后的原文，替换次数必须 == 参数个数），由 `--phase bytes` 在容器内执行：
  `SELECT SUM(Σ_cols COALESCE(LENGTH(CAST(col AS CHAR)),0)) AS bytes, COUNT(*) AS cnt FROM ( <替换后语句> ) t;`
  其中列集合 = 该臂语句 SELECT 列表（机械解析 SELECT 与 FROM 之间文本）。另落逐列分解（每列一个 SUM 别名）供证据。ratio = bytes(OLD) ÷ bytes(PROJ)；OLDrep 与 OLD 同文同值（佐证）。

### 2.4 预登记自检

- IT 运行前机械复算 `evidence/PREREGISTRATION.md` 的 sha256 并与 `evidence/preregistration.sha256.txt` 记录比对，不一致即测量无效。检索路径：先 `spec/changes/project-grading-score-scalar-projection/evidence/`，后归档态 `spec/changes/archive/project-grading-score-scalar-projection/evidence/`（两路径均冻结于此；用哪个以 capture JSON 的 `preregistration.file` 为准如实记录）。

## 3. 判据算子与裁决（逐单元 M1–M5；允许部分 GO）

- **M1 捕获有效性** ⇔ 逐单元逐形状：PROJ 捕获 SELECT 列表 == 冻结列（空白规范化后逐字相等）∧ OLD ≠ PROJ ∧ OLD 列表含 `answers` ∧ OLDrep 与 OLD 捕获文本逐字相同 ∧ `?`→字面量替换次数全部 == 参数个数 ∧ IT problems 清单为空。
- **M2 容器有效性** ⇔ `schema.sql` 整库执行退出码 0；镜像 digest / `SELECT VERSION()` / `SHOW CREATE TABLE` / `SHOW INDEX` 落盘；V1 逐形状机械复核全过。
- **M3 轮次完整性** ⇔ 每端点每臂每形状 ≥ 5 个成功计时轮，ISO 时间戳与 attempt 记录齐备。
- **S1 语义等价** ⇔ 每形状：progress/preview 响应规范化严格相等 ∧ summarize 三元组逐轮跨臂相等 ∧ casSummarize 次数与逐次参数逐轮跨臂相等 ∧ 运行时护栏全过。**S1/S3 是端点级判据：任一不成立 ⇒ 该端点全部单元 NO-GO（保守归因，不做逐单元拆分重测）**。
- **S2 字节收益** ⇔ 逐单元逐形状：`ratio = bytes_LENGTH(OLD) ÷ bytes_LENGTH(PROJ) ≥ 5.0`（冻结倍数，不得下调）。措辞纪律：不成立写「**字节收益不成立**」。S2/S4 按语句归单元，单元级不成立仅否决该单元。
- **S3 单侧无回归** ⇔ 逐端点逐形状：PROJ 臂**每一轮** wall-clock ≤ 噪声带上界 = `max(OLD ∪ OLDrep 全部成功轮 ns)`（每一轮都要满足，不得改为中位数比较）。措辞纪律：不成立写「**不稳定/无净收益**」；并如实记「额外往返是否抵消字段节省」的检验结论（S4 无新增往返 ∧ S2 ∧ S3 均过 ⇒ 未抵消；否则按对应措辞登记）。
- **S4 形状账目** ⇔ 逐单元逐形状：目标语句条数两臂相同（1/1）且返回行数相同（M1/M3/M5 = n；M2/M4 = 2n）∧ 每轮捕获的全部语句条数三臂相同 ∧ summarize 的 casSummarize 条数三臂均为 n。条数变化即 NO-GO。
- **NO-GO 判（写死，不得重测取宠、不得改判据）**：S1 任一破坏；S2 < 5.0；S3 任一轮超带；S4 条数变化；实施需要动谓词/索引/排序/缓存/写语句（超出本卡授权）。命中即该单元不实施。
- **裁决主式**：单元 GO ⇔ 该单元所属端点（逐形状）M1∧M2∧M3 全过 ∧ S1∧S3 过 ∧ 该单元 S2∧S4 过。M 组任一不成立 ⇒ 该形状「测量无效」，不作收益结论，停手保留现场并回报。M6/M7 维持排除（不参与裁决）。
- 措辞纪律总则：S2 与 S3 的失败措辞不得混用；不得以中位数替代「每一轮」；不得挑轮；不得下调 5.0 倍数；测量环境（一次性本地容器、单机、空并发、进程内测试上下文）结论不外推生产 MySQL/Tomcat，不构成判分或成绩发布 P99 结论。
- E6 说明：本卡算子不含 EXPLAIN ANALYZE 解析（无执行计划类判据）；E6 的解析器纪律在 S2 的 SELECT 列表解析上以「机械解析 + 逐字比对 + OLDrep 同文佐证」等价落实。

## 4. 证据落位与复算

- 原始产物（脚本运行日志、IT 控制台原件、逐轮原始 JSON、容器日志）直写仓库外 `D:\code\examOnline-measure\grading-score-projection\`（装置脚本与归因底稿在 `D:\code\examOnline-verify\grading-score-projection\`）；收口前把关键件拷贝入本目录 `evidence/`。
- IT 侧产出：`capture-manifest.json`（rev/label/PREREGISTRATION sha256 复算与匹配/canary）、`rounds-n{200,1000,3000}.json`（逐端点逐臂逐轮 ns/退出码/ISO/attempt/语句账目/casSummarize 参数/S1 对拍/护栏计数/捕获 SQL 原文与 literalSql）、`seed-counts.json`（V1）。
- 容器侧产出：`container-start.log`、`container-setup.log`、`container-stop.log`、`bytes-n{200,1000,3000}.json`（含逐列分解与 V1 复核）。
- 机械复算脚本：`analyze-grading-score-projection.cjs`（本目录入库；输入 = rounds JSON + bytes JSON；输出 = `adjudication.json` 与 `verdict-console.txt`），**裁决由脚本按本文件算子输出，不人脑算**；脚本是 adjudication.json 的唯一产出者。
- 证据清单 `evidence/evidence-sha256.txt`，收口 `sha256sum -c` 须 rc=0；清单只列 git 跟踪得到的文件（本目录 `evidence/*.log` 由 `.gitignore` 负向规则放开、可入库；60MB 级控制台原件留库外，在 `evidence/measurement-rounds.md` 登记绝对路径与 sha256）。
