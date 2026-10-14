# add-wrong-question-practice：错题本重练（消灭计划）

## 背景与必要性

愿景 §9.4「错题本（自动归集/分类/重练/消灭计划）」与创新点 3「错题本 + 消灭计划——
自动归集错题，连续 3 次做对自动移出，形成学习闭环」（2026-10-14 候选池勘误后用户裁决
立案，中优先）。现状（两轮 Explore 核证）：错题**查询**已建成——`GET /api/scores/my/wrong-questions`
（考试粒度分页）与 `GET /api/scores/my/exams/{examId}/review`（单场逐题回顾）两端点 +
前端错题本页；但**重练与移出机制零代码**（后端无 practice/retry 端点，前端页面纯展示）。
创新点 1/4 已完整交付，本案完成后愿景三大已建创新点闭环。

## 现场核证基础（HEAD=a25fb2f，2026-10-14 Explore 两轮核证）

- 错题判定口径在案：spec 基线「错题本分页查询」——以学生已参加且**已发布**考试为粒度，
  PersonalReport 现场重算，得分严格小于满分（含 0 分与多选/主观部分得分）判为错题；
  未发布考试严格隐藏。
- 判分同源纪律在案：spec 基线「读侧装配不得携带消费路径未使用的重计算」——判分唯一
  事实源为现场重算，轻量入口与完整入口共用同一判分核心，**绝不另造第二套判分口径**；
  本案重练判分必须遵守同一纪律。
- 可机判题型齐备：客观判分覆盖 SINGLE/MULTIPLE/JUDGE/FILL_BLANK（填空随
  add-question-fill-blank 交付）；SHORT_ANSWER 无标准答案机器判分。
- 前端落点：`frontend/src/pages/(dashboard)/student/wrong-questions/index.page.vue`
  现为纯展示（分组/答案对比/解析/分页，L2-133），重练入口与作答模式空白。
- 单题判分复用入口：候选为既有客观判分核心（ObjectiveGradingService /
  答案归一化判分路径）——**具体入口由执行侧核验**，若需改动既有判分行才能复用则触发
  停止条件。
- 数字链：后端 448 / 前端 657 / openapiPaths 82（2026-10-14 收口时点）；本案新增
  2 端点（pool/submit），paths 82→84，gate-baseline 三值同批上调。
- DDL 前提：新表 `practice_attempt` 走 `src/main/resources/schema.sql` 唯一入口
  （AGENTS.md 硬约定 1，H2/MySQL 双兼容，AUTO_INCREMENT 块同写 PRIMARY KEY (id)），
  同批配套实体与 Mapper（硬约定 3）；存量 dev 库补表脚本放
  `docker/mysql/migrations/`，应用与验证由用户另行裁决（硬约定 4）。

## 设计裁决

1. **重练形态＝逐题刷题流，不做「重练卷」**：进入重练模式逐题作答、提交即判分、
   即时反馈（对/错、正确答案、解析、连对进度）。不生成考试形态的卷（无总分/排名/
   时间窗），不复用 exam-taking 状态机（预约/缺考/A卷B卷/交卷窗口对练习语义全是
   无关约束，复用即语义污染）。落点 score-management 域（紧邻
   StudentWrongQuestionService），不新建能力域。
2. **持久化＝明细表而非冗余状态列**：新表 `practice_attempt`（id, student_id,
   question_id, is_correct, created_time；索引 (student_id, question_id, id)），
   每次提交一条；「连对次数」「已消灭」从明细**推导**，不存 consecutive_correct /
   is_removed 冗余列（愿景草案 L378-385 的 wrong_questions 表形态不采纳——冗余
   状态列需额外同步，明细表单一事实源且天然保留历史）。
3. **移出口径＝纯练习序列（愿景字面）**：同一题目连续第 3 次练习答对 → 消灭；
   任一次答错 → 计数归零。**考试再次答错不自动重置连对计数**（连对 3 次证明已
   掌握；若学生想重练，练习答错一次即自然归零）——列为明确边界，避免在交卷判分
   链路挂钩子侵入考试域红线。
4. **答对口径＝满分（判分口径同源）**：重练提交复用既有客观判分核心对该题判分，
   得满分＝答对；多选漏选部分得分**不算**答对（消灭＝能拿满分，与错题判定
   score<max 互补）。SHORT_ANSWER 错题保留于错题本列表（可看解析自评）但
   **不入重练池**。
5. **重练池＝question 维度全量构建**：本人全部已发布考试的错题、跨考试去重、
   仅可机判题型、排除已消灭。学生维度已发布考试场次有限，全量重算可接受
   （复用错题本逐场重算逻辑）；不做缓存与增量（列为边界，实测过慢再议）。
6. **既有列表行为扩展（向后兼容）**：错题条目增 `consecutiveCorrect` 与
   `mastered` 字段；默认过滤已消灭条目，可选参数 `includeMastered`（默认 false）
   翻回；既有判定/分页/解析语义逐字零漂移。
7. **端点**：`GET /api/scores/my/wrong-questions/practice/pool`（重练池）+
   `POST /api/scores/my/wrong-questions/practice/submit`（questionId+answer →
   判分反馈+连对状态）；仅本人（token 学生）。paths 82→84；SDK 再生成；
   gate-baseline 三值同批上调。
8. **前端形态**：错题本页加重练入口与连对进度（x/3），重练模式逐题作答卡片、
   即时反馈、消灭态提示；零新依赖，对齐既有 UI 风格。

## 红线（零触碰）

- 判分核心既有判分行（ObjectiveGradingService / 归一化判分路径只许调用，不许改）；
  错题本既有判定口径与 PersonalReport 现场重算装配（score<max、已发布门控、
  考试粒度分页、解析语义）；「绝不另造第二套判分口径」纪律（重练判分必须复用
  既有核心，护栏用例锁定与既有口径一致）。
- 交卷/判分/汇总/发布链路（不挂钩子、不写系统 attempt）；exam-taking 域全部
  既有端点与状态机。
- pom.xml（零新依赖）、application*.yml、auth/security、com.exam.clazz、
  `.github/`（gate-baseline 三值上调除外）、dev docker-compose.yml、
  spec/specs 既有基线、其余 score 域端点。

## 验收边界

- 池构建：构造跨多场考试重复出错题（去重）、SHORT_ANSWER 错题（列表可见池排除）、
  已消灭题（池排除）→ pool 逐项与构造一致。
- 判分：单选/判断答对答错、多选部分对（不算答对）、填空精确匹配 → submit 反馈
  与既有判分口径一致（同题同答案在考试判分与重练判分结果相同，护栏）。
- 消灭：连对 3 次→mastered（第 3 次响应即报，列表默认隐藏、includeMastered
  可见）；连对 2 次后答错→计数归零。
- 既有错题本列表/单场回顾用例零回归（口径零漂移护栏）；他人 questionId 提交/
  池查询仅限本人数据。
- 双端门禁全绿且用例数净增；paths 82→84；变异自证 ≥3 项（候选：撤连对计数→
  答错不归零→红；撤满分口径→多选部分对判答对→红；撤池题型过滤→简答入池→红；
  撤本人门控→可练他人题→红）；schema.sql 新表 + 实体 + Mapper 同批（硬约定
  自查命令全绿）。
- 存量 dev 库补表脚本提交 ≠ 已应用（硬约定 4），应用时点由用户裁决。

## 停止条件

- 单题判分无法在不改既有判分核心行的前提下复用（需另造口径或复制判分逻辑）——
  停止上报裁决。
- 连对推导与既有错题列表装配（PersonalReport 逐场重算）在实现上冲突且无法在
  不改既有装配行的前提下消解——停止上报。
- schema.sql 新表 H2/MySQL 双兼容或实体-Mapper 映射受阻（硬约定 3 自查不过）——
  停止上报。
- 任一红线需破坏才能实现——立即停止上报。
