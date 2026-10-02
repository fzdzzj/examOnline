# 变更提案：统一教师端考试下拉取数与翻页策略（unify-teacher-exam-select-pagination）

## 背景与动机

前端巡查台账（`docs/frontend-optimization-candidates.md`，快照 `bd5c645`）候选 2 与候选 3 指向同一处「教师端考试下拉取数」决策，台账纪律 #3 明确要求二者合并处理、不分别叠加：

- **候选 2（成绩页只取第一页）**：`teacher/scores/index.page.vue` 写死 `PAGE_SIZE = 50` 且 `examPage` 从不自增，教师考试数 > 50 时下拉只列第一页，更早的考试无法在页内选中。
- **候选 3（批改页累积翻页丢前页）**：`teacher/grading/index.page.vue` 用 `watch(exams, list => { if (list.length === examPage.value * PAGE_SIZE) examPage.value += 1 })` 做「满页即再拉一页」。但 `exams` 是**当前 page 参数对应查询的覆盖式数据**，`examPage` 自增只是换了 `queryKey` 再发一次请求——写入的是新页、不是累积；前一页数据被覆盖丢弃，下拉实际只剩最后一页。

同域其余考后页面（`reviews` / `absences` / `makeups`）各自复制了 `PAGE_SIZE = 50` + 单页取数，与候选 2 同源但未被台账单列。本卡将五页收敛到同一份取数策略。

## 改动内容（全部在 `frontend/src`）

1. **新增 `hooks/useTeacherExams.ts`（收口点）**：
   - 纯拉取核心 `fetchAllTeacherExams(fetchPage, options?)`：从 `page = 1` 逐页累加；返回空或长度 < `pageSize` 即视为到底并提前 `break`；`maxPages` 兼作分页信封缺 `total` 时的防死循环上限；取数抛错 / 返回 `undefined` 时安全兜底——保留已累积页、不抛出。
   - 默认参数 `pageSize = 100`（后端 `size` 上限）、`maxPages = 5`（单人至多 500 场考试）。
   - Vue Query 封装 `createTeacherExamsQueryOptions(fetchPage, options?)`：`queryKey = ['exams', 'teacher-dropdown']`（**首元素固定 `'exams'`**，保证既有页面/测试缓存口径向后兼容）、`queryFn` 走 `fetchAllTeacherExams`、`staleTime = 30_000`（跨页签 / 跨功能复用同一份缓存）。
2. **改造五个消费端页面**：`scores` / `grading` / `reviews` / `absences` / `makeups` 一律移除各自的 `const PAGE_SIZE = 50` 与死变量 `examPage = ref(1)`，改调 `createTeacherExamsQueryOptions`；其中 `grading` **彻底删除导致第 1 页丢失的 `watch(exams, ...)`**。各页 label 格式（如 `grading` 的状态后缀）保持不变。
3. **新增 `hooks/__tests__/useTeacherExams.spec.ts`**：纯逻辑边界用例 + 页面接线词法护栏。

## 不变式（全部不得动）

1. 后端契约不变：仍调 `GET /api/exams?page=&size=`（gen:api `page2` + `unwrap`），`src/`、`pom.xml` 零触碰。
2. 各页 `examOptions` 的 label 形状、过滤函数 `examFilterOption`、选中态联动（`selectedExamId` / `selectedExam` / 各页 watch）均不动。
3. 各页其余查询（主观题进度、学生行、复核列表、缺考名单、补考候选/最终成绩）与提交动作、提示口径零改动。
4. `staleTime`、`pageSize`、`maxPages` 为新增策略常量；本卡不引入服务端搜索式选择器（超范围，另行立项）。

## 验收与停止条件

**门禁**（两道，全绿才算过）：

- 前端：`npm.cmd run lint:check`、`npm.cmd run type-check:check`、`npm.cmd run test` 三项退出码 0；vitest 用例计数**恰增新用例数、不减**（开工先记基线）。
- 后端：仓库根 `.\mvnw.cmd clean test` → 四计数 **350/0/0/1** 不变 + BUILD SUCCESS（本卡零后端改动，预期不变）。

**提交**（严格两笔）：

- **实施笔**：仅 `frontend/src`（新增 hook + 新增 spec + 五页改造）。
  信息：`feat(frontend): unify teacher exam dropdown pagination and fix grading watch bug`
- **收口笔**：delta 合入 `spec/specs/frontend/spec.md` + 变更目录归档到 `spec/changes/archive/unify-teacher-exam-select-pagination/` + `spec/README.md` 归档行。
  信息：`docs(spec): archive unify-teacher-exam-select-pagination`
  零源码改动凭据：`git diff --name-only HEAD~1 HEAD -- src frontend pom.xml` 输出为空。

**停止条件**（触发即停、原样回传）：

1. 前端或后端门禁基线红（基线修复不属本卡）。
2. 词法护栏与单测无法同时成立。
3. 实施中发现必须动「不变式」任一条（如需改后端契约）。
4. 验证需要 dev server / 构建 / 真实环境（本卡纯静态 + 单测可证）。
