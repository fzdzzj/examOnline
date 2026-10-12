# add-exam-reservation spec-delta（absence-makeup）

能力域：`spec/specs/absence-makeup/spec.md`（收口时将下述 Scenario 追加合入既有 Requirement「缺考标记」末尾，Scenario 文本逐字一致）

变更类型：MODIFIED Requirement（追加 Scenario）

## MODIFIED Requirement: 缺考标记（追加 Scenario）

#### Scenario: 预约制考试的应考名单构成

GIVEN 考试开启「需预约」且考试结束（自然到点或教师提前结束，锚定「进行中 → 已结束」迁移不变）

WHEN 系统标记缺考

THEN 应考名单取该考试开考时锁定的有效预约名单（考试结束时刻读到的预约行集），缺考 = 应考名单中有答卷者之外的有效预约学生，`INSERT IGNORE` 幂等语义不变

AND 普通考试（未开启预约）的应考名单仍为班级名单（`ClassService.listStudentIds`），既有缺考行为零回归
