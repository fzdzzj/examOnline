# add-exam-reservation spec-delta（exam-management）

能力域：`spec/specs/exam-management/spec.md`（收口时将下述 Scenario 追加合入既有 Requirement「考试创建」末尾，Scenario 文本逐字一致）

变更类型：MODIFIED Requirement（追加 Scenario）

## MODIFIED Requirement: 考试创建（追加 Scenario）

#### Scenario: 预约开关仅在主考创建链暴露

GIVEN 教师创建或编辑普通考试

WHEN 设定「需预约」开关（`reservationRequired`）

THEN 开关随建考/编辑请求持久化，默认关闭；默认关闭下既有考试与既有行为零变化

AND 补考创建链（`MakeupCreateRequest`）不暴露该开关——补考白名单制与预约制正交，补考不适用预约

#### Scenario: 预约开关修改窗口与考试状态绑定

GIVEN 「需预约」考试已推进为进行中或更晚状态

WHEN 教师请求修改该考试的 `reservationRequired`

THEN 服务端拒绝修改（开关仅未开始状态可改），开考后预约名单与开关一并锁定

AND 考试其余字段的编辑语义维持既有约束零变化
