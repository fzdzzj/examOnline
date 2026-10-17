# harden-slave-read-failover

## 一、背景与问题（B.2 横切首扫中危 M3 卡，2026-10-17 立案）

三个 `@DS("slave")` 非强一致读点从库故障时直接 500（从库连接失败/超时异常裸穿）：

- `ExamSnapshotService.getCurrent`——**拉卷主链路**（开考 5000 人拉卷场景）；
- `PaperSnapshotService.getCurrent`——试卷快照读取（教师/判分回看）；
- `GradingQueryService.progress`——判分进度总览（批改期间前端高频轮询）。

从库故障 + 缓存冷时，拉卷请求在从库读处失败 500——读可用性完全没有主库兜底。既有读写分离语义（add-performance-deepening task3）：master/slave 双池、primary=master、非强一致读 @DS("slave")，写后窗口经 ReadYourWriteRouter 临时转主库（push("master") 覆盖栈顶路由）。

## 二、方案决策（内建能力探索 → 读点降级回主库）

**方案决策框架（派工卡要求先探索内建）**：dynamic-datasource 4.3.1 的 strategy 包只有
`DynamicDataSourceStrategy`（接口）/`LoadBalanceDynamicDataSourceStrategy`/`RandomDynamicDataSourceStrategy`
三类（`.m2-repo` jar 清单机械核验）——均为**选库期**策略（获取连接前决定命中哪个池），无法感知
**执行期**连接故障并自动重试；starter 亦无 failover/retry 组件。**结论：内建切换能力不存在，
采用读点 try-catch 降级回主库方案。**

- **新组件 `SlaveReadFallbackExecutor`**（com.exam.config，与 ReadYourWriteRouter 同包同机制）：
  读逻辑包为 Supplier，`DataAccessException`（从库连接失败/超时等基础设施异常）→ ERROR 日志
  （含 requestId）+ 降级计数 + `DynamicDataSourceContextHolder.push("master")` 重试一次
  （与 ReadYourWriteRouter 同一 push/poll 覆盖栈顶机制），主库成功即恢复可用；主库也失败原样上抛
  （诚实失败）。从库恢复后自然回到原路由，零人工干预；
- **降级口只认不可用**：仅捕 DataAccessException；业务异常（NOT_FOUND/越权 DENIED 等
  BusinessException）原样传播，不重试不计数；
- **新降级计数指标** `exam.datasource.degraded`（BusinessMetrics.countDatasourceDegraded，
  tag `endpoint` ∈ exam_snapshot/paper_snapshot/grading_progress 代码常量有界）+ 告警规则
  **DatasourceDegraded**（照抄 AuthDegraded 形态）+ AlertAssetsTest 同笔守护；
- **主链路零回归**：正常路径（从库健康）不压栈不计数零额外开销（try 直进直出）；三读点的
  既有窄投影/缓存互斥/归属校验语义逐字不动；本执行器禁用于事务内写路径（push/poll 只影响
  线程路由栈顶，事务内切库会破坏连接绑定——三读点均无事务，javadoc 明示约束）；
- **幂等性前提**：重试整体重跑 Supplier（纯读，含 getOwnedPaper/requireOwnedExam 校验读），无副作用。

## 三、白名单

- `src/main/java/com/exam/config/SlaveReadFallbackExecutor.java`（新文件）
- `src/main/java/com/exam/monitoring/metrics/BusinessMetrics.java`（exam.datasource.degraded 新指标）
- `src/main/java/com/exam/exam/service/ExamSnapshotService.java`（getCurrent 读逻辑包入执行器 + 构造 +1）
- `src/main/java/com/exam/paper/service/PaperSnapshotService.java`（getCurrent 同上）
- `src/main/java/com/exam/grading/service/GradingQueryService.java`（progress 整体读逻辑包入 + 构造 +1）
- `docker/observability/prometheus/rules/exam-online-alerts.yml`（DatasourceDegraded 规则）
- `src/test/java/com/exam/observability/AlertAssetsTest.java`（EXPECTED_ALERTS + known 集合）
- `src/test/java/com/exam/config/SlaveReadFailoverTest.java`（新文件：执行器机制 + 三读点接线）
- `src/test/java/com/exam/grading/service/GradingQueryServiceProjectionTest.java`、
  `GradingProgressAlgorithmTest.java`、`SubjectiveGradingServiceSaveScoreReadBackTest.java`
  （构造适配 +1 mock 透传桩，行为零改动）
- `.github/ci/gate-baseline.json`、`spec/changes/harden-slave-read-failover/**`、`spec/README.md`、`docs/功能待办清单.md`
- **禁触**：ReadYourWriteRouter/ReadYourWriteMark（读写分离既有设施）、DynamicDataSourceConfig、
  ExamSubmitService/ExamSubmissionService/ScoreQueryService（无 @DS 读点）、写路径服务、
  schema.sql、docker/mysql、openapi.yaml、pom.xml、frontend/

## 四、验收要点

- 执行器单测五断言：DataAccessException → 压栈 master 重试一次（重试期栈顶=master 轨迹断言）+ 计数；
  主库也失败原样上抛；业务异常不重试不计数；正常路径零计数零压栈；结束后路由栈平衡
- 三读点接线测试：ExamSnapshotService（拉卷主链路，从库失败 → 主库重试 → 业务异常传播链）/
  PaperSnapshotService（归属校验读失败同链）/ GradingQueryService（主库兜底成功路径返回真实进度）
- 既有 grading 三测试类零回归（构造适配透传桩）
- 变异击红：执行器摘除压栈重试（catch 内直接上抛）→ 降级用例确定性失败 → 复原回绿留证；等价变异如实登记
- 范围外零改动核对（ReadYourWriteRouter/写路径 + schema.sql + docker/mysql + openapi.yaml + pom + frontend）
- 门禁 `mvnw.cmd clean test` 全绿（仓库根、带 clean、禁 -o）；backendTests 基线在 667 上只增不减，收口笔同笔上调
- M 级理由：方案决策（内建探索证伪 → 降级方案）需落档；拉卷主链路行为面变更需三读点接线测试钉住；
  新指标 + 告警守护登记 + 既有测试构造适配三处
