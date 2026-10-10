# 提案：离线重入已领取考试（add-offline-exam-reentry，创新点 5 · Phase 2）

## 1. Why & 背景

`docs/examOnline需求规格说明书.md` §3.1 核心创新点 5「断网断电保护」。一期（`add-offline-exam-shell`，merge `87b63a6`）落地了 SW 离线壳 + 断网显性 UI + 切屏离线兜底，并立下四条设计红线，其中红线 1 为「**离线进入考试不做**：初次进入考试必须联网从服务端获取个人试卷快照（`POST /api/exam-taking/exams/{examId}/enter`），服务端时间戳与幂等快照是合规底线」。

本案为红线 1 的**定向解禁**（用户 2026-10-10 裁决：完整离线重入链路 + 超窗风险显性告知）：解禁「**已领取考试的离线重入**」子集——学生在联网时曾成功 `enter` 过的考试，断网后可从「刷新」与「列表重入」两条路径恢复作答；「未领取考试的离线首入」维持禁止（理由见 §2.3）。

## 2. 阶段 0 · 调研核实结论（指导主 agent 亲自查证，2026-10-10）

### 2.1 一期能力缺口：断网刷新后题目实际不可得

- 作答页试卷数据 **100% 来自 `enter` 响应**：`[id].page.vue` 的 `useQuery` 绑定 `createEnterExamQueryOptions` → `enterContract`（`POST /api/exam-taking/exams/{examId}/enter`），无任何本地试卷持久化。
- `draftStorage`（IndexedDB `exam-online-student-drafts` / `drafts` 仓库）只存 `{examId, version, savedAt, answers}`，源码注释明言「不缓存题目」。
- SW 对 `/api/**` 严格 network-only（一期红线 4，词法护栏 `swGuard.spec.ts` 守护）。因此断网刷新后：壳 HTML 可加载，但 `enter` 网络失败 → `questions` 为空 → 学生实际无法继续作答。一期 spec「导航请求断网回退缓存入口」Scenario 只承诺「由既有 IndexedDB `draftStorage.load` 接管恢复草稿答案」，未承诺题目恢复——**未违约，但「断网刷新重入不断考」的能力不完整**，本案补齐。
- SW 生命周期：仅作答页挂载时注册、路由离开即 `unregister()`（`swRegister.ts`）。断网时考试列表页导航无 SW 保护（白屏），「从列表重入」路径不通。

### 2.2 服务端权威链路（本案零改动的依据）

- `ExamTakingService.enter`：首次进入执行准入校验（普通考试班级归属 / 补考名单）→ 生成个人快照 `paperJson`（题序/选项乱序锁定）→ 记录 `startTime`，个人截止 = min(now + duration, exam.endTime)；重复进入幂等（`uk_exam_student` 唯一索引回读）。
- 倒计时权威：`EnterExamResponse` 携带 `startTime / deadlineTime / serverTime / remainingSeconds`；前端 `useServerCountdown` 以「拿到后端快照的时刻」为锚点 + `performance.now()` 单调秒表推算，**绝不本地推算时刻**；归零只锁定作答，超时判定 100% 在服务端（`buildAnsweringContext` 就地兜底 + `ExamSweepService` 每 10s 扫描）。
- 超窗收卷答案来源：Redis 草稿（最后自动保存），无草稿记空答案。
- **推论**：曾联网 `enter` 过的考试，其准入校验、起表时间戳、个人快照、截止时刻均已在联网时刻由服务端签发——把这份 `EnterExamResponse` 持久化到本地用于断网重入，服务端权威完整保留，无任何绕过。

### 2.3 红线 1 的本质拆解与解禁边界裁决

红线 1 禁止的实质是四件事：① 服务端准入校验（离线无法执行）；② 服务端起表见证（离线进入时刻本地声明不可信）；③ 个人快照服务端生成（离线无法生成）；④ 监考在线心跳（离线缺失）。

- **可安全解禁**（本案范围）：已领取考试（①②③ 均已在联网 `enter` 时刻完成）的离线重入；④ 如实登记为监考盲区（在线状态显示离线、行为事件走一期本地队列延迟补报）。
- **维持禁止**（登记理由，不做）：
  - **未领取考试的离线首入**：预取若起表 = 现状「进入考试」，无新能力；预取不起表则离线进入时刻不可信，必破坏「服务端时间为唯一权威」；
  - **开考前预取**：`requireEnterableExam` 拒绝非进行中考试（「考试尚未开始」），物理不可覆盖。
- **物理边界（显性告知，无法技术消除）**：断网跨越个人截止 → 服务端 sweep 从 Redis 草稿收卷（断网期间 IndexedDB 增量未达服务端）→ 恢复网络后答卷已封闭、草稿 flush 被拒。**答案丢失上限 = 断网期间作答增量**。本案以离线状态条风险文案如实告知（用户裁决），不试图绕过时间窗。

### 2.4 关键技术事实（方案设计依据）

- `EnterExamResponse` 的 `questions`（`QuestionView`）不含答案字段（`personalPaperService.toView` 产出），持久化安全；`draft.answers` 是学生本人草稿，归 `draftStorage` 管辖，**不进快照仓库**（职责不混）。
- 断网刷新后若直接用快照里的 `remainingSeconds` 重锚定倒计时，锚点重置为「读快照时刻」→ **倒计时回跳到满值**。需以持久化时刻的墙钟（`Date.now()`）差值校正。学生改墙钟的最坏后果是「显示偏多」——归零锁定与收卷裁决仍在服务端，风险由告知文案覆盖。
- 断网时考试列表 `GET /api/exam-taking/exams` 失败 → 现状 error 态（「暂无考试」与「拉不到」分开的既有纪律保持）；`canEnter`/`group` 只认后端（既有不变式），离线降级用缓存值，真相由作答页 `enter`/快照给出。
- 一期 `behaviorQueue` 已扩展过 IndexedDB 存储；快照仓库挂同库需递增 `DB_VERSION`，阶段 2 开工前先核实 `behaviorStorage` 的库/版本现状（若已升版本则顺势递增，避免版本号冲突）。

## 3. 详细方案设计

### 3.1 阶段 2 · 本地快照与列表缓存仓库（先红后绿）

- **快照仓库**：IndexedDB `exam-online-student-drafts` 库递增 `DB_VERSION`，新增仓库 `exam_snapshots`（keyPath `examId`）。持久化行形状：
  `{ examId, capturedWallClock: number（Date.now()，仅用于离线倒计时估算）, payload: { examId, examTitle, submissionId, status, startTime, deadlineTime, serverTime, remainingSeconds, questions, version } }`
  —— **脱敏纪律**：不含 `answers`/`marked`（草稿归 `draftStorage`），不含任何后端未下发的字段；结构损坏容忍（`toRecord` 模式，损坏按无记录处理不阻断作答）。
- **列表缓存仓库**：同库新增 `exam_list_cache`（单行），行形状 `{ cacheKey, userId, capturedWallClock, items: ExamListItem[] }`；写入时机 = `myExams` 成功；读取时校验 `userId` 与当前登录用户一致，不一致弃用（防换号串号）。
- 沿用 `draftStorage` 的端口模式：可注入 `DraftStorage` 式接口（load/save/remove）+ 内存实现（jsdom 单测）+ 浏览器薄封装（降级 console.warn 不阻断）。交卷确认后删除对应快照行（`remove`）。
- Vitest：读写覆盖、结构损坏容忍、脱敏形状断言（持久化行不含 `answers` 键）、换号弃用、remove 幂等。

### 3.2 阶段 3 · 作答页离线重入降级（先红后绿）

- **降级条件**（全部满足才降级）：`enter` 请求失败（`error` 置位）AND `navigator.onLine === false` AND 本地快照存在。否则维持现状 error 态（不降级成假数据——既有「拉不到≠暂无」纪律的同类取舍）。
- **降级渲染**：`snapshot` 计算链改为「服务端 `data` 优先，降级取本地快照 payload」；题目、导航、交卷面板、草稿播种（`watch snapshot` 既有链路）全部复用。本地快照不含 answers，`serverDraftOf` 得到空服务端草稿，`mergeDrafts` 与 `draftStorage.load` 保守合并后本地那份生效——播种链路天然正确，不另造第二套。
- **倒计时墙钟校正**（数据层校正，零改 `useServerCountdown`）：降级喂给倒计时的 `remainingSeconds' = max(0, payload.remainingSeconds − (Date.now() − capturedWallClock)/1000)`，`serverTime`/`deadlineTime` 同步用校正值或置空（保持「纯读快照字段 + 单调秒表」的既有机制，锚点仍是「读快照时刻」）。
- **恢复网络**：`online` 事件 → 既有 `autoSave.flush()` + `behavior.flushQueue()` 照旧；`enter` 查询由 vue-query 重试/重锚定，服务端快照覆盖本地（本地快照永不优先于服务端）。
- **UI**：降级态显性标注「离线重入中，题目来自本机缓存」（措辞纪律：禁「离线考试/离线作答/offline exam/offline answer」）；离线状态条（既有 `OfflineStatusBanner`）自然亮起。
- Vitest：降级触发/不触发分支（在线失败不降级、无快照不降级）、倒计时校正（跨 capturedWallClock 的估算正确性）、播种走本地草稿、恢复后服务端快照接管。

### 3.3 阶段 4 · SW 学生域化与列表离线降级（先红后绿）

- **SW 注册域扩大**：`student/exams` 列表页（`index.page.vue`）与作答页（`[id].page.vue`）挂载时均注册（`register` 幂等）；`onBeforeRouteLeave` 判断**离开学生考试域**（目标路由不在 `/student/exams` 下）才 `unregister()`。登录页、教师端、其他学生页不受影响。`swRegister.ts` 头注释与 `swGuard` 词法护栏同步更新。
- **列表降级渲染**：列表 `error` AND `!navigator.onLine` AND 缓存行存在且 userId 匹配 → 渲染缓存 `items` + 显性「离线缓存」标注（`data-test` 可断言）；`canEnter` 用缓存值，点击进入作答页后由阶段 3 降级链路接管。刷新按钮保留（在线时重取）。
- **风险告知**（用户裁决：显性告知）：离线状态条 / 降级标注文案包含超窗风险——「若超过考试截止时间仍未恢复网络，断网期间新保存的答案可能无法补交，以系统收卷为准」。文案集中于常量文件，措辞护栏测试断言禁用词不出现。
- Vitest：SW 注册域行为测试（mock navigator.serviceWorker）、列表降级分支、措辞护栏（渲染文案不含禁用词）、`swGuard` 护栏更新后全绿。

### 3.4 阶段 5 · 全量门禁、变异校验与分支提交

- 变异校验至少三项（由复核独立复现，执行侧记录仅作参考——治理纪律 §3.2）：
  - **A 撤作答页离线降级**（删本地快照 fallback 分支）→ 阶段 3 相关测试红；
  - **B 撤倒计时墙钟校正**（`remainingSeconds` 原值直喂）→ 校正断言红；
  - **C 撤列表降级或注入违规措辞**（在降级文案中引入「离线考试」）→ 列表降级测试或措辞护栏红。
- 前端三门禁 `npm.cmd --prefix frontend run lint:check / type-check:check / test` 全绿（核实输出非空，真实 vue-tsc/tsc 双 banner）；后端 `.\\mvnw.cmd clean test` 零改动零影响（基线 384/0/0/1，绑定收口时刻实测）。

## 4. 二期红线（再裁决版，全案有效）

1. **离线首入不做**：未领取（从未联网 `enter`）的考试离线不可进入；本地快照只作已领取考试的断网降级，永不优先于服务端；预取不起表的预备卷端点不做；
2. **绕过服务端交卷时间窗不做**：倒计时归零仍只锁定作答；离线墙钟估算仅影响显示（允许偏差），超时裁决 100% 服务端；断网跨截止的答案丢失上限如实告知，不试图绕过；
3. **离线反作弊不做**：离线重入期间在线心跳缺失（教师端如实显示离线）、行为事件本地队列延迟补报，监考盲区如实登记，不新增任何离线反作弊能力；
4. **API network-only 禁缓存不变**：快照与列表缓存是**应用层 IndexedDB 持久化**，不经 SW、不是 HTTP 缓存；SW 对 `/api/**` 仍严格放行（`swGuard` 词法护栏继续守护）。

## 5. 验收判据

1. **测试驱动（先红后绿）**：阶段 2/3/4 各自单测先行，全量绿后提交；
2. **变异校验留证**：A/B/C 三项先红后复原归零，`git diff` 逐字节归零；
3. **全量门禁**：前端三项全绿、文件/用例数净增；后端 384/0/0/1 零影响（`src/`、`pom.xml`、`schema.sql` 零改动，git diff 为空）；
4. **措辞纪律**：全部渲染文案不含「离线考试 / 离线作答 / offline exam / offline answer」；能力表述为「断网重入已领取的考试」；
5. **收口惯例**：`--no-ff` 合入、零代码收口（spec-delta 合入基线 + 归档 + README 登记，UTF-8 无 BOM）。
