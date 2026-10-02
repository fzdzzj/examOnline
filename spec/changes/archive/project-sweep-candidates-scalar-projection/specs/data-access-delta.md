# delta：spec/specs/data-access/spec.md ——「标量读取的列投影」Requirement 追加

> 候选 delta：仅当护栏先红后绿且实施验收成立时合入。本卡为机械归因驱动的单站点投影（无三臂测量），验收边界见 proposal.md。

## MODIFIED Requirement: 标量读取的列投影

（在既有 Requirement 的 Scenario 列表后追加以下 Scenario，不改动既有 SHALL 与场景）

#### Scenario: 兜底扫描候选行只取冻结标量列（project-sweep-candidates-scalar-projection）

GIVEN 交卷链路定时兜底扫描（`ExamSweepService.forceSubmitOverdue`，默认每 10 秒一轮、分批多轮）扫描「进行中且（个人已超时 或 所属考试已结束/已批改）」的候选答卷，下游对候选行仅消费 `examId`/`studentId`（经 `forceSubmitByBackend` 按 `(examId, studentId)` 重新定位答卷）

WHEN `ExamSubmissionMapper.selectForceSubmitCandidates` 执行取数

THEN SELECT 列表限定为 `s.exam_id, s.student_id`（冻结列集，机械归因产物），不得载入 `paper_json` / `answers` 等长字段

AND 谓词（`s.status = 1 AND (s.deadline_time < ? OR e.status IN (2, 3))`）、`JOIN_INDEX(s idx_submissions_sweep)` 提示、JOIN、LIMIT 与返回行数不变

AND 兜底强制交卷行为与三路竞态幂等语义不变（既有回归 `MultiInstanceSweepSafetyTest` 全绿）

AND 未实施投影的站点（`selectSubmittedWithoutAnswers` 对账补发——命中量为个位数行、价值低，仅登记不实施；其余 NO-GO/排除站点）不据此判为违规、取数语句不变

> 合入注记（收口时补全）：护栏先红后绿证据（guard-red.log / guard-green.log）、门禁四计数与短 revision、验收边界（本地 H2 测试上下文 + 仓库门禁——不外推生产 MySQL/Tomcat，不构成交卷链路 P99 结论）。
