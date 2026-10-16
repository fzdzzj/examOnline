# 规范增量：grading

## MODIFIED Requirement: 判分进度部分批改计数（追加 Scenario）

> 合入注记：由 optimize-practice-pool-hot-path 追加（2026-10-16）。背景：进度总览两处统计查询曾无列投影——答卷载 answers TEXT 大列、主观批改行载全列，仅为内存计数；批改期间前端高频轮询该端点，读放大持续发生。修复：两处统计查询加消费列投影；统计语义与从库读（@DS("slave")）零改动。

### ADDED Scenario: 进度统计窄投影

- **WHEN**: 教师批改期间前端轮询判分进度总览
- **THEN**: 答卷统计查询 SHALL 仅选取计数所需列（grading_status 等消费列），SHALL NOT 载入 answers 等答案大列
- **AND**: 主观批改行统计查询 SHALL 仅选取统计与未批答卷收集所需列（submission_id、score），SHALL NOT 载入 comment 等无关重列
- **AND**: 进度计数口径（已判/失败/待判/部分批改）与读写分离语义 SHALL 零改动

## MODIFIED Requirement: 简答批改（追加 Scenario）

> 合入注记：由 optimize-practice-pool-hot-path 追加（2026-10-16）。背景：批改保存回读曾为「selectWorkbenchRows 全量学生行（含 student_answer TEXT）+ 内存 filter 单行」——每笔保存都白载全考场。修复：改单行回读查询。

### ADDED Scenario: 批改保存单行回读

- **WHEN**: 教师保存某道主观题的批改分数（成功路径）
- **THEN**: 批改结果回读 SHALL 以单行查询取得（含 submission_id 维度），SHALL NOT 载入该题全量学生工作台行后内存过滤
- **AND**: 回读结果字段与既有工作台行等价（响应形状不变），回读失败仍按既有错误语义处理（INTERNAL_ERROR「批改结果回读失败」）
- **AND**: 乐观锁 CAS、读己之写标记、批改防重与越权校验语义 SHALL 零改动
