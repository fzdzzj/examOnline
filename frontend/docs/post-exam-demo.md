# 考后闭环端到端演示脚本（前端阶段 23）

> 本脚本覆盖「交卷 → 批改（含一次并发冲突）→ 汇总预览 → 发布 → 学生查询 → 申请复核 →
> 教师处理 → 学生看到结果」，另加「force-end → 缺考名单 → 为该生创建补考」。
> 每步写明：**前置数据 / 操作 / 期望现象 / 它证明了后端哪条能力**。

## 0. 实跑状态声明（先读这一节）

| 项目 | 状态 |
|---|---|
| 前端门禁（lint / type-check(app+config) / vitest） | ✅ 实跑：0 错误 / 0 错误 / **14 文件 121 用例全通过** |
| 后端全量回归 | ✅ 实跑：**218 run / 0 fail / 0 error / 1 skipped**（BUILD SUCCESS，本阶段后端零改动） |
| 前端页面与后端真机联调 | ❌ **未实跑** |
| 并发批改冲突的真机实跑 | ❌ **未实跑** |

**未实跑原因（如实记录，不作任何"已验证"表述）**：

1. 后端 dev 实例需要以 `DB_URL=jdbc:mysql://127.0.0.1:13306/...`、`DB_PASSWORD=root123`（容器
   `exam-mysql-master`，宿主 13306）+ 从库 3307 的环境变量启动；本轮**启动命令未获执行授权**（审批超时），
   应用没有起来，因此**没有一次真实的 HTTP 请求发生**；
2. Docker 容器已手动拉起（`exam-mysql-master` / `exam-mysql-slave` / `exam-rabbitmq` 均 Up），
   库里存在 3 个历史账号（admin / stuobs1 / t20240876）但**口令未知**，本轮未通过改库的方式重置口令
   （那会污染 dev 数据，且不属于本阶段写入边界）；
3. 阶段 22 `add-frontend-student-taking`（学生答题界面）**尚未合入**，因此脚本第 1 步「学生交卷」
   目前只能走后端接口（`POST /api/exam-taking/exams/{examId}/enter|submit`）或既有数据，没有 UI 可点。

因此下面每一步的「期望现象」是**按后端已核实契约推导的设计预期**，不是本轮观察到的结果；
重跑时必须逐步核对，跑通的打 ✅ 并贴真实 HTTP 状态与界面表现。

## 1. 前置：环境与账号

| 项 | 值 |
|---|---|
| 后端 | dev profile，宿主 8080（本机 MySQL80 占 3306、Windows Redis 占 6379，不改端口、不停服务） |
| 主库 | Docker `exam-mysql-master` 宿主 **13306**，`root/root123`，库 `exam_online` |
| 从库 | Docker `exam-mysql-slave` 宿主 **3307** |
| 启动前必设 | `DB_URL` / `DB_PASSWORD` / `SLAVE_DB_URL` / `SLAVE_DB_PASSWORD` |
| 启动成功判据 | `GET /actuator/health` 返回 UP（日志里 `ExamSubmitSender` 的 `IllegalStateException` 是遗留 #10，非致命，不要顺手修） |
| 角色 | 教师（TEACHER，建卷 / 批改 / 发布）、学生（STUDENT，答题 / 查分 / 复核）、管理员（ADMIN，撤回） |

## 2. 主链路

### 步骤 1 — 学生交卷（产生待批改答卷）

- **前置**：一场已发布、已开始的考试；试卷含客观题 + 简答题；该生在 `exam_candidates` 准入内。
- **操作**：`POST /api/exam-taking/exams/{examId}/enter` → 答题 → `POST /api/exam-taking/exams/{examId}/submit`。
  （阶段 22 前端未合入，本步走接口或直接复用既有答卷。）
- **期望现象**：答卷落 `exam_submissions`，状态 = 已提交；客观题由后端自动判分。
- **证明的能力**：交卷幂等（三重幂等 + 状态机 CAS）、客观题自动判分。

### 步骤 2 — 教师批改（逐题打分 + 评语）

- **路径**：`/teacher/grading`
- **操作**：选考试（状态 ≥ 已结束，入口才出现）→ 主观题进度（`GET .../grading/subjective/questions`）
  → 「去批改」→ 同题学生行（`GET .../grading/subjective?questionId=`）→ 逐人填分数 + 评语 → 提交。
- **期望现象**：提交成功提示「批改已保存」，该行 `graded` 变已批、`version` 递增；题级进度 +1。
- **证明的能力**：逐题批改落 `subjective_grade`（`exam:manage` 权限 + 教师归属校验）；
  打分校验（非负 / 不超满分 / 最多 1 位小数，后端 400「批改分数不得超过本题满分 X」为权威）。

### 步骤 3 — 制造一次并发批改冲突（本阶段最核心的一步）

- **操作**：同一份答卷、同一题，开两个教师会话（或两个浏览器窗口）：
  1. A 打开该行，读到 `version = v`；
  2. B 提交一次成功 → 该行 `version` 变 `v+1`；
  3. A **不改分数**直接提交（仍带 `expectedVersion = v`）。
- **期望现象（界面）**：
  - 顶部黄色 Alert：「**该答卷已被他人批改，请查看最新内容后重新打分**」+ 说明「系统不会用你刚才的分数覆盖他人的批改」；
  - 该行自动刷新为最新内容（他人分数/评语 + 新 `version`），教师重看后重新打分；
  - **不出现**「已保存 / 批改已保存」成功提示；**不会**自动用 A 的旧分数重试提交。
- **期望现象（后端）**：`POST /api/exams/{examId}/grading/subjective/save` 返回 **HTTP 409**，
  业务码 **1012**，消息「批改已被他人更新，请刷新后重试」
  （`SubjectiveGradingService` 抛 `BusinessException(STATE_CONFLICT, ...)`，
  `SubjectiveGradeMapper.casSaveScore` 的 `UPDATE ... WHERE id=? AND version=?` 影响 0 行）。
- **证明的能力**：深水区 3「并发批改不覆盖」——后端 CAS 乐观锁 + 前端把冲突**可见化**而不是吞掉。
- **自动化兜底**：`src/hooks/__tests__/useGradingFlow.spec.ts`（8 例）断言了
  「提示文案 + 只调用 saveScore 一次（不重试）+ 不算成功 + 拉取最新行」四件事。

### 步骤 4 — 汇总成绩

- **路径**：`/teacher/scores`，选考试 → 「汇总成绩」按钮（仅状态为**已结束**且**非已发布**时出现）。
- **期望现象**：回显 `本次汇总 N 人 / 跳过 M 人 / examGraded=true|false`；
  若 `examGraded=false`（仍有主观题未批），发布按钮**不会出现**（后端判定）。
- **证明的能力**：`POST /api/exams/{examId}/scores/summarize` 幂等；汇总门槛由后端裁决（≥ 已结束且 ≠ 已发布，
  否则 400「考试尚未结束，不能汇总成绩」/「成绩已发布，须先撤回再重新汇总」）。

### 步骤 5 — 发布前预览 → 发布

- **操作**：「发布前预览」（`GET .../scores/publish-preview`）→ 核对榜单（总分 / 排名 / 部分批改标记）
  → 「发布成绩」→ 确认弹窗。
- **期望现象**：结果表按后端 `ScoreActionItem` 逐场展示（本场 success=true）；考试状态变**已发布**；
  学生端立即可见。重复点发布 → 后端返回「已发布（幂等跳过）」，不报错。
- **证明的能力**：发布仅 `GRADED → PUBLISHED`（后端 CAS 状态机），批量接口**部分成功语义**
  （单场失败不影响其余，逐场返回 message）；预览门槛 ≥ 已批改（否则 400「成绩尚未汇总，请先执行汇总」）。

### 步骤 6 — 学生查询成绩

- **路径**：`/student/scores`，选考试。
- **期望现象**：展示客观题 / 主观题 / 总分 / 排名与「已全部批改」标记。
  - 若考试未发布 → 显示 **「成绩未发布」**（后端 400「成绩待发布」映射而来），**不是**空白也不是 0 分；
  - 若已提交复核申请且在处理中 → 显示 **「成绩复核中，暂不可见」**（后端 `reviewing=true` 且三处分数置 null）。
- **证明的能力**：成绩可见性完全由后端裁决（后端 `myScore` 对未发布统一 400，避免泄露批改进度）；
  复核中隐藏也是后端置空的结果，前端不本地推断。

### 步骤 7 — 学生申请复核

- **操作**：成绩卡下方「申请成绩复核」→ 填理由 → 提交（`POST /api/exams/{examId}/score-reviews`）。
- **期望现象**：提示「复核申请已提交（待教师处理）」；
  **页面不显示「剩余次数 / 剩余天数」**——后端没有资格查询端点，前端不本地计数（硬约定）。
  超限或重复申请时，界面**原样显示后端拒绝文案**（超时窗 400；重复申请 1001「数据已存在，请勿重复提交」）。
- **证明的能力**：复核限 1 次 / 7 天窗口由后端在提交时校验；两端规则不漂移。

### 步骤 8 — 教师处理复核

- **路径**：`/teacher/reviews`，选考试 → 列表（`GET /api/exams/{examId}/score-reviews`）→ 「处理」。
- **操作**：选「同意（可调整总分）」或「驳回（维持原成绩）」+ 调整后总分（同意时可选）+ 处理说明
  → `POST /api/score-reviews/{reviewId}/handle`。
- **期望现象**：列表状态变「已同意 / 已驳回」，处理说明落在 `result` 字段；
  只有「待处理 / 处理中」才有处理按钮，已出结论的不给入口。
- **证明的能力**：教师端复核处理（`exam:manage`），结论与调整说明可追溯。

### 步骤 9 — 学生看到复核结果

- **路径**：`/student/scores` 重新查询。
- **期望现象**：`reviewing` 变为 false → 成绩重新可见；若教师同意并调分，总分是**后端返回的新总分**
  （前端不做任何本地合并/计算）。
- **证明的能力**：复核闭环——处理完成后学生端恢复展示，且展示的是后端权威分数。

## 3. 附加链路：force-end → 缺考 → 补考

### 步骤 10 — 强制结束考试并标记缺考

- **操作**：`/teacher/exams` 对**进行中**的考试点「强制结束」（`POST /api/exams/{id}/force-end`）。
- **期望现象**：状态变已结束；未交卷学生被记缺考（阶段 12 已修复：`ExamService.forceEnd` 调 `markAbsence`）。
- **证明的能力**：缺考标记的两条路径之一（强制结束），与自然到点共用 `AbsenceService.markAbsence`
  （`INSERT IGNORE` + 唯一索引，幂等）。

### 步骤 11 — 查看缺考名单

- **路径**：`/teacher/absences`，选考试。
- **期望现象**：列出缺考学生（ID / 姓名 / 标记时间）。两条路径（自然到点、force-end）产生的标记**都能看到**。
- **诚实边界**：后端 `AbsenceItemResponse` 只有 `studentId / studentName / markedTime`，
  **没有标记来源字段**，因此界面**不区分**「哪条路径产生的标记」——前端不编造来源。
- **证明的能力**：缺考标记幂等 + 两条结束路径统一入口。

### 步骤 12 — 为缺考学生创建补考

- **操作**：缺考名单行内「为该生创建补考」→ 跳 `/teacher/makeups`（预填主考与该生）
  → 需可先「查询补考候选人」（`GET /api/exams/{id}/makeup-eligible?passLine=60`，原因由后端给出）
  → 勾选学生 → 填标题 / 起止时间 / 时长 / 成绩规则 → 创建。
- **期望现象**：结果卡片回显 `补考考试 ID / parent_exam_id / 成绩规则 / 准入人数`；
  补考是**独立考试记录**，可在「考试管理」里看到并单独发布。
- **诚实边界（界面已明示）**：**不展示「主考 vs 补考合并后的最终成绩」**——
  `MakeupScoreService.finalScore` 全仓库零调用（遗留 #5），后端没有该接口；
  需要该展示须先单独立项 `add-makeup-final-score`（后端功能变更）。
- **证明的能力**：补考独立记录 + `parent_exam_id` 关联 + `exam_candidates` 准入。

## 4. 导出（可穿插在步骤 5 之后）

- **路径**：`/teacher/scores`，状态 ≥ 已批改时导出按钮可用。
- **操作**：全班成绩单 / 逐题得分明细 / 题目统计（三个按钮）；预览表内每行的「Excel / PDF」个人成绩单。
- **期望现象**：按钮转 loading + 「正在由后端生成文件（SXSSF 流式写）」→ 完成后浏览器自动下载，
  文件名取后端 `Content-Disposition: filename*=UTF-8''...`；失败时显示后端原因 + 「重试上一次导出」。
- **证明的能力**：导出由后端 SXSSF 流式生成（窗口 100 行，防 OOM），前端只触发 + blob 保存，
  **不在前端取全量数据拼表**（未引入 `xlsx` / `exceljs`）。

## 5. 每一步对应的自动化证据

| 演示步骤 | 自动化守门 | 文件 |
|---|---|---|
| 步骤 3 并发冲突 | 8 例（冲突可见 / 不重试 / 不算成功 / 拉最新行 / 校验拦截 / 非冲突错误 / 只认 1012 / 缺 version） | `src/hooks/__tests__/useGradingFlow.spec.ts` |
| 步骤 4/5 动作可用性 | 6 例（五状态动作集合 / 非管理员无撤回 / 未知状态 fail-closed） | `src/utils/__tests__/scoreActions.spec.ts` |
| 步骤 6/9 成绩可见性 | 5 例（三态 + reviewing 时不含分数字段 + 非「成绩待发布」400 不误吞） | `src/utils/__tests__/scoreVisibility.spec.ts` |
| 步骤 2 打分校验 | 5 例（0/满分/一位小数通过；空值/非数字/负数/超满分/两位小数拦截） | `src/utils/__tests__/gradingValidation.spec.ts` |
| 步骤 4 导出 | 4 例（文件名解析 / 成功保存 / 失败抛原因 / 同 URL 重试成功） | `src/utils/__tests__/exportDownload.spec.ts` |
| 步骤 7/8/11 状态映射 | 5 例（1012/1001 常量 / 复核四态 ongoing 语义 / 补考规则 / 缺考无来源字段） | `src/constants/__tests__/postExam.spec.ts` |
