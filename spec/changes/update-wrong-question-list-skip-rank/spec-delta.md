# update-wrong-question-list-skip-rank spec-delta

能力域：`spec/specs/performance/spec.md`（收口时合入该基线；如错题本行为 Requirement 在 score-management 域有更贴合的挂载位，以 score-management 基线实际结构为准，Scenario 文本逐字一致）

变更类型：ADDED Requirement

## ADDED Requirement：读侧装配不得携带消费路径未使用的重计算

### Scenario: 错题本列表不计算班级排名

- WHEN 学生查询错题本列表（每页至多 10 场已发布考试）
- THEN 服务端装配逐题数据时不得执行全场排名计算（`rankBySubmissionId` 的全班投影查询与 `RankCalculator` 调用），亦不得执行列表响应未使用的用户取数（`userMapper.selectById`）
- AND 响应体（total/page/size/groups 及错题条目各字段）与既有契约逐字一致，`openapi.yaml` 零变更

### Scenario: 判分口径同源保持

- WHEN 错题本列表或单场回顾装配逐题得分/答案
- THEN 判分唯一事实源仍为 ScoreExportService 现场重算（轻量入口与完整入口共用同一判分核心装配，绝不另造第二套判分口径）
- AND 单场回顾（含名次与用户字段）与个人成绩单 PDF、逐题明细两处导出路径零改动

### Scenario: 护栏常驻

- WHEN 任何后续改动使错题本列表路径重新引入全班排名计算，或使轻量入口判分口径偏离完整口径
- THEN 常驻纯 JUnit 护栏测试（轻量入口对 RankCalculator 零交互断言 + 判分三要素与完整口径一致断言）必须击红
