# 变更提案：optimize-practice-pool-hot-path

## 为什么（Why）

2026-10-16 三轮后端巡查 score/grading 域发现（高危×1 + 中×3 + 低危顺带×1，主 Agent 亲验）：

1. **高危 · 单题提交门控全量重建错题池**：`StudentPracticeService.submitPractice`（L155）每次单题重练提交都调 `getStudentWrongQuestionIds(studentId)`（L315-335）——逐场 `getPersonalReportWithoutRank`（快照 JSON 解析 + 逐题判分装配）重建**全量**错题集合，O(场数 × 题数) 的重计算叠加在每次提交热路径上；学生刷练 50 题 = 50 次全量重建。
2. **中 · 门控静默吞**：同方法 L322-323 `catch (Exception e) { continue; }` 无日志——同文件 L93、L265 同型循环均有 `log.warn`，口径不一致；DB 抖动时门控集合静默缩水、合法提交被误拒且无排查线索。
3. **中 · 批改进度轮询全列载**：`GradingQueryService.progress`（L54-77）两处 `selectList` 无列投影——submissions 载 `answers` TEXT 大列仅为内存计数；subjectiveRows 载全列（含 comment 等）仅为统计 score 非空与收集未批答卷 ID。批改期间前端高频轮询该端点。
4. **中 · 批改保存全量回读**：`SubjectiveGradingService.saveScore`（L202-205）回读 `selectWorkbenchRows(examId, questionId)` **全量学生工作台行**（含 student_answer TEXT）再内存 filter 单行——每笔批改保存都为找一个 submission 白载全考场。
5. **低 · attempt 回读无上限**：`submitPractice`（L229-233）提交后回读该题**全部**历史 attempt 推导连对——连对判定只需最近 3 条（`consecutiveCorrect >= 3` 即消灭）。

## 改什么（What）

1. **门控短路定位**：`submitPractice` 门控不再「全量重建集合 + contains」，改为单次定位验证 `isWrongQuestionOf(studentId, questionId)`——逐场装配但**命中即短路**（找到该生某场 GRADED 快照中包含该题且判分 < 满分即通过，返回 true；全部场扫描完不命中返回 false），正常路径（题在池中）止步于命中场，不再装配全部考试。**选型说明**：错题池缓存落表需 DDL + 失效语义（考试判分/发布时机），超本案零 DDL 边界，短路定位是无 DDL 前提下的最小热路径修复；`getStudentWrongQuestionIds` 保留（列表页消费），仅门控改道。
2. **静默吞对齐**：门控定位循环 catch 补 `log.warn`（对齐同文件 L93/L265 口径），异常场跳过语义不变。
3. **attempt 回读 LIMIT 3**：连对推导查询加 `.last("LIMIT 3")`——连对窗口即 3，第四条及以前必然已被窗口截断，语义等价（mastered 判定后历史更长也不改变结果）。
4. **progress 两处投影**：submissions 查询 `.select` 仅取消费列（id + gradingStatus——以方法尾部消费路径实际使用为准，实施时直读确定列集）；subjectiveRows 查询 `.select` 仅取 submissionId + score。排除 answers/comment 等 TEXT 重列。统计语义零改动。
5. **saveScore 单行回读**：废弃「全量工作台行 + 内存 filter 单行」，改单行查询（优先复用既有 submissionId 单行取数方法——B.2 台账注 listRows 已有单行取数；若形态不匹配则新增 mapper 单行方法，参数含 examId + questionId + submissionId）。回读结果字段与既有工作台行等价（响应形状不变），回读失败仍抛 INTERNAL_ERROR「批改结果回读失败」。

## 影响面（Impact）

- 落点：`score`（StudentPracticeService）、`grading`（GradingQueryService、SubjectiveGradingService + 可能的 mapper 单行方法）两包 + 对应测试；零 DDL（schema.sql 零 diff）、零契约变更（openapi paths 97 不变、响应形状不变）、零前端。
- 既有语义保全红线：错题判定口径「得分严格小于满分且非简答」不变（门控与列表页同口径）；快照回退判分链路（原题软删回退）不变；消灭计划连对 3 次口径不变；progress 从库读（@DS("slave")）与读写分离语义不变；批改乐观锁 CAS 与读己之写标记不变。
- 门禁基线：仅 backendTests 预期增长；M 级双岗隔离。

## 任务阶段

- 阶段 0 & 1：立项三件套与在途总账登记。
- 阶段 2：五项实施 + 单测先红后绿 + 变异击红留证。
- 阶段 3：全量门禁实测、基线上调、规范合入、归档、`--no-ff` 合并入 main。
