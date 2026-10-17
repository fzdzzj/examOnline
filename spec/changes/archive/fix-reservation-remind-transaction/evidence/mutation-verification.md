# 变异验证：fix-reservation-remind-transaction

判据：变异先红后绿——注入变异 → 用例确定性变红（击杀）→ 复原 → 用例回绿。全程留证本目录。

## m1：还原死注解形态（击杀）

- **变异内容**：`ReservationRemindService.java` 整体还原到修复前形态（deliverIfAbsent 恢复 `@Transactional(rollbackFor = Exception.class)` 注解 + 方法体裸奔、构造函数退回 3 参）——即"自调用死注解"原缺陷形态。diff 见 `m1.diff`（92 行，与实施笔 c420319 逐字互逆）。
- **预期**：核心用例 `insertFailureRollsBackClaimThenRetryHeals` 变红，失败形态与修复前先红运行逐字同形（变异复现缺陷，先例 fix-exam-state-machine-transaction m1 同款口径）。
- **实测**（`m1-run.log`）：`Tests run: 2, Failures: 1, Errors: 0, Skipped: 0` + BUILD FAILURE；失败行 `expected: <null> but was: <2026-10-17T09:36:59.252741>`——认领回填残留而消息未写出，与先红（`01-red-run.log`，`expected: <null> but was: <2026-10-17T09:28:57.251172>`）逐字同形仅时间戳不同。回归护栏用例 `successfulRoundDeliversExactlyOnce` 保持绿（按设计：正常路径在两种形态下都成立，不作击杀判据）。
- **复原**：`git checkout HEAD --`（实施笔 c420319 版本）→ 复跑 `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS（`D:\code\examOnline-measure\fix-reservation-remind-transaction\m1-restore-run.log`，判定行经 mutation-verification 本条登记）。

## 结论

- m1 确定性击杀；无等价变异体（本卡变异面仅"事务边界形态"一项，测试断言对象是真实数据库行，不存在"改打桩即改结论"的空间）。
- 红绿链完整：先红（缺陷存在性）→ 修复转绿 → m1 还原击红 → 复原回绿 → 全量门禁 638/0/0/1（`full-clean-test-summary.txt`）。
- 原始全量日志留存于仓库外 `D:\code\examOnline-measure\fix-reservation-remind-transaction\`（red-run.log / green-run.log / m1-run.log / m1-restore-run.log / full-clean-test.log），本目录为判定行裁剪版。
