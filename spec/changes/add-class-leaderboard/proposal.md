# 提案：班级匿名榜单（add-class-leaderboard）

## 1. Why & 背景

学生现可查本人成绩与名次（`myScore`，score-management spec「成绩排名」Requirement：聚合计数、并列跳号），教师端已有实名全榜（`ScoreService.publishPreview` + `exportClassSheet`）。缺口：**学生看不到自己与全班的相对位置分布**（前 10 是谁、多少分、自己差多少）。本案新增学生视角「班级匿名前 10 榜单」，小功能全栈案。

## 2. 阶段 0 · 调研核实结论（2026-10-10）

1. **排名口径已标准化**：`RankCalculator` 总分排序、BigDecimal.compareTo 数值并列、同分同名次跳号（1,2,2,4）、null 名次 0；`publishPreview` 过滤条件 = `status = STATUS_GRADED` AND `total_score IS NOT NULL`，orderByDesc(totalScore)。
2. **学生本人名次已存在**：myScore 以聚合计数（更高分人数 + 1）取数，404 条件显式（本人行缺失 / 总分未产生 / 状态非已批改 →「暂无本人成绩记录」），门控含「考试已发布」。
3. **补考口径**：`MakeupScoreService.finalScore` 是主考 + 补考家族的合并最终成绩口径；`publishPreview` 用主考场次 totalScore。**裁决：榜单跟随 publishPreview 口径**（与教师榜单同源，避免双口径漂移；补考合并口径属最终成绩查询域，不混入榜单）。
4. **数据模型**：`exam_submissions`（student_id/total_score/grading_status/status）、`users`（name）、`exams`（class_id/status）齐备；`ClassService.listStudentIds` 不需要——榜单按 examId 聚合该场答卷即可，不触班级域。
5. **性能纪律**：spec 要求学生名次路径「行级取数不随班级人数增长」——榜单取数固定 LIMIT 10 + 本人行单查，天然满足。

## 3. 方案设计

### 后端
- **`com.exam.score.service.ScoreLeaderboardService`**（只读聚合，独立类）：
  - `leaderboard(examId, currentStudentId)`：复用/对齐 myScore 的准入与发布门控（学生必须是该场考生、成绩已发布才可见；若 myScore 门控逻辑为 controller/service 私有方法不可直接复用，则在新 service 内实现**同口径**校验，禁止改动既有方法体）；取数 = `status = STATUS_GRADED` AND `total_score IS NOT NULL` orderByDesc(totalScore) **LIMIT 10**（列投影：student_id/total_score 两列，沿用 M5 冻结列集纪律）+ 本人行单独查询（复用 myScore 同判定，404 语义一致）。
  - 排名：榜单行名次用聚合计数或复用 RankCalculator（10 行内均可，口径须与竞赛排名逐条一致）。
  - 匿名化：非本人行 `displayName = 姓 + "**"`（如「张**」），**不下发他人 studentId**（防枚举）；本人行实名 + `isMe = true`。
  - 响应 DTO：`{ examId, examTitle, myRow: { rank, totalScore, isMe: true } | null, top: [{ rank, displayName, totalScore, isMe }] }`；本人不在前 10 时 myRow 附榜尾本人名次。
- **端点**：`ScoreController` 新增 `GET /api/exams/{examId}/scores/leaderboard`（领域归属惯例：成绩查询归 ScoreController；与 myScore 同路径风格），学生角色，当前用户来自既有鉴权上下文。

### 前端
- 新增 `LeaderboardPanel.vue`（轻量展示组件：榜单表格 + 本人行高亮 + 空态文案），挂载于学生「单场考试逐题回顾」页（该页已含本人总分与名次，榜单为自然延伸）；脱敏名 + 分数 + 名次三列渲染；不下发也不渲染他人 ID。
- API SDK 按既有代码生成方式接入新端点。

### 不做（登记理由）
- 教师端榜单：publishPreview + 导出已覆盖实名全榜；
- 跨考试累计积分榜 / 班级维度总榜：无需求输入，避免范围膨胀；
- 补考合并口径入榜：见 §2.3 裁决。

## 4. 验收判据

1. 先红后绿：后端集成测试（真实链路：未发布 404 / 非考生 403 / 并列跳号 / 匿名脱敏 / 本人不在前 10 的榜尾行 / LIMIT 边界）+ 前端组件测试；
2. 变异校验 ≥3（复核独立复现）：A 撤发布门控（未发布考试可查榜）→ 红；B 撤匿名脱敏（泄露实名或他人 studentId）→ 红；C 撤本人行/myRow → 红；
3. 后端 `.\mvnw.cmd clean test` 全绿、用例数净增（基线 384/0/0/1 只增不减）；前端三门禁全绿净增（基线 84 文件/604 例）；
4. spec：score-management 新增 Requirement「班级匿名榜单」、frontend 新增对应 Requirement；
5. 红线：不改 com.exam.clazz 包、不改 ScoreService/MakeupScoreService 既有方法体、schema.sql 零改动（无新表）、契约按既有 OpenAPI 惯例（新端点入契约测试基线）。
