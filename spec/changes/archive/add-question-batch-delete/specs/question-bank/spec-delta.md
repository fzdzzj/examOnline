# question-bank spec-delta：批量删除题目（add-question-batch-delete）

## ADDED Requirements

### Requirement: 批量删除题目

系统 SHALL 提供批量删除题目端点（`POST /api/questions/batch-delete`），接受题目 ID 列表，SHALL 以逐题结果信封语义执行：逐题校验（存在性、归属、考试引用锁定）收集失败项及原因，合规子集单事务软删除；任一题校验失败 SHALL NOT 阻止其余合规题目的删除（部分成功），且结果 SHALL 逐题如实区分成功与失败，不得将部分成功伪装成全部成功。

#### Scenario: 部分成功

GIVEN 批量列表混有合规题目、不存在或已软删题目、非归属教师题目、被进行中考试引用题目
WHEN 教师调用批量删除
THEN 合规子集被软删除（is_deleted 标记，历史试卷/快照引用不受影响），其余逐题返回失败原因（文案与单题删除同类语义），响应含 succeeded 与 failed 列表

#### Scenario: 全部成功

GIVEN 教师以本人全部合规题目调用批量删除
WHEN 请求执行
THEN 全部题目软删除，failed 为空列表，题库列表刷新后不再展示

#### Scenario: 全部失败零删除

GIVEN 批量列表全部题目均校验失败
WHEN 教师调用批量删除
THEN 零题目被删除，succeeded 为空数组，逐题失败原因返回（不报整体错误）

#### Scenario: 请求校验拒绝

GIVEN 列表为空、超过单次上限（100）或含重复题目 ID
WHEN 请求到达服务端
THEN 返回 400（空/超限/「请求内存在重复题目」），零删除

#### Scenario: 单题删除端点语义不变

GIVEN 既有单题删除端点 DELETE /api/questions/{id}
WHEN 批量端点上线后
THEN 单题端点校验链（owner + 考试锁定 + 逻辑删除）与失败文案原样保留，行为零改动
