# spec-delta：performance（错题派生缓存失效正确性）

## 变更类型

MODIFIED（新增缓存失效性能语义 Scenario；现有预算条目不动）

### 新增 Scenario: 错题判定缓存的版本戳自失效 (optimize-wrong-question-derivation-cost)

- **WHEN**: 教师批改主观题、复核改分或其他任何判分事实源写入之后，学生随后读取重练池
- **THEN**: 缓存读取 SHALL 先校验判分事实源版本信号；信号与缓存键不一致时 SHALL 视为
  未命中并按现行口径重算回填，SHALL NOT 采信脏缓存导致错题集合错判（漏题/多题）
- **AND**: 版本信号 SHALL 取自已存在的列（零 DDL 约束下不得新增列）；若侦察确认无可用
  信号列，实施 SHALL 退化为显式失效钩子并覆盖批改与复核全部写点——退化依据 SHALL 留
  痕于 proposal.md 并经复核确认
- **AND**: 失效正确性验证 SHALL 以单测覆盖：改判分事实源 → 缓存失效 → 新值派生错题
  （变异验证注入「去校验采信脏值」须击红）
