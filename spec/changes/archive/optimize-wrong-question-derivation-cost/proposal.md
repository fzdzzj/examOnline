# optimize-wrong-question-derivation-cost

## 一、背景与问题（第四轮巡查发现，2026-10-16 立案）

错题派生链（重练池列表 / 错题本列表 / 提交门控 / 重练提交）逐场全量装配个人成绩单：
`StudentPracticeService` L91/L264/L322/L354 + `StudentWrongQuestionService` L140 共 5 处调用
`ScoreExportService.getPersonalReportWithoutRank` → `assembleGradingCore`（全卷载入 TEXT +
`QuestionScoreResolver.resolve` 全卷判分重算 + `parseAnswers`），其中 `assembleGradingCore`
L573 与 `resolve` L62 **对同一答案 JSON 双重 parse**。判错消费段只用 `score < max` 布尔
（getPracticePool L100-112）——学生打开重练池 = O(已发布场数) × (全卷载入 + 全卷判分重算)，
20 场×50 题典型学生为秒级可感延迟。

历史脉络：optimize-practice-pool-hot-path（已归档）只短路了提交门控扫描，列表页逐场装配
因「判分同源」决策被保留——本案是该保留半区的延伸收口，非返工。

## 二、方案方向（判分同源不变量必须保持）

**不变量**：错题判定口径 ≡ `QuestionScoreResolver.resolve` 现算口径（注释明载的设计决策：
同引擎同快照同答案免建逐题分数表——不推翻、不另建判分逻辑）。

**主方向：Redis 缓存错题判定轻量结果（版本戳自失效，零写路径侵入）**
- 缓存内容：`{examId}:{studentId}` → `{questionId → (score, max, type)}` 轻量 map（非
  PersonalReport 全对象、不含 detail/comment/答案文本）
- 失效：**优先版本戳方案**——缓存 key 拼接判分事实源的版本信号（submission 更新时间与
  SubjectiveGrade 批改行的最新变更信号，任务 2 侦察确认可用列），读路径内自校验、天然失效，
  不触碰批改/复核写路径（白名单得以收窄在 score 包）。**约束：零 DDL，不得为版本戳加列**；
  若无可用版本信号列，退化方案为显式失效钩子挂批改/复核写点（白名单扩 grading 包，
  需在任务 2 侦察后由复核确认范围——写入退化路径的依据）。
- 缓存未命中的场按现行装配回填（首次访问不劣化）。

**顺带修**：`assembleGradingCore` 双重 parseAnswers 冗余（resolve 内已 parse，外层再 parse
一次）——错题轻量装配通道复用 resolve 内已 parse 结果或收敛为一次。

**不动**：`QuestionScoreResolver` 判分逻辑本身、`isWrongQuestionOf` 短路结构（上次修复成果）、
错题判定业务口径（score<max、排除简答题、连对消灭过滤）。

## 三、白名单

- `src/main/java/com/exam/score/**` 与 `src/test/java/com/exam/score/**`
- `src/main/java/com/exam/config/CacheProperties.java` 单条豁免（wrongQuestionJudgementTtl
  缓存 TTL 配置默认值，行为中立；复核确认白名单外仅此一处，2026-10-16 勘正补录）
- 仅当退化方案成立时：`src/main/java/com/exam/grading/service/**` 的失效钩子单点（批改
  提交写路径）+ 对应测试——**实施已走版本戳方案，本退化路径未启用**
- 禁触：schema.sql、docker/mysql/migrations/、openapi.yaml（零契约变更）、pom.xml、frontend/

## 四、验收要点

- 单测先红后绿：缓存命中路径（模拟 Redis 有值不装配）、版本戳失效路径（改判分事实源后
  缓存不命中）、未命中回填路径、双 parse 收敛（装配计数断言）
- 变异验证：去掉版本戳校验 → 缓存脏值被采用 → 击红；去掉缓存回填 → 未命中场错题丢失 → 击红
- 全量门禁 `mvnw.cmd clean test`（仓库根、带 clean）留证；backendTests 基线只增不减
- 性能语义验证：多场学生的重练池打开路径中 `assembleGradingCore` 调用次数断言（缓存
  预热后 ≤ 未命中场数，而非场数总数）
- M 级理由：缓存失效正确性是主要风险（脏缓存 → 错题判定错 → 学生重练池内容错），
  版本戳可行性待侦察，退化路径牵涉白名单扩界
