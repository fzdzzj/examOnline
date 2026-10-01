# 装置修正登记（冻结判据未改；仅装置级调整与脚本首版缺陷修正，如实披露）

> 冻结件 `PREREGISTRATION.md`（sha256 `bb4f289b…e39`）零改动。本文件登记测量正式生效前的全部废弃尝试与装置级调整；被废弃的产物留库外 `D:\code\examOnline-measure\grading-score-projection\discarded-tmpfs3g\`，不参与裁决。

## 1. 脚本缺陷登记（均在任何有效轮次产生前发现；第 1–3 条为首版缺陷，第 4 条为 skip-log-bin 落地时的装置参数丢失）

1. **attempt-1（12:01:33 前后）**：IT `insertExam` 把 `exams.paper_id` 插 NULL，触发 `Column 'paper_id' cannot be null`（schema NOT NULL 约束）。夹具缺陷，未产生任何轮次数据。修正：paper_id 以冻结常量 981_999_900 写入。
2. **attempt-1 同轮**：runner 用命令行传 `-Dmeasure.mysql.url`，URL 内 `&` 被 cmd.exe 当命令分隔符截断（it-run.log 可见 `characterEncoding`/`serverTimezone` 等被当作命令的残迹）。修正：改为传 `-Dmeasure.mysql.port/-Dmeasure.mysql.database` 两个纯 ASCII 参数，URL 参数串在 IT 内组装（E2 纪律）。
3. **attempt-2（12:03:50 前后）**：`insertExam` 修正后 SQL 含 6 个 `?`（第 4 个为 class_id）但只绑 5 个值，`No value specified for parameter 6`。修正：补齐 class_id 的 null 绑定。
   attempt-2 运行产生的 capture-manifest.json（prereg sha256 现场复算 match=true、canary 通过、problems 空、shapesDone=[]）同样视为废弃预检，已归入 discarded-tmpfs3g/。

4. **装置参数丢失（加入 `--skip-log-bin` 后的首次 `--phase start`，14:15:12）**：该参数首版写在**镜像名之前**（docker 选项位），`docker run` 以 `unknown flag: --skip-log-bin`（exit 125，见 `container-start.log` 同刻条目）拒绝——参数**丢失、容器未创建**；紧随的 `--phase setup` 报 `No such container: gsproj_mysql_measure`。修正：mysqld 参数须写在**镜像名之后**（`... mysql:8.0 --skip-log-bin`；docker 只把镜像名之前的 token 当 `run` 选项）。**独立探针闭环**（一次性容器，用后即毁）：`docker run -d --name gsprobeB -e MYSQL_ROOT_PASSWORD=<redacted> mysql:8.0 --skip-log-bin` → `SELECT @@log_bin, @@log_bin_basename` = `0  NULL`，`docker inspect --format {{json .Config.Cmd}}` = `["--skip-log-bin"]`；对照把参数放选项位复现 `exit 125`。为避免「脚本常量自证」，`--phase start` 已改为落**真实装置事实**（`docker inspect` 的 PortBindings / Tmpfs / Config.Cmd + 容器内 `SELECT @@log_bin` 原文并硬校验），详见 §4。

## 2. 装置级调整：tmpfs 3g → 8g（预授权依据：PREREG §2.0 冻结文本内标注「装置级参数，调整须预授权并披露」）

- **attempt-3（12:05 起）**：n=200、n=1000 两形状全部三端点轮次跑完（零 MEASURE PROBLEM）；n=3000 种子阶段 12000 行 subjective 批次之前、users 批次中途（1201/3000）挂起：MySQL 报 `ERROR 1114 The table '/tmp/#sql1_63_0' is full`，`df` 显示 `tmpfs 3.0G 3.0G 0 100% /var/lib/mysql`。根因：前两次废弃尝试与本次重复种子在 tmpfs 内累积 binlog/undo，3g 耗尽。
- **处置**：丢弃 attempt-3 全部轮次（n=200/n=1000 虽完成但不采用，避免「部分装置」混用——本轮所有被裁决算据必须来自同一完整装置）；挂起 JVM 终止（taskkill 32140）；MySQL 容器销毁重建，`--tmpfs /var/lib/mysql:rw,size=8g`；Redis 容器不动；重新 setup 后**全量重跑** rounds。
- **不改任何冻结判据**：S1–S4 算子、阈值、轮数、臂序、形状、长字段公式均与冻结件一致；attempt-3 的完成轮不参与裁决、不作为「重测取宠」的样本（裁决输入仅为 8g 装置上的完整一轮）。

## 3. 测量 IT 内存缺陷（attempts 4–6，同一根因；修正＝每臂执行后 clearInlineMocks）

- **attempt-4（12:50 起，tmpfs 8g 首跑）**：n=3000 progress oracle OLD 臂 `OutOfMemoryError: Java heap space`（forked JVM exit 99）。初始假设「9000 行单批 INSERT 的客户端巨型语句串」→ 修正为每 1000 行分批（`batched` helper，数据与冻结公式不变）并显式 `-DargLine=-Xmx6g`。
- **attempt-5（argLine 生效后）**：n=3000 同位置仍 OOM → 假设被否证。运行中 `jcmd GC.class_histogram` 实测：`org.mockito.internal.invocation.InterceptedInvocation` 559,303 个（伴随同量 `LocationImpl`/`StackFrameInfo`/`StackWalker` 元数据）——**inline mockmaker 为每条 spy 方法调用保留带栈快照的 invocation，且跨形状跨执行累积**；到 n=3000 时前两形状的数万 spy × 数十万调用记录已占满堆。该假设被直方图实锤。
- **修正**：每臂执行结束（RunState.end() 后、窗口外）调用 `Mockito.framework().clearInlineMocks()`（Mockito 5 inline mockmaker 的官方内存卫生 API；护栏的「读取即失败」语义、canary、非 null 计数全部不变——只释放拦截状态，不再跨执行累积）。
- attempt-5/6 的部分产物（n=200/n=1000 轮次、seed-counts、manifest、控制台日志 it-run-oom-*.log）归档于 `discarded-tmpfs3g/`，不参与裁决；attempt-7 起为全量完整重跑。
- **判据零改动**：以上全部为装置与夹具工程缺陷的修正；S1–S4 算子、阈值、轮数、臂序、形状、长字段公式、容器配方（除 tmpfs 3g→8g 披露项）均与冻结件一致。

## 4. 装置级调整 #2：测量容器加 `--skip-log-bin`（attempt-7 根因，实测证据）

- **attempt-7（8g tmpfs + 内存卫生修正后的首跑）**：n=200/n=1000 轮次完成（零 MEASURE PROBLEM），n=3000 种子第一批后再次挂死；`df` 显示 tmpfs 8G **又一次 100%**，`ERROR 1114 table '/tmp/#sql...' is full`。判明**不是**残余累积，而是本次干净运行自身灌满：`du` 实测 `binlog.000002..000008` 各 1.1G + `binlog.000009` 462M ≈ **8.2G 全部是 binlog**。
- **机理（实测证据）**：`binlog_format=ROW`（MySQL 8 默认）对每行 UPDATE 记录 before+after 全行镜像——本夹具每行含 `paper_json` 20480B + `answers` 2048B，单行镜像 ≈45.2KB（与 `SHOW BINLOG EVENTS` 中逐条 `Update_rows` 事件 45,244 字节精确吻合）；summarize 的**每臂复位 UPDATE**（PREREG §2.1 冻结算子）与 **casSummarize** 每轮各 n 行，n=1000/3000 两形状合计 ≈168,000 行 × 45KB ≈ 8G。performance_schema digest 佐证：top 写语句 `UPDATE exam_submissions SET status=?...`（复位）COUNT_STAR=79,800、casSummarize 同量级。
- **处置**：测量容器 `docker run` 加 `--skip-log-bin`（单机一次性容器、无任何复制消费者；binlog 属纯固定开销，去除后两臂同摊不变）。tmpfs 维持 8g（去 binlog 后全程占用预计 <2G）。attempt-7 全部产物（含已完成的 n=200/n=1000 轮次）归档 `discarded-tmpfs3g/`（it-run-attempt7.log），attempt-8 为全量完整重跑。
- **判据零改动**：S1–S4 算子、阈值、轮数、臂序、形状、长字段公式、复位算子文本均不变；tmpfs 大小与 binlog 开关均为 PREREG 冻结文本明示或框定的装置级参数，两处调整一并披露。
- **装置参数丢失与探针闭环（如实登记；attempt-8 发起前）**：§4「处置」首版把 `--skip-log-bin` 放在 `docker run` 的**选项位**（镜像名前），被 docker 当未知选项拒绝（`unknown flag: --skip-log-bin`，exit 125，`container-start.log` 14:15:12 条目），即参数**从未进入容器**、容器亦未创建——这正是「脚本写对常量、装置却可能没接上」的假绿风险。修正＝参数移到镜像名之后；该修正经**独立探针**实测闭环（§1 第 4 条：`@@log_bin=0`、`Config.Cmd=["--skip-log-bin"]`；选项位对照复现 exit 125）。同时 `--phase start` 由只写脚本常量（`port=`/`tmpfs=`）改为**落真实装置事实**：`docker inspect` 的 `PortBindings`/`Tmpfs`/`Config.Cmd` 与容器内 `SELECT @@log_bin, @@log_bin_basename` 原文写入 `container-start.log`，并对端口/挂载/log_bin 做硬校验（不符即 START 失败），消除「常量假绿」漏洞。判据仍零改动。

## 5. 其他如实登记

- 测试上下文存在 RabbitMQ 重连循环噪音（auto-startup=false 但有监听容器反复尝试 127.0.0.1:5672，无 broker）：它运行在独立线程，与既往归档卡测试档行为一致，不进入计时窗口、不计入语句账目（仅拦截器窗口内语句记账）。已存在的既有噪音，不属本卡改动。
- `SELECT VERSION()` 首探在容器初始化窗口内返回空串，随后直查复核 = 8.0.46（container-start.log 有追加登记）。
- redis:7-alpine 镜像本地缺失，按冻结配方 `docker pull`（digest sha256:858f009f…4999）；属环境准备，非判据改动。

## 6. 装置缺陷 #3：n=3000 形状 progress OLD 臂 OOM（attempt-8 实测，复现两次；根因＝业务嵌套换算 × spy 调用栈快照）

- **attempt-8（16:36–16:54；8g tmpfs + 去 binlog + §3 clearInlineMocks 后）**：n=200、n=1000 两形状各 15 计时轮全部完成（零 MEASURE PROBLEM；`rounds-n200.json`/`rounds-n1000.json` 落盘）；n=3000 种子完成（`seed-counts-n3000.json` ok=true）——**3g/8g 与 binlog 两类旧缺陷均未复现**（实测 `@@log_bin=0`、tmpfs 占用 <1G）。**但 n=3000 的 progress OLD 臂首次执行（oracle）即 OOM**：`MEASURE PROBLEM progress-n3000 arm OLD failed: java.lang.OutOfMemoryError: Java heap space`；mvnw `BUILD FAILURE`、`Tests run: 0`；`rounds-n3000.json` 与 `capture-manifest.json` **未产出**。
- **复现**：同命令重跑一次（17:12–17:30），**同形状/同端点/同臂/同执行**再次 OOM（原文 `D:\code\examOnline-verify\grading-score-projection\it-run-n3000-oom-repro.log`）。**须知悉**：该重跑覆盖了 measure 目录的 `it-run.log`/`rounds-n200.json`/`rounds-n1000.json`（首跑这三件未及另存，如实登记）。
- **实测（jcmd `GC.class_histogram` @ -Xmx6g 堆顶；原件 `diag-histogram-atcap.txt`，对照 `diag-histogram-t1.txt`）**：`org.mockito.internal.invocation.InterceptedInvocation` **18,335,139** 个（浅 880MB）、`GradingSubmission$MockitoMock$…$auxiliary$…` 与 `SubjectiveGrade$…$auxiliary$…` 各 **9,152,201** 个，`LocationImpl`/`StackFrameInfo`/`DelegatingMethod`/`WeakReference` 同量级。
- **根因（指导裁定；我当场回源码复核）**：**不是跨轮次泄漏**，是 progress 业务流自身的嵌套换算——`GradingQueryService`（`src/main/java/com/exam/grading/service/GradingQueryService.java`）L77–79：`submissions.stream().filter(s -> subjectiveRows.stream().anyMatch(row -> row.getSubmissionId().equals(s.getId()) && row.getScore() == null)).count()`。n=3000 时 `submissions(3000) × subjectiveRows(6000) = 1.8×10^7` 次嵌套取值；`Mockito.spy` 默认 mockmaker 对**每次**取值抓完整调用栈快照（`LocationImpl`），**单次 progress 调用内**即产出 ≈1.8×10^7 个 `InterceptedInvocation`，与直方图 18,335,139 精确吻合。**更正上一版结论**：先前「以跨轮次保留为主 + ~10² 放大」的表述不准确（依据＝该嵌套 `anyMatch` 回源码引文 + 18,335,139 与 1.8×10^7 量级吻合）；`clearInlineMocks()`（§3）针对跨执行累积，对**单次执行内**的 1.8×10^7 无效，故未能阻止本 OOM。
- **装置优化（预授权 + 披露；PREREG 文本零改动）**：本卡测量**不使用** `Mockito.verify()`（IT 全文零 `verify(`），只消费 `doThrow` 抛错护栏。`ReadFailGuard.wrap` 由 `Mockito.spy(x)` 改为 **stub-only mock**；**落地形态与指导给出的一行不同，已实测判定（客观登记，非擅自发挥）**：指导原式 `Mockito.mock(cls, withSettings().spiedInstance(x).stubOnly())` 经独立探针（`StubOnlyProbeTest`，原件 `D:\code\examOnline-verify\grading-score-projection\stubonly-delegation-probe.txt`）证明**并不委托**——`MockSettings.spiedInstance` 单独**不改变默认答案**，未 stub 的 getter 仍返回默认值（id=0 / gradingStatus=0 / objectiveScore=null）；据此重跑时 summarize 全臂全形状抛 `存在未完成判分的答卷，不能汇总成绩`（`MEASURE PROBLEM` 计 108 条）。补 `defaultAnswer(Mockito.CALLS_REAL_METHODS)` 后与 `spy()` 逐值相等（探针 form C ≡ form A）。故落地为 `Mockito.mock(cls, Mockito.withSettings().spiedInstance(x).defaultAnswer(Mockito.CALLS_REAL_METHODS).stubOnly())`（= `spy()` 语义 + stub-only 内存优化；语义不变：`getAnswers()`/`getStudentAnswer()`/`getComment()` 一经调用即抛 `AssertionError`，其余 getter 委托真实实体；canary、非 null 计数不变）。
- **结论**：装置在 `-Xmx6g` + stub-only 下重跑（attempt-9）；PREREG §2.2 冻结文本未动，仅装置实现按其语义更换。
