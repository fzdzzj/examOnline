# grading 规范差异（spec-delta）

## MODIFIED Requirements

### Requirement: 简答批改

> 修改说明：在既有教师人工批改与并发防覆盖基础上，增加批改评语入参长度约束（≤ 1024 字符）与初次批改并发唯一键冲突防御（DuplicateKeyException 转抛 409）。

#### Scenario: 批改评语长度限制
- **GIVEN**: 教师批改主观题
- **WHEN**: 提交的批改评语（comment）字符长度超过 1024
- **THEN**: 系统 SHALL 在参数校验阶段阻断请求并返回 400 校验错误
- **AND**: 系统 SHALL NOT 向数据库写入超长评语，防止产生 500 数据溢出异常

#### Scenario: 初次批改并发唯一键冲突防护
- **GIVEN**: 两名教师同时对同一答卷的同一道尚未批改的主观题提交初次批改打分（双方本地状态均无历史记录）
- **WHEN**: 双方同时发起新增插入操作导致其中一方触发数据库唯一键约束（uk_subjective_grades）
- **THEN**: 触发唯一键冲突的一方由系统捕获 `DuplicateKeyException` 并转抛 409 状态冲突异常（STATE_CONFLICT，"批改已被他人更新，请刷新后重试"）
- **AND**: 系统避免向客户端暴露 500 内部服务错误
