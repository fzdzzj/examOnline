# 阶段 3 护栏测试红→绿转录（change reattribute-monitor-overview-projection）

## 护栏测试

`src/test/java/com/exam/monitoring/guard/MonitorOverviewProjectionGuardTest.java`（本任务新增，随
`mvnw clean test` 常驻；Executor 层拦截器 + `armed` 窗口，手法沿用
`src/test/java/com/exam/scalar/guard/ScalarProjectionGuardTest.java`）：

- 主语句 `ExamSubmissionMapper.selectList`：armed 窗口内执行条数 / 返回行数 / 实体长字段
  （answers、paper_json）非 null 计数（须 0）；seed 前置断言库内同谓词行确有长字段非 null
  的行（排除"库本来就空"的伪绿）；另断言主语句 SQL 文本不再含 paper_json/answers。
- 窄读 `ExamSubmissionMapper.selectFirstPaperJson`：执行次数（有行且有快照 ⇒ 恰 1；
  主语句 0 行 ⇒ 0；有行但无可用快照 ⇒ 1），SQL 文本只取 paper_json 且带 LIMIT 1、
  不捎带 answers，返回值即个人快照。
- 语义同批锁定：题数分母跨考试参数不串场（诱饵考场 7 题 vs 被查场 40 题）、
  在线/离线/已交卷计数、逐学生进度（含无快照/零题不除零）与排序。

## 红（实施前，冻结顺序要求"先红"）

- 命令（仓库根，2026-09-29）：
  `./mvnw test -Dtest=MonitorOverviewProjectionGuardTest -DfailIfNoTests=false`
- 结果：`exit=1`；`Tests run: 2, Failures: 2, Errors: 0, Skipped: 0`；`BUILD FAILURE`
  （Finished at 2026-09-29T21:12:37+08:00）。
- 关键失败原文：

```
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 17.32 s <<< FAILURE! -- in com.exam.monitoring.guard.MonitorOverviewProjectionGuardTest
org.opentest4j.AssertionFailedError: 有行但无可用快照：窄读仍按实记账恰一次（返回 0 行） ==> expected: <1> but was: <0>
org.opentest4j.AssertionFailedError: 列投影后主语句返回实体的 answers/paper_json 必须为 null ==> expected: <0> but was: <3>
[ERROR]   MonitorOverviewProjectionGuardTest.degenerateShapesKeepNarrowReadAccounting:228 有行但无可用快照：窄读仍按实记账恰一次（返回 0 行） ==> expected: <1> but was: <0>
[ERROR]   MonitorOverviewProjectionGuardTest.overviewProjectsScalarColumnsAndCountsQuestionsViaSingleNarrowRead:146 列投影后主语句返回实体的 answers/paper_json 必须为 null ==> expected: <0> but was: <3>
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

红的两条失败正是"未实施"的签名：实体长字段非 null 计数 3（投影缺失）、
窄读执行次数 0（窄读方法尚不存在）；语义断言（题数 40、各计数、进度、排序）在红轮即已通过，
说明失败只来自取数形态哨兵，不来自语义口径。

## 绿（实施后）

- 实施范围（仅读形态一类）：`MonitorService.overview` 主语句加列投影
  （`student_id,status`）；题数分母改由新增 `ExamSubmissionMapper.selectFirstPaperJson`
  一次窄读（仅 paper_json、LIMIT 1，主语句 0 行时不发起）。
- 命令同上。
- 结果：`exit=0`；`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`；
  `BUILD SUCCESS`（Finished at 2026-09-29T21:13:41+08:00）。

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 21.67 s -- in com.exam.monitoring.guard.MonitorOverviewProjectionGuardTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- 既有断言未放宽、无 `@Disabled`、无新增睡眠/重试；红轮与绿轮之间只改了上述两个主代码文件
  （测试文件在两轮间未变），因此"红转绿"由实施本身造成。

## 原始日志与 sha256

入库副本（与库外原件逐字节相同，`cp` 复制后 `sha256sum` 复核一致）：

- `spec/changes/reattribute-monitor-overview-projection/evidence/guard-red.log`
  sha256 `f4b4befcc248ec85b769a714d420b5e8f80f45047c0fc6d1203e4e167aa0d727`
- `spec/changes/reattribute-monitor-overview-projection/evidence/guard-green.log`
  sha256 `741d1cc60493c0d6257dbc75e5f15b8a164b4d3519bb0946dc0273a9dc7cc918`

库外原件（不在入库清单内，仅登记位置与 sha256）：
`D:\code\examOnline-measure\monitor-projection\raw\red-green\guard-red.log`、
`D:\code\examOnline-measure\monitor-projection\raw\red-green\guard-green.log`（sha256 同上）。

## 后续夹具修正（全文披露，不覆盖上文红绿结论）

上文红绿两轮之后，**全量门禁**暴露本护栏夹具与共享 H2 的交互问题：夹具 `seedExam` 原先插入
`published=1` 的考试，而 `jdbc:h2:mem:exam;DB_CLOSE_DELAY=-1` 跨测试类累积，
`ExamTakingIntegrationTest.examListGroups` 依赖 `ExamTakingService.myExams()` 的
「已发布考试按 `start_time` 倒序第 1 页 50 条」窗口——夹具 4 条把该窗口挤满，使目标考试落
第 2 页而红（两次原样重跑同点同文案＝确定性红）。处置：`seedExam` 的 `published` 改为 `0`
（`MonitorService.overview` 与 `requireOwnedExam` 不读发布态，断言对象不变），随后全量门禁
327/0/0/1 绿（run3/run4）。

- **对本转录的影响**：`guard-red.log` / `guard-green.log` 产生于修正**前**的夹具版本
  （夹具 sha256 编辑前 `7662321388a731ee5cb6a1f3f65aa6e60a84d1595b5576c775cde1de6781024d`）；
  修正后夹具 sha256 `6dc13e88fcd953b217900b61ecd6c517155670167ad0b5b15712cc1f70f4022d`，
  其单类红绿未再重跑（修正只改 `published` 位，不影响本护栏的任何断言对象；其正确性由
  run3/run4 全量门禁覆盖）。
- 两次红的完整记录、归因计数与最小修正依据见 `evidence/gate-runs.md`；机制登记为 spec/README 遗留 #20。
