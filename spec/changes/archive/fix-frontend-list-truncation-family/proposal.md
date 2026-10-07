# 提案：下拉与列表取数不截断（fix-frontend-list-truncation-family，UX 台账 U-2）

## Why

「一次取 100 条」的单页截断家族（UX 台账 U-2，登记于 `docs/frontend-improvement-candidates.md`）在其余四个取数点仍然原样存在，而同源的第五个取数点（教师端考试下拉）已经由 `unify-teacher-exam-select-pagination` 收口成「累积多页不丢前页」并写进 `spec/specs/frontend/spec.md` 基线：

- 考试创建页试卷下拉：`teacher/exams/create.page.vue:228` `query: { page: 1, size: 100 }`；
- 考试创建页班级下拉：`teacher/exams/create.page.vue:243` 同一形状；
- 转班目标班级下拉：`teacher/classes/index.page.vue:476` `query: { page: 1, size: 100 }`；
- 试卷列表：`teacher/papers/index.page.vue:72-92` `FETCH_SIZE = 100` 一次取回后**纯客户端切片分页**。

后果是同一类静默缺陷：候选超过 100 条时第 101 条起不可见且无任何提示（下拉），或第 101 份试卷在列表里永远不存在（切片）。台账「最小下一步」明确要求把本家族**并入同一次取数策略决策统一处理、不分别叠加实现**——本卡即该次统一决策：把已经验证过的累加语义抽成一份通用实现供四处复用，试卷列表则改为真正的服务端分页。

量级说明（如实登记）：本卡**未测量**试卷/班级/考试的真实数据量是否已超 100——dev 库数据量不构成分布证据。收口判据与已合入的考试下拉 Requirement 同为「取数不截断」，与量级无关：只要端点是分页形状，取全量候选就不能只发一次请求。

## What Changes

**关键裁决 1：抽一份通用累加器，四处复用，不分别实现。**
新增 `frontend/src/hooks/fetchAllPages.ts`，语义逐字照抄 `useTeacherExams.ts:18-60` 已验收的那一份：满页（`length === pageSize`）续拉、空页或未满页即停、最多 5 页保护上限、**单页请求失败保留已累积部分且不抛错**。导出 `fetchAllPages<T>(fetchPage, options)` 泛型 + `FETCH_ALL_PAGE_SIZE = 100` / `FETCH_ALL_MAX_PAGES = 5`。

**关键裁决 2：`useTeacherExams.ts` 只允许「改一行委托」，既有测试与行为零变化。**
`fetchAllTeacherExams` 函数体改为委托 `fetchAllPages`（`pageSize`/`maxPages` 仍取自身的 `TEACHER_EXAMS_*` 常量默认值），签名、导出常量、`createTeacherExamsQueryOptions` 全部不动。护栏是既有的 `frontend/src/hooks/__tests__/useTeacherExams.spec.ts`——它必须**原样全绿**，用它证明委托抽取没有改变语义（而非新增断言）。

**关键裁决 3：下拉沿用累加器的「尽力而为」失败语义，列表页才用错误 Alert。**
硬约束「下拉/列表的失败态呈现对齐既有口径（错误如实，不伪装空态）」在两类载体上的既有口径本就不同，本卡按各自既有口径对齐，不新造第三种：

- **下拉**（试卷/班级/转班目标）：对齐考试下拉家族既有语义——累加器吞掉单页失败、返回已累积部分、不抛错、不阻断页面。该语义已在 `spec/specs/frontend/spec.md` 的考试下拉 Requirement 里作为基线存在，五个在用的教师端考试下拉正是这个形态；下拉改造成「失败即整块红 Alert」会改变五个既有消费点的形态，属越界。
- **列表**（试卷列表页）：对齐 U-1「查询失败与业务态分离」刚合入的口径——查询失败用 `Alert type="error"` 显性呈现后端 message、表格不渲染成空态；成功且无数据时空态照常。

**关键裁决 4：试卷列表改服务端分页，`total` 用「满页则至少预留下一页」推断。**
`papers/index.page.vue` 改为形态对齐 `teacher/exams/index.page.vue`：`pageNum`/`pageSize` ref 进 `queryKey`、翻页发起真实请求、`@change` 驱动。与考试页不同的一处必须如实登记：考试页分页器写的是字面量 `total: pageNum * pageSize`，而按 antd 分页源码（`vc-pagination/Pagination.js` 的 `calculatePage = Math.floor((total-1)/pageSize)+1`、`hasNext = current < calculatePage`），该写法在第 1 页满页时算出 `calculatePage === 1`，**下一页按钮根本不可点**——照抄它会让「翻页真实请求」这条断言永远无法被触发。本卡对同一裸列表（无 total 信封）契约改用可点下一页的推断式 `total`：满页时 `+1` 预留下一页、末页（未满页）不预留。这是**为可用性偏离考试页字面写法**，不是笔误，注释与 spec-delta 均登记。

## Impact

### 受影响的规范

- `spec/specs/frontend/spec.md` — ADDED：「下拉与列表取数不截断」Requirement（累加器语义与保护上限 / 三处下拉取全量候选 / 列表服务端分页且翻页真实请求 / 查询失败不伪装空态 / SDK 与分页参数以契约为准）。
- 既有「考试下拉累积多页不丢前页」两个 Scenario：**行为零变化**，仅实现位置从 `useTeacherExams.ts` 移到 `fetchAllPages.ts` 委托。

### 受影响的文件

- 新增 `frontend/src/hooks/fetchAllPages.ts`；
- `frontend/src/hooks/useTeacherExams.ts`（`fetchAllTeacherExams` 改一行委托，签名/常量不变）；
- `frontend/src/pages/(dashboard)/teacher/exams/create.page.vue`（试卷、班级两个下拉改累加器）；
- `frontend/src/pages/(dashboard)/teacher/classes/index.page.vue`（转班目标下拉改累加器）；
- `frontend/src/pages/(dashboard)/teacher/papers/index.page.vue`（服务端分页 + 查询失败 Alert，移除 `FETCH_SIZE` 本地切片）；
- 新增用例：`frontend/src/hooks/__tests__/fetchAllPages.spec.ts`、create 页下拉用例、classes 转班下拉用例、papers 分页用例（`?raw` 词法护栏 + 行为）；
- `frontend/src/pages/(dashboard)/teacher/grading/__tests__/gradingServerPaging.spec.ts`（**第三抖专项**：单个用例补显式超时上限，断言与交互零改动，见下）。

### 需要迁移

- 无（零表变更、零后端、零契约变更、零 `gen:api`）。

## 第三抖专项（开工基线期触发，如实登记）

阶段 0 基线锚定期，`gradingServerPaging.spec.ts` 的「queryFn 携带 page/size/onlyUngraded/name…」用例在**未做任何改动的 main@31dfc17** 上连续三次全量跑超时（`Error: Test timed out in 5000ms`），定向单跑同文件 9/9 绿。按派发卡「定向+全量复跑并如实记录（两案两抖，第三抖触发专项）」执行专项，测得的机理（证据 `evidence/jitter-diagnosis-full-run-timeout60s.txt`）：

- 该用例在**全量套件并行**下实测 **5034ms**，贴着 vitest 默认 5000ms 上限（同文件其余用例 3465/1865/1820/965ms）；
- 定向单跑整文件 9 例仅 3929ms，全量跑同文件 13718–14842ms——**CPU 争抢放大约 3.5 倍**，属边际超时而非逻辑缺陷或挂死；
- 同一超时抖动在上一个归档卡（`add-frontend-invite-code-admin`，2026-10-07）的基线已登记一次，当次按「定向+全量复跑」处置并把「是否构成三抖触发专项」提请指导判定；本卡派发文本已给出该触发条件，故在此实施最小处置。

**处置**：只给这一条实测越界的用例补显式超时上限（`}, 15000);`，5034ms 的约 3 倍余量，可吸收两次负载翻倍），**不改任何断言、不改交互步骤、不动同文件其余用例**（含 3465ms 那条——它四次全量跑均未越界，无越界证据不动）。该改动随实施笔入库、可整块单独回滚；若不认同「专项改动进入本卡」的口径，回滚这一个 hunk 即可，其余交付不受影响。

## 边界与不做

- **零后端改动**：`src/main`、`src/test`、`pom.xml`、`schema.sql`、`openapi.yaml` 均不动；零契约变更零 `gen:api`；SDK 函数名（`pagePapers`/`pageClasses`/`page1` 等在 `sdk.gen.ts` 的实际导出）与 `page`/`size` 参数一律以 `types.gen.ts` 契约为准，不手写 URL；
- 不给试卷/班级端点加服务端搜索或筛选参数（属后端卡，U-3 同源另议）；
- 不改考试下拉的 `queryKey` 首元素约定（`exams` 保持不变，既有 mock 向后兼容）；
- 不改五个在用教师端考试下拉的失败呈现形态（裁决 3）；
- 不测量、不承诺「数据量未超 100 所以无需修复」；
- 不为试卷列表伪造后端 total；分页器 `total` 只做「满页则至少还有下一页」的下界推断，页码数字可能小于真实页数——如实呈现，不谎称精确总数；
- 不重写 `useTeacherExams.ts` 其余部分，不动 `createTeacherExamsQueryOptions`。

## 验收判据

- 先红后绿：累加器纯逻辑用例、三下拉全量候选用例、试卷页真分页用例在实施前跑一次留红，实施后转绿；
- `useTeacherExams.spec.ts` 既有用例在委托改造后**原样全绿**（委托等价性护栏）；
- 实施笔仅含 `frontend/src`；收口笔仅含 `spec/`（`git diff --name-only <实施笔> HEAD -- src frontend pom.xml openapi.yaml` 为空）；
- 前端三项（`lint:check` / `type-check:check` / `test`）退出码 0，vitest 相对基线（53 文件 429 例 @ `31dfc17` 当次实测）只增不减；
- 仓库根 `mvnw.cmd clean test` 与基线（`31dfc17` 当次实测）持平——本卡零后端改动；
- `git status` 终态除白名单未跟踪文件外干净。
