# 预登记判据（写定于正式测量前，冻结不改）

> 冻结时刻：2026-09-29（任何捕获 IT 或容器脚本运行前）。本文件写定后不再修改；测量与裁决按本文件的算子机械复算。若现场事实与本文件前提冲突，如实记录冲突并停，不为 GO 改写本文件。
> 冻结时的仓库入口：`bea34af`（分支 `feature/update-monitor-overview-submission-projection`）。本文件 sha256 在冻结后记入 `evidence/preregistration.sha256.txt`。
> 「测量」的起点定义：第一次运行本变更的捕获 IT 或容器脚本之前。写测试代码与脚本本身不算测量。

## 0. 对象、站点定义与「前序 NO-GO 不再绑定」的依据

被考察站点 s6：`MonitorService.overview` 的答卷主语句——`submissionMapper.selectList(Wrappers.<ExamSubmission>lambdaQuery().eq(ExamSubmission::getExamId, examId))`。现状按实体默认全列取数（13 列，含 LONGTEXT `paper_json` / `answers`）并逐行映射；逐人状态实际只用到 `student_id`、`status` 两个标量；题数只取「第一份非空快照」的 `questions` 数组长度。

- **OLD 臂** = 现状：该语句全列（SELECT 列表以捕获的 OLD 语句原文为准，机械解析，不手写）；题数沿现状代码路径从返回实体第 0 行的 `paper_json` 读取。
- **PROJ 臂** = 冻结的**同一谓词**下把 SELECT 列表限定为**机械枚举出的**标量列 `student_id, status`（列名 = 该路径实际调用的 getter：`getStudentId`——去重名单/进行中名单/逐人行；`getStatus`——状态过滤/逐人行。`getPaperJson` 属题数用途，不计入逐人状态列）；题数改为**至多一条窄单行读**：只取 `paper_json`，谓词 `exam_id = ? AND paper_json IS NOT NULL AND paper_json <> '' LIMIT 1`。
- PROJ 臂在测试上下文中的施加方式（**不改 `src/main`**）：测试侧 `Executor.query` 拦截器对目标语句（msId = `com.exam.submission.mapper.ExamSubmissionMapper.selectList`，主语句相位）在 `proceed()` 前把 SQL SELECT 列表改写为冻结列；`proceed()` 后若主语句返回行 ≥ 1，由同一拦截器**同步**执行上面的窄读（JDBC 单条、同数据源），并把窄读结果供给**未改动的**生产代码（`resolveTotalQuestions` 对第 0 行 `paper_json` 的读取由测试侧守卫行对象按窄读结果供给）。窄读返回 0 行时按现码口径（无快照 → 题数 0）如实记账。

**「前序 NO-GO 不再绑定」的两条新证据（冻结原文）**：

1. 前序主因＝逐人 Redis 草稿 GET（120 次/请求，passMedian 30.9/33.4/42.2ms ≈ 整条请求延迟），该前提**已消失**：`update-monitor-overview-draft-batch-read`（GO，已合入）把草稿读取改为 `draftService.getBatch(examId, activeIds)` 一次 MGET（开工核对时现场确认现码；K5）。
2. 前序引擎＝H2 内存库 + MockMvc 同进程，结构上看不见网络字节成本；本次改**真 MySQL 引擎**（一次性 `mysql:8.0` 容器）三臂实测，字节与时间代价可见。量级锚取自同表同谓词同两列的已入库读数（`spec/changes/archive/project-scalar-only-submission-reads/evidence/adjudication.json`）：n=3000 时 **67,941,000 → 27,000 字节（2516.33×）**；n=1000 时 22,647,000 → 9,000；n=200 时 4,529,200 → 1,800。

**残余语义边界（如实登记）**：现状「第一份非空快照」在无 `ORDER BY` 的行序下本身未定义；窄读同样取任意一行。本测量各形状内**逐行快照内容一致**（同一 40 题紧凑 JSON），故该边界不可观测、不构成两臂差异；若未来行间快照内容不同，两臂理论上可能取到不同行的快照——该说明在实现阶段（仅 GO 时）原样保留。

## 1. 造数形状与分布

- 形状 n ∈ {200, 1000, 3000}。`examId(n) = 976_000_000 + n`；学生 `sid(i) = 976_400_000 + n*1000 + i`（i = 0..n−1）；owner = 开工后注册的真实教师 `teacherId`；另有第二个真实教师账号用于非 owner 403 对拍；`users` 只为 i<10 的学生插入展示名行（其余学生名回退 null，两臂一致）。
- 状态分布：`i < 0.6n` → 进行中(1)；`0.6n ≤ i < 0.85n` → 已交卷(2)；其余 → 已批改(3)。（n=200 → 120/50/30。）
- 长字段（与 s2 生成口径同构，全 ASCII ⇒ 字符数 = 字节数）：
  - `paper_json`：**所有行**非空、**恰 20480 字符**。构型 `{"questions":[{"id":1},…,{"id":40}],"pad":"B…B"}`：题干数组 40×8 + 39 逗号 = 359 字符；固定骨架（`{"questions":[`、`],"pad":"`、`"}`）共 25 字符；`pad` 为 20096 个 `B` ⇒ 359 + 25 + 20096 = 20480。
  - `answers`：**已交卷/已批改行**非空、**恰 2048 字符**（`{"1001":"` + 2037×`A` + `"}`）；**进行中行**按真实链路留 NULL（交卷才落答案，与既有测量夹具同口径）。该口径偏离如实登记：OLD 臂 `answers` 列对进行中行的应传字节按 `COALESCE(LENGTH(...),0) = 0` 计。
- 草稿（Redis，进行中学生）：`exam:draft:{examId}:{sid}`，值 = 12 条答案的规范 JSON；每 60 个进行中学生取 4 个退化形态（i%60 = 0 损坏 / 15 answers 非对象 / 30 缺键 / 45 空串），其余正常——两臂同一输入；退化只影响草稿批读路径（本变更不动）的进度口径。
- 在线键（Redis，进行中学生）：`exam:monitor:online:{examId}:{sid}`，按 i%5 != 4 写入（n=200 时 96 个在线）。
- 异常日志：i%10==0 → severity {1,1,3}；否则 i%5==0 → {1,2}；其余无。事件时间 = T0 − (60−k) 秒。
- `exams` 行：`created_by = teacherId`、`status = 1`、`published = 1`、`paper_id = 976_000_001`。
- T0 = 一次造数的秒级时间戳。逐形状落盘：设计行数与实测行数、`LENGTH` 极值、非空计数（M2 依据）。
- **退化夹具**（仅 S1 语义层，不参与 S2/S3 的计时形状）：
  - **D1 空场**：examId = 976_000_101，0 行答卷；PROJ 主语句 0 行 ⇒ 窄读 0 条（按现码口径），两臂题数均 0。
  - **D2 全空快照**：examId = 976_000_102，3 行已交卷、`paper_json` 全 NULL、无进行中；PROJ 主语句 3 行 ⇒ 窄读 1 条返回 0 行；两臂题数均 0 且进度口径不变。
  - **D3 全已交卷**：examId = 976_000_103，3 行已交卷、`paper_json` 非空（同构 20480）、无进行中；无 presence/草稿键（在线口径为全离线）；两臂题数均 40、逐人进度 100%。

## 2. 测量装置

### 2.0 引擎与容器（一次性，用毕销毁）

- MySQL：一次性 `mysql:8.0` 本地容器（tmpfs `/var/lib/mysql`、`127.0.0.1:13319`、库名 `monitor_projection_measure`、`--default-character-set=utf8mb4`、整库执行仓库 `src/main/resources/schema.sql` 须退出码 0）；记镜像 digest、`SHOW CREATE TABLE exam_submissions`、`SHOW INDEX`；用毕 `docker rm -f` 并留 `docker ps -a` 零匹配证据；不连共享 dev、不写 dev 库。
- Redis：一次性 `redis:7.2-alpine` 本地容器（`127.0.0.1:6390`、db15），仅承载本测量的 presence/草稿键；用毕销毁留零匹配证据。本机 6379 属其他项目，不碰。
- IT 上下文：`MonitorOverviewProjectionMeasureIT`（`@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("test")`；类名以 IT 结尾，不进 Surefire 全量门禁）；`@DynamicPropertySource` 把 `spring.datasource.dynamic.datasource.{master,slave}` 指向容器 MySQL（URL 参数与 dev 同款：`useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true&useSSL=false&allowPublicKeyRetrieval=true`）；MySQL 口令经环境变量 `MONITOR_MYSQL_PWD` 注入（不落盘、不进命令日志，日志中的该参数一律以 `***` 记录）；Redis 经环境变量 `REDIS_HOST=127.0.0.1`、`REDIS_PORT=<一次性容器端口>` 指向。缺失 `-Dmeasure.mysql.url` 时 IT 直接失败（fail fast），不在 H2 上悄悄跑。
- 定时任务隔离（沿用既有手法）：`@TestPropertySource` 把 `exam.schedule.fixed-delay-ms/initial-delay-ms` 拉到 3600000（测试档 `exam.taking.sweep` 已为 3600000），避免定时写污染记账。
- 命令（相位化；原始产物落 `D:\code\examOnline-measure\monitor-projection\`，不写 C 盘、不留在 target）：
  `node run-monitor-projection-mysql.mjs --phase start --port 13319`
  `node … --phase start-redis --port 6390`
  `node … --phase setup`（建库 + 整库 schema.sql）
  `node … --phase rounds`（以 -D 注入 MySQL URL、以环境变量注入口令运行 IT，IT 内含全部形状与 S1 层）
  `node … --phase bytes`（容器侧机械字节账 + 逐形状复核计数）
  `node … --phase stop`（销毁两容器，留零匹配证据）

### 2.1 三臂、轮次与计时（端到端）

- 每形状三臂位：**OLD**（全列）/ **PROJ**（投影 + 同计时窗口内至多一条窄读）/ **OLDrep**（与 OLD 完全同文的等价副本臂，用于漂移噪声带）。
- 每臂 2 次预热（不计时）+ 5 个计时轮；第 r 轮（r=1..5）臂序 = `[OLD, PROJ, OLDrep]` 左移 (r−1) mod 3 位。并发 = 1（逐轮串行）。
- 每轮测量：MockMvc 请求 `GET /api/exams/{examId}/monitor/overview`（owner 教师 JWT；端到端，含安全过滤链、Service、MyBatis、MySQL 与 Redis 调用），`System.nanoTime` 包住 `mockMvc.perform(...).andReturn()`；记 ns、退出码（0 = HTTP 200 且响应语义（totalStudents/totalQuestions）校验通过；非 0 = 异常或校验失败）、ISO 时间戳。PROJ 臂的窄读发生在拦截器内、返回生产代码之前 ⇒ 必在该轮计时窗口内（S4 逐轮留痕）。
- 失败轮原样保留（attempt=1 失败记录）并在同位置补跑一次（attempt=2）；某臂某形状成功计时轮 < 5 ⇒ 该形状测量无效（M3）。
- 计时规则：只使用每臂每形状的**同一口径绝对量**（逐轮 ns）；S3 比较逐轮值；不跨轮相减求占比、不得挑轮、不得以中位数替代「每一轮」。

### 2.2 容器侧字节算子（S2 载体，确定性、非计时）

- 对每个臂的每条捕获语句（PROJ 含主语句与窄读两条），从捕获 JSON 取 SQL 原文 + 参数值，机械做 `?`→字面量替换（替换次数必须 == 参数个数，前后文本落盘）；然后容器侧执行：
  `SELECT COALESCE(SUM(Σ_cols COALESCE(LENGTH(CAST(col AS CHAR)),0)),0) AS bytes, COUNT(*) AS rows FROM ( <替换后语句> ) t;`
  列集合 = 该语句 SELECT 列表（机械解析）。窄读的 `LIMIT 1` 在子查询内生效 ⇒ 计一条行（20480 字节）。PROJ 侧字节 = 主语句 + 窄读；OLD 侧字节 = 主语句；OLDrep 与 OLD 同文同值（佐证）。

### 2.3 S1 语义层口径（同一次 IT 运行内）

- 每形状以 owner 教师 token 各执行 OLD 与 PROJ 一次，取响应 JSON 规范化后**严格相等**：人数统计、状态分组（在线/离线/已交卷）、逐学生进度（answeredCount/progressPercent）、异常高亮与排序（数组顺序）、响应形状全部参与比较；时钟相关字段仅 `students[].lastAbnormalTime` 按「是否为空/是否为正」比较（规范化：null → `-T`，非 null → `+T`）。
- 权限对拍：非 owner 教师 token → 两臂同一 HTTP 状态与同一错误码/消息（403）；admin token → 两臂规范化响应相等。
- 在线/离线口径：以预置 presence 键的在线/离线混合（主形状）与全离线（D1/D2/D3）输入对拍；「Redis 异常 → 全员离线」的降级路径由两臂**同一代码**承担（本变更只改答卷语句），不单独注入 Redis 故障——如实登记为未单独演练项。
- **运行时护栏**（两臂每次执行均适用）：返回实体的 `getAnswers()` 一经调用即抛断言错（两臂都不允许读）；`getPaperJson()` 在 OLD 臂放行并记读取行号（事后断言读取行号构成前缀 `[0..k]`）、在 PROJ 臂仅允许第 0 行以窄读供给值返回（或退化夹具的全空扫描），其余行抛错；PROJ 臂返回实体长字段必须为 null 且库内同谓词行非 null 计数 > 0（F1/D3 要求 > 0；D2 如实记 0）；捕获 SQL 的 SELECT 列表 PROJ == 冻结列且 ≠ OLD。

## 3. 判据算子与裁决（单站点 s6）

- **M1 捕获有效性** ⇔ 主语句 SELECT 列表：PROJ == `student_id,status`（空白规范化）且 OLD ≠ PROJ 且 OLD 含 `paper_json` 与 `answers`；窄读 SELECT 列表 == `paper_json` 且含 `LIMIT 1`；`?`→字面量替换计数全部 == 参数个数；OLDrep 与 OLD 捕获文本逐字相同；IT 捕获问题清单为空。
- **M2 容器有效性** ⇔ `schema.sql` 整库执行退出码 0；镜像 digest / `SHOW CREATE TABLE` / `SHOW INDEX` 落盘；容器侧逐形状复核：答卷行数 == n、`paper_json` 非空行 == n 且 LENGTH 极值 == 20480、`answers` 非空行 == n − 进行中数 且 LENGTH 极值 == 2048（非空集合上）、行为日志行数 == 设计值。
- **M3 轮次完整性** ⇔ 每形状每臂 ≥ 5 个成功计时轮，ISO 时间戳与尝试记录齐备。
- **S1 语义等价** ⇔ 每形状：owner 响应规范化后两臂严格相等 ∧ admin 响应相等 ∧ 非 owner 两臂同状态同错误 ∧ 护栏全过（含 D1/D2/D3 的响应相等与窄读记账）。
- **S2 字节收益** ⇔ 每形状：`ratio = bytes_LENGTH(OLD) ÷ bytes_LENGTH(PROJ，含那条窄读) ≥ 5.0`（冻结倍数，不得下调）。措辞纪律：不成立写「**字节收益不成立**」。
- **S3 单侧无回归** ⇔ 每形状：PROJ 臂**每一轮** wall-clock ≤ 噪声带上界，其中噪声带上界 = `max(OLD ∪ OLDrep 全部成功轮 ns)`（每一轮都要满足，不得改为中位数比较）。措辞纪律：不成立写「**不稳定/无净收益**」；并如实记「额外往返是否抵消字段节省」的检验结论（S2 ∧ S3 均通过 ⇒ 未抵消；否则按对应措辞登记）。
- **S4 形状账目（本站点专用）** ⇔ 每形状：主语句两臂条数相同（1/1）且返回行数相同（n/n）；窄读为 PROJ 臂新增的至多 1 条——F1 记 1 条返回 1 行、D1 记 0 条、D2 记 1 条返回 0 行；窄读必须记在 PROJ 臂同轮的记账内（**不得把这条新增语句排除在 S3 计时之外**）。
  - 本站点专用理由：s6 的唯一读形态改动 = 主语句投影 **+** 新增一条窄读；「形状账目」必须显式承认并约束这条新增语句——若只比主语句条数，会把窄读藏进账外，而前序反例「额外往返抵消字段节省」正是要防的。
- **裁决主式**：`GO ⇔ (∀形状: M1∧M2∧M3) ∧ (∀形状: S1∧S2∧S3∧S4)`。M 组不成立 ⇒ 该形状「测量无效」，不作收益结论，停手保留现场并回报；S1 任一形状不成立 ⇒ 停手；S2/S3/S4 不成立 ⇒ 按上表措辞如实登记、不实施。
- 措辞纪律总则：S2 与 S3 的失败措辞不得混用；不得以中位数替代「每一轮」；不得挑轮；不得下调 5.0 倍数；测量环境（一次性本地容器、单机、空并发、MockMvc 测试上下文）结论不外推生产 MySQL/Tomcat，不构成交卷或监考 P99 结论。

## 4. 证据落位与复算

- 原始产物（脚本、逐轮原始输出、IT JSON、容器日志）直写仓库外 `D:\code\examOnline-measure\monitor-projection\`；收口前把关键件拷贝入本目录 `evidence/`。
- IT 侧产出：`capture-manifest.json`（rev/label/PREREGISTRATION 原文 sha256 与重算值）、`capture-n{200,1000,3000}.json`（两臂捕获 SQL 与参数、SELECT 列表、护栏计数、S1 对拍结论、窄读记账）、`rounds-n{200,1000,3000}.json`（逐轮 ns/退出码/ISO/尝试/窄读记账）、`seed-counts.json`。
- 容器侧产出：`container-start.log`、`container-setup.log`、`container-stop.log`、`bytes-n{200,1000,3000}.json`（含逐形状复核计数）。
- 机械复算脚本：`analyze-monitor-projection.cjs`（与本文件同批入库；输入 = IT JSON + 容器 JSON/日志；输出 = `adjudication.json` 与 `verdict-console.txt`），裁决由脚本按本文件算子输出，不人脑算。
- 证据清单 `evidence/evidence-sha256.txt`，收口 `sha256sum -c` 须 rc=0；清单只列 git 跟踪得到的文件（本目录 `evidence/*.log` 由 `.gitignore` 负向规则放开、可入库）。
