# grading 规范

> 能力域：判分（阶段 6，W7）。
> 来源：`spec/changes/add-grading-score` 合入（判分策略、客观题判分、简答批改、判分失败处理）。

## Requirements

### Requirement: 判分策略

WHEN 系统判分,

系统 SHALL 按题型选择对应判分策略；单选、多选、判断、简答 SHALL 各有独立实现，新增题型 SHALL 不改变既有核心。

#### Scenario: 按题型分派

GIVEN 一道单选题

WHEN 系统判分

THEN 调用单选判分策略

AND 与标准答案精确比对

---

### Requirement: 客观题判分

WHEN 系统判客观题,

系统 SHALL 单选/判断精确匹配，多选漏选 SHALL 给部分分、错选或多选 SHALL 给 0 分。

#### Scenario: 单选正确

GIVEN 学生答案与标准答案一致

WHEN 判分

THEN 判满分

#### Scenario: 多选漏选部分分

GIVEN 多选标准答案为 A、B、C

AND 学生只选 A、B

WHEN 判分

THEN 按配置比例给部分分

#### Scenario: 多选错选零分

GIVEN 学生答案含非标准选项

WHEN 判分

THEN 判 0 分

---

### Requirement: 简答批改

WHEN 系统判简答题,

系统 SHALL 以关键词初判提供提示分，最终分数 SHALL 由教师人工批改确定，且 SHALL 保留批改痕迹。整场判分时，已有主观批改行 SHALL 按本场答卷一次取出，而不是每份答卷每道简答各查一次；重判 SHALL NOT 覆盖教师终分、评语与 version。

#### Scenario: 教师批改留痕

GIVEN 教师批改一道简答题

WHEN 教师提交分数与评语

THEN 系统保存分数、评语、批改人与批改时间

#### Scenario: 并发批改防覆盖

GIVEN 两教师同时批改同一答卷

WHEN 乐观锁执行

THEN 仅一个成功

AND 另一个冲突重载或报错

#### Scenario: 整场判分主观行一次取出

GIVEN 同一场有多份已交卷答卷且卷面含多道简答题

WHEN 教师触发整场判分

THEN 这些答卷的已有主观批改行在进入逐份写入之前一次取出

AND 刷新初判提示分时不覆盖已有终分、评语与 version

AND 一份答卷判分失败仍只标记该份，不影响其他答卷

---

### Requirement: 判分失败处理

WHEN 判分失败,

系统 SHALL 标记判分失败，并 SHALL 支持重判或手动给分，不影响其他答卷。

#### Scenario: 标记失败可重判

GIVEN 判分过程异常

WHEN 系统检测到失败

THEN 标记该答卷判分失败

AND 教师可触发重判或手动给分

---

### Requirement: 判分进度部分批改计数

WHEN 教师查看判分进度总览,

系统 SHALL 以「存在未批简答（`subjective_grades.score IS NULL`）的已交卷答卷数」计算部分批改答卷数；该计数 SHALL 以「先归集存在未批简答的答卷 ID 集合、再单遍过滤已交卷答卷」的方式求出，复杂度 SHALL 为 O(答卷数 + 主观行数)，而不是逐答卷嵌套扫描主观行的 O(答卷数 × 主观行数)。

#### Scenario: 部分批改计数与朴素判定等价

GIVEN 一场考试的已交卷答卷列表与主观批改行

WHEN 计算部分批改答卷数

THEN 结果等于「存在至少一行 `submission_id` 匹配且 `score IS NULL` 的答卷数」

AND 空答卷列表、空主观行列表、score 全非空、score 全为 NULL、部分未批、同一答卷多道简答混合批改状态、行属于非本批答卷、答卷列表含重复项等形态下，均与逐答卷嵌套扫描的朴素判定逐点一致

AND 同一答卷多道简答中只要有一道未批即计 1 次（按答卷计，不按行计）

AND 主观行 `submission_id` 为 NULL 属越界输入、不在保证域内（库内不可达：`schema.sql` 对 `subjective_grades.submission_id` 声明 `BIGINT NOT NULL`）；该输入下朴素判定抛 NPE、集合判定返回计数值，差异如实登记而非冒称等价

> 合入注记（2026-10-01，`optimize-grading-progress-partial-algorithm`，整卡 **GO**）：判分进度 `GradingQueryService.progress` 的「部分批改答卷数」由逐答卷嵌套扫描主观行的 O(n·m) 改为「先归集存在未批简答的 `submissionId` 集合、再单遍过滤已交卷答卷」的 O(n+m)，仅动该计数块与其 import（两条 `selectList` 语句、其余计数与其他业务类零改动）。等价性由新增常驻测试 `GradingProgressAlgorithmTest`（15 例：随机 400 组固定种子 + 卡面规模 n=3000/m=6000 + 空答卷/空行/全批/全未批/部分/同卷多题混合/行属他卷/答卷列表重复/答卷项 id 为 null 等边界，以改动前原文为 oracle 逐点比对，并驱动真实端点断言 `partialGradedCount` 等于 oracle）承担。**口径修正（如实登记）**：卡面「包含 null 键 100% 等价」不成立——主观行 `submission_id` 为 null 时旧写法抛 NPE、新写法返回值；该输入库内不可达（`subjective_grades.submission_id BIGINT NOT NULL`），故两者在可达域上逐点等价、在越界输入上不等价，用例 `nullRowKeyIsOutOfDomainDivergence` 已固化该差异。**验收边界**＝仓库根 `mvnw.cmd clean test`（`JAVA_HOME=D:\develop1\jdk21`）@ 实施笔 `5d089c9` → 345/0/0/1 BUILD SUCCESS 退出码 0（基线 330→345，Skipped 1 恒为 `com.exam.support.OpenApiContractTest`）；H2 测试上下文 + Mockito 隔离单测，**未做真机性能测量**，不构成 P99 结论。

---

### Requirement: 主观题批改行分页与筛选

批改工作台主观题行端点（`GET /api/exams/{examId}/grading/subjective`）SHALL 支持服务端分页与筛选参数（page/size/onlyUngraded/name/submissionId，全部可选），响应 SHALL 恒为分页信封 `{rows, total, graded}`；分页 SHALL 以稳定排序（按 student_id）保证翻页不丢行不错行；乐观锁批改协议（casSaveScore / expectedVersion / 409 冲突）SHALL 零改动，冲突回填 SHALL 改用 submissionId 单行取数。

#### Scenario: 分页取数

GIVEN 一场考试某主观题已有若干交卷学生行

WHEN 以 page 与 size 调用

THEN 返回该页行子集（按 student_id 稳定排序）与 total（总行数）、graded（已批行数），graded 与题级进度口径一致

#### Scenario: 缺省全量

WHEN 不传 page/size 调用

THEN 信封内 rows 为该题全部行（与既有全量行为等价，仅形状改信封）

#### Scenario: 筛选下沉

WHEN 以 onlyUngraded 或 name 调用

THEN 服务端按未批改 / 学生姓名包含过滤后返回信封（语义对齐既有面板客户端筛选）

#### Scenario: 冲突回填单行取数

GIVEN 教师提交批改收到 409/1012 冲突

WHEN 前端回填最新行

THEN 以 submissionId 参数单行取数（至多 1 行），不再全量拉取后查找；expectedVersion 协议与「拉最新行回填、教师重看重打」语义不变

#### Scenario: 非法参数与越权

GIVEN page<1 或 size 超上限，或非本场归属教师调用

WHEN 请求到达服务端

THEN 非法参数返回 400；越权按既有批改 403 口径拒绝

#### Scenario: 批改提交协议不变

GIVEN 分页取数上线

WHEN 教师逐行提交批改（saveSubjectiveScore）

THEN 校验链、打回重批语义、读己之写口径与分页前完全一致

#### Scenario: 越界页返回空行（补强）

GIVEN 一场考试某主观题已有若干交卷学生行  
WHEN 传入的 page 超过根据 size 计算的最大有效页码（例如总数 5 条、size=2、传入 page=4 或 page=999）  
THEN 响应信封内 rows 返回空数组 `[]`，且 total 与 graded 仍保持真实全量统计数值。

#### Scenario: 相邻页无缝无重叠稳定排序（补强）

GIVEN 一场考试某主观题有多名交卷学生（student_id 严格不同）  
WHEN 连续请求相邻分页（Page 1 与 Page 2，统一 size）  
THEN 各页内行均按 student_id 升序排列，且 Page 1 最后一项的 student_id 严格小于 Page 2 第一项的 student_id，相邻页行集合无重叠、无遗漏。

#### Scenario: 越界页前端空态渲染与全量统计保持（补强）

GIVEN 前端批改面板接收到越界页数据（rows: []，total > 0，graded > 0）  
WHEN 渲染批改面板  
THEN 表格组件正确渲染空数据状态（无假数据报错），且顶部题级元信息「已批 X/Y」仍忠实反映信封中 graded 与 total 真实全量统计。

#### Scenario: 筛选作用域与题级进度口径一致性（补强）

GIVEN 教师在批改面板中输入学生姓名筛选或勾选只看未批改  
WHEN 触发筛选状态变更并上抛事件  
THEN 筛选操作仅对当前页展示或查询条件生效，题级进度「已批 X/Y」恒定维持信封中的全局全量统计口径，绝不同屏产生计数冲突。

> 合入注记（2026-10-02，`add-subjective-grading-pagination`）：批改工作台行端点由「单题 × 全部交卷学生全量行裸 List」就地扩参为分页信封——`SubjectiveGradePageResponse{rows, total, graded}` 恒定形状（不做裸 List/信封 oneOf 双形态），`page/size/onlyUngraded/name/submissionId` 全部可选：page/size 均缺省返回全量，`onlyUngraded`（score IS NULL）与 `name`（LIKE 包含）筛选下沉服务端、语义对齐面板既有客户端筛选，`submissionId` 单行取数（至多 1 行）专供 409/1012 冲突回填；`ORDER BY g.student_id` 稳定排序不动。`graded` 与题级进度 `SubjectiveQuestionItem.gradedStudents` 同口径（`score IS NOT NULL` 的该题全量计数，不受行筛选影响）。**两处 spec-delta 未明说的实现口径（如实登记）**：① page/size 只给其一另一参数取兜底（page=1 / size=100 上限），越界 400；② `total` 为「与 rows 同口径（筛选后、分页前）」的计数——前端据此算服务端分页页数，缺省无筛选时即总行数。**乐观锁链路零改动**：`casSaveScore`、`saveScore` 校验链、`readYourWriteMark.mark()` 主库回读（仍走未动的原 `selectWorkbenchRows`）与前端 `useGradingFlow` 协议/409/1012 语义原样。**前端协同改造**：批改页 `useQuery` queryKey 持有 page/size/onlyUngraded/nameFilter（切题目/筛选自动回第 1 页），面板移除客户端全量筛选链（filteredRows）与静态客户端分页、筛选输入与分页控件经 defineModel 上抛服务端驱动、已批计数改信封 graded/total，`refreshRow` 改 `submissionId` 单行拉取。**测试与证据**：新增集成测试 `SubjectiveRowsPaginationIntegrationTest` 8 例（分页稳定子集与 total/graded / 缺省全量 / onlyUngraded / name / submissionId 单行 / 非法参数 400 / 越权 403 / graded 与题级进度口径一致）先红（8 例中 7 红：响应非信封 NPE + 非法参数 200≠400，`evidence/red-backend.txt`）后绿（8/0/0/0，`evidence/green-backend.txt`）；前端 `gradingServerPaging.spec.ts` 9 例（词法护栏 4：无 filteredRows / 无静态客户端分页 pageSize:10 / refreshRow 含 submissionId 查询参数 / queryKey 含 page、size、筛选参数 + 行为 5：信封驱动计数 / 表格翻页与页大小上抛 / 筛选上抛 / 409 冲突后 refreshRow 单行取数并刷新 / queryFn 携带服务端分页与筛选参数且切筛选回第 1 页）先红（9/9 红）后绿（9/9，`evidence/red-frontend.txt`/`green-frontend.txt`）；`openapi.yaml` 路 A 离线重导出（响应改信封 + 5 个新查询参数）+ `gen:api` 再生成。**验收边界**＝实施笔 `c1c3ba0` 已提交状态双端全量门禁：仓库根 `mvnw.cmd clean test` → **368/0/0/1 BUILD SUCCESS 退出码 0**（基线 360/0/0/1@8db79d4 + 8 个新集成用例）；前端 lint:check / type-check:check / test 三项退出码 0，vitest 46 文件 394 例 → **47 文件 403 例**（+1 文件 +9 例）；零表变更、零迁移。**纯静态 + 集成/单测可证：未启 dev server、未跑前端构建、未碰 Docker/共享 dev。**

> 合入注记（2026-10-10，`update-grading-subjective-paging`）：本案为既有能力「主观题批改行分页与筛选」之护栏补强案（零业务实现代码、零 API 契约改动），追加上述 4 个边界与端到端协同 Scenario 并落地自动化用例。后端 `SubjectiveRowsPaginationIntegrationTest` 净增 2 例（越界页返回空 rows 且统计维持全量、相邻页严格有序递增无重叠），全量测试总数由基线 382 净增至 384（384/0/0/1）；前端 `gradingServerPaging.spec.ts` 净增 2 例（越界页空态面板渲染且顶部信封统计全量维持、筛选作用域与题级进度口径一致），全量测试总数由基线 557 净增至 559（77 文件 559 例）。三项变异校验（撤 Service 分页参数透传 3 例红、撤信封 graded 口径 5 例红、撤前端 409 回填 1 例红）经独立复核实测闭环变红并复原归零，执行侧失真记录已在 tasks.json 纠偏登记。验收边界＝main 已提交状态双端全量门禁：后端 `mvnw.cmd clean test` 384/0/0/1 BUILD SUCCESS 退出码 0，前端三门禁全绿（77 文件 559 例）。纯测试+spec，零业务改动。