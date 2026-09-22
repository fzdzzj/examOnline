# 学生端考试四条可复现演示脚本（阶段 22 第 3 片）

> 每条脚本证明一条后端深水区能力在前端真实发生。环境：dev profile 后端
> （端口与环境事实见 `docs/指导Agent交接文档.md` §6.2，真源是 `docker-compose.yml`）
> + 前端 `pnpm dev`。前置数据准备共用第 0 步。
> 实跑结果见文末「实跑记录」。

## 0. 前置数据（四条脚本共用）

1. 共享栈起好：`docker start exam-mysql-master exam-mysql-slave exam-rabbitmq`（Redis 用宿主已有实例，勿起 compose 里的 Redis 容器）。
2. 后端 dev profile 启动，`GET /actuator/health` 返回 UP（启动日志里 `ExamSubmitSender` 的 `IllegalStateException` 是遗留 #10，非致命，忽略）。
3. 教师账号登录 → 题库建题（单选/判断若干）→ 组卷 → **创建考试并发布**（阶段 21 界面）；
   考试时长设长一些（如 60 分钟），班级里加入学生账号 S1。
4. 学生 S1 登录 →「我的考试」应看到该考试在「进行中（可续答）」分组。

---

## 脚本一：双击交卷 —— 三重幂等 + 前端 pending 配合

- **证明的后端能力**：交卷三重幂等（SETNX 锁 + 防重表 `uk_submit_dedup` + 状态机 CAS）——重复请求只产生一次交卷，重复调用返回首次结果（`ExamSubmitService.doSubmit` 幂等快速路径）。
- **前置**：学生 S1 已进入考试，已作答若干题。
- **步骤**：
  1. 在答题页点「交卷」→ 弹二次确认（显示未答题数）→ 点「确认交卷」；
  2. 按钮进入「正在交卷…」loading 禁用态的瞬间（或借 DevTools Network 面板把请求限速），快速再点两次交卷按钮；
  3. 观察结果页与后端：`GET /api/exam-taking/exams/{examId}/paper` 的 `status`、以及教师端该答卷的提交记录。
- **期望现象**：
  - 网络面板里 `POST /api/exam-taking/exams/{examId}/submit` **只出现一次**（在途期间前端不发第二次）；
  - 结果卡展示唯一答卷编号与交卷时间；刷新重进后 `status=2`，无第二份答卷；
  - 数据库 `exam_submit_dedup` 该学生仅 1 行。
- **对应硬约定**：3（结果以后端为权威）、4（防重是配合非替代）。

## 脚本二：断网续答 —— IndexedDB 不丢答案 + 保守合并

- **证明的后端能力**：30s Redis 草稿 + 草稿版本协议（`ExamDraftService`）+ 断线恢复入口 `GET /paper`；前端 IndexedDB 只做断线留底（**不是离线考试模式**）。
- **前置**：学生 S1 已进入考试并作答，至少完成一次自动保存（DraftSyncBadge 显示「已同步」）。
- **步骤**：
  1. DevTools → Network → Offline（断网）；继续作答若干题，观察 DraftSyncBadge 变为「未同步」；
  2. 刷新页面（仍在断网）：进入请求失败，界面报加载错误——**答案不丢**：IndexedDB 留底仍在；
  3. 恢复网络，重进考试：页面拉取后端草稿，与本地缓存走 `mergeDrafts` 保守合并；
  4. （分支演示）用另一浏览器以同一学生账号进入同一考试作答不同答案 → 原浏览器刷新：出现「本机有一份未同步的答案」冲突提示，两份都保留，学生显式选择。
- **期望现象**：
  - 断网期间作答不丢、恢复后自动同步成功（badge 回「已同步」）；
  - 无任何「离线考试」文案；冲突分支两份答案都在，默认用服务器那份，绝不静默覆盖。
- **对应硬约定**：7（合并保守）、11（不声称离线模式）。

## 脚本三：归零锁定 —— 前端锁定 + 后端兜底判定

- **证明的后端能力**：服务端时间权威（`EnterExamResponse.remainingSeconds/serverTime/deadlineTime`）+ 超时定时兜底扫描（`ExamSweepService` 从 Redis 草稿取答案收卷，status=2 谓词 + `answers_missing` 对账补发）。
- **前置**：为缩短等待，教师创建一场 **1 分钟时长** 的考试并发布；学生进入。
- **步骤**：
  1. 作答一题，等倒计时进入最后 5 分钟（本脚本 1 分钟场直接进入警告态）观察警告标志；
  2. 倒计时归零：界面立即锁定作答控件（输入无效、导航仍可回看），出现「待同步」标志；
  3. 不做任何操作，等待 ≤10s（扫描周期），刷新页面；
  4. （对照）学生把**本机时间**往前改 2 小时再刷新——判定依旧：快照剩余时间以后端为准，改表不能提前解锁或提前交卷。
- **期望现象**：
  - 归零瞬间前端发出 `POST .../submit`（submitType=COUNTDOWN_ZERO）或后端扫描先行收卷——两者以先到者为准，最终 `status=2` 只有一次；
  - 改本机时间不影响任何判定（本地只有单调秒表）；
  - 教师端看到答卷为超时/正常交卷状态之一，答案完整（含草稿同步的最后状态）。
- **对应硬约定**：2（服务端时间为权威，最终判定在后端）。

## 脚本四：切屏记录 —— 只警告不交卷 + 事件落库

- **证明的后端能力**：行为采集核心（策略模式判 severity）+ 行为日志落库 + 教师端行为日志时间线（阶段 21）交叉验证。
- **前置**：学生 S1 进入一场进行中考试；教师账号另开一个浏览器/窗口，进入该考试的行为日志页面（`GET /api/exams/{examId}/behavior-logs`）。
- **步骤**：
  1. 学生切到别的标签页（触发 blur + visibilitychange）再切回；
  2. 观察答题页警告 Alert（文案与严重度来自后端响应，前端不加工）——注意警告出现在**回归之后**；
  3. 重复切出/切回 2 次；
  4. 教师端刷新行为日志时间线，按学生 S1 过滤，点开事件看 `eventData`。
- **期望现象**：
  - **离开瞬间不发请求**；回归瞬间才上报，每次离开**恰好一条**事件
    （SWITCH_SCREEN 或 WINDOW_BLUR，不拆分、不重复）；
  - `eventData` 携带 `{durationMs}`——离开时长是**单调时钟差**（相对量），
    不是本机时刻（绝对量），学生改表伪造不了；
  - 异常终结的离开（交卷前还没回来 / 关页面）由兜底 flush 上报，
    `eventData = {incomplete: true}` 标注时长未知，不谎报；
  - 考试**未**被交卷——切屏再多次也不会触发提交（界面无此逻辑）；
  - 教师端能看到对应条数的事件（severity 由后端策略判定），学生侧无权访问该接口（403）。
- **对应硬约定**：8（只警告）、9（只报确证事件、不抬严重度）。

---

## 实跑记录（第 3 片，2026-09-22 真机联调）

环境：dev 后端（`--server.port=8080`，`/actuator/health` UP，db/rabbit/redis 全 UP）
+ 共享容器 `exam-mysql-master/slave`、`exam-rabbitmq` + 宿主 Redis。
本轮造的数据：考试 #9（`s3-take-e2e`，试卷 #4、班级 #1、`pe_t160824` / `pe_s1_160824`）。
驱动方式：真实 HTTP 接口（与前端页面发出的请求同端点同载荷），未改库、未 mock。

| 脚本 | 实跑 | 真实观察 |
| ---- | ---- | -------- |
| 双击交卷 | ✅ 实跑 | 开考前后端先拒（`HTTP 400 考试尚未开始`，canEnter=false 可用性由后端判定）；进入 `HTTP 200`（status=1、remainingSeconds=3599、serverTime/deadlineTime 齐备）；交卷两次均 `HTTP 200` 且**同一 `submissionId=10005`**（幂等快速路径返回首次结果，`exam_submit_dedups` 仅 1 行）；重进返回 status=2、questions 置空、remainingSeconds=0 |
| 断网续答 | ⚠️ 部分实跑（浏览器断网环境未逐项演练，IndexedDB 合并分支由 vitest 单测覆盖：35 文件 315 例含合并三分支/断线写入/恢复同步） | 草稿接口真跑：v1 `accepted=true`、31s 后 v2 `accepted=true`（≥30s 周期纪律保持）；重进返回同一快照 |
| 归零锁定 | ⚠️ 未实跑完整等待（1 分钟场未演练到归零时刻）；判定链路已实证：服务端时间字段齐备、`canEnter`/开考判定均由后端返回控制；单测覆盖归零锁定与「待同步」标志 | — |
| 切屏记录 | ✅ 实跑（返修前口径：离开瞬间即报） | `WINDOW_BLUR`→`HTTP 200 {warned:false,severity:1,severityName:"低"}`；`SWITCH_SCREEN`→`HTTP 200 {warned:true,count:1,message:"…切屏不会强制交卷…"}`（警告文案由后端给）；教师端 `GET /api/exams/9/behavior-logs` 返回 **total=2，恰好两条**（WINDOW_BLUR、SWITCH_SCREEN 各一，无拆分虚报）；学生访问该端点 `HTTP 403`。**返修后（离开时长）改为回归瞬间上报、eventData 带 `durationMs`**，端点与条数语义不变，时长路径由单测覆盖（23 例） |

交卷落库验证：`exam_submissions` #10005 status=2、submit_type=1(MANUAL)、`answers_missing=0`（MQ 消费落库成功，答案内容为草稿 v2 的最终状态）。

