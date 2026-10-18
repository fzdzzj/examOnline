# exam-taking 规范差异（spec-delta）

## MODIFIED Requirements

### Requirement: 自动保存与断线恢复

> 修改说明：在既有自动保存与断线恢复基础上，增加草稿 answers 字节体积上限约束（≤ 64KB），防止超大恶意 payload 占用带宽与 Redis 内存。

#### Scenario: 草稿体积上限钳制
- **GIVEN**: 学生处于考试中并触发草稿保存
- **WHEN**: 提交的答案草稿（answers）序列化 UTF-8 字节体积超过 64KB（65,536 字节）
- **THEN**: 系统 SHALL 拒绝保存并返回 400 错误（BAD_REQUEST，草稿内容超过大小上限 (64KB)）
- **AND**: 系统 SHALL NOT 向 Redis 写入该超大草稿数据
