# spec-delta：score-management（错题派生链装配成本收敛）

## 变更类型

MODIFIED（行为契约不变——错题判定口径、入池/排除规则逐字不动；新增性能与缓存语义 Scenario）

## MODIFIED Requirement：错题重练池（现基线「错题本与重练池」相关 Requirement 追加 Scenario）

### 新增 Scenario: 错题判定结果的缓存派生（判分同源不变量保持）

- **WHEN**: 学生请求重练池列表或错题本列表（错题派生链读路径）
- **THEN**: 系统 SHOULD 优先读取 Redis 缓存的逐题轻量判定结果（`{examId}:{studentId}` →
  逐题 score/max/type），缓存键 SHOULD 携带判分事实源版本信号；未命中的场 SHALL 按现行
  `QuestionScoreResolver.resolve` 现算口径回填缓存并派生错题（首次访问不劣化）
- **AND**: 缓存值 SHALL 由现算口径产出（同引擎同快照同答案），SHALL NOT 引入第二套判分
  逻辑；判分事实源变更（批改、复核改分）后，后续读取 SHALL NOT 采信变更前的缓存值
- **AND**: 缓存 SHALL NOT 存储答案文本、评语、判分依据明细（仅 score/max/type 轻量元组），
  SHALL NOT 替代成绩单导出的完整装配（导出路径仍走完整口径）
- **AND**: Redis 不可用时读路径 SHALL 退化为现行逐场现算（可用性优先，SHALL NOT 因缓存
  故障阻断重练池访问）

### 新增 Scenario: 判错装配成本收敛

- **WHEN**: 学生打开重练池列表（已发布 N 场）
- **THEN**: 判分核心装配（`assembleGradingCore`）调用次数在缓存命中后 SHOULD ≤ 未命中
  场数（而非 N）；同一请求内同一答卷的答案 JSON 解析 SHALL 至多一次（消除双重 parse）
