# Data Access Specification Delta

> Capability: `data-access`  
> Change: `harden-data-retention-scope`  
> Status: PROPOSED  

---

## MODIFIED Scenarios

### Requirement: 数据保留策略

#### Scenario: 清理只触达辅助数据

- **GIVEN** 一场早已终结的考试或长期运行的辅助数据表
- **WHEN** 清理任务运行
- **THEN** 仅清理诊断性、幂等性辅助数据与已处理死信（实施范围：`exam_behavior_logs` / `exam_submit_dedups` / `score_audit_logs` 按 `exam_id` 清理；`audit_log` 按自身年龄清理；`exam_dlq_messages` 仅对已重放成功的 `status = 'REPLAYED'` 消息按年龄清理）
- **AND** 答卷、成绩、考试快照、班级归属、错题重练历史等业务事实一律不被清理
- **AND** `exam_dlq_messages` 中待人工排查的 `status = 'PARKED'` 消息绝对不被清理

---

## ADDED Scenarios

### Requirement: 数据保留策略

#### Scenario: 已重放死信消息按状态与年龄索引有界清理

- **GIVEN** `exam_dlq_messages` 表存在超期的死信记录，且建有复合索引 `idx_dlq_status_time (status, created_time)`
- **WHEN** 清理任务运行
- **THEN** 系统仅检索 `status = 'REPLAYED' AND created_time < cutoff` 的候选行，完全走复合索引，不发生全表扫描
- **AND** 按照独立配置的 `exam.retention.dlq.retention-days` 窗口分批执行短事务删除
- **AND** 产生指标 `exam.retention.rows`（tag: `table=exam_dlq_messages, action=candidate/deleted`）
- **AND** 在 `dry-run=true` 模式下仅统计候选数量，实际删除行数为 0
