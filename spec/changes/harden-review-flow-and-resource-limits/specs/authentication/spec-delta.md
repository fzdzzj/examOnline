# spec-delta: authentication (harden-review-flow-and-resource-limits)

### Requirement: 用户批量导出

#### Scenario: 大批量用户导出分片分批查询资源保护 (ADDED)
- **WHEN**: 管理员导出包含大量用户（如超过 500 人）的学生、教师或全量用户数据
- **THEN**: 系统 SHALL 在按 ID 加载用户实体时执行有界分片批量查询（每批至多 500 个 ID），禁止单次发起包含数千参数的巨型 `WHERE id IN (...)` 查询
- **AND**: 分片查询导出的结果集顺序与字段完整性 SHALL 与既有口径完全一致，防止 MySQL 报文上限溢出与应用层内存激增
