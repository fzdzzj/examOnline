# 提案：查询失败三态分离——失败不再伪装成空态或「成绩待发布」（fix-frontend-query-failure-states，U-1）

## Why

前端 UX 边界台账 U-1（`docs/frontend-improvement-candidates.md`，2026-10-07 基于 `main@3b7783e` 现场核验）：
四处数据查询的 `error` 未消费，查询失败与业务空态/业务态渲染为同一画面——

- 学生成绩页 `student/scores/index.page.vue:172-189`（myScore）与 `:191-212`（补考最终成绩）两查询只解构
  `data`/`isFetching`；`utils/scoreVisibility.ts:46-49` 对 `undefined`（请求失败时 data 为 undefined）返回
  `{ kind: 'not-published' }`——网络失败/500 与「成绩待发布」渲染为同一张未发布卡片，学生无法区分「没分」和「坏了」；
- 教师复核页 `teacher/reviews/index.page.vue:131-140` 失败落空态文案「该考试暂无复核申请」；
- 缺考页 `teacher/absences/index.page.vue:121-130` 失败落「该考试没有缺考记录（或考试尚未结束）」——把系统故障
  解释成业务正常，还附带误导性括号解释；
- 补考页 `teacher/makeups/index.page.vue:235-251`（候选人查询）与 `:280-296`（最终成绩查询）error 均未消费，
  失败分别落「请先选择主考考试并点击查询」与提示文案——用户明明点了查询，失败却被引导「请先点查询」。

教师据此做成绩发布/补考决策、学生据此判断是否申诉，误读成本高。反例（仓库既有正确形态）：
`components/student/StudentExamList.vue:55-59` 注释自陈「空态与错误态分开……真相是系统坏了」，
页面侧 `student/exams/index.page.vue:51-57` 把 errorText 接进组件。

## What Changes（关键裁决：纯前端三态分离；「成绩待发布」判定只认 isNotPublishedError 不放宽）

1. **四处接线，形态抄 `StudentExamList.vue:55-59` 三态分离范式**（错误 Alert / 业务空态 / 数据三态互斥）：
   - `student/scores/index.page.vue`：myScore 与补考最终成绩两查询解构 `error`；非「成绩待发布」失败在数据卡片
     位置显性呈现错误 Alert（承载后端 message），不再落 not-published 卡片；`isNotPublishedError` 归一链路
     （queryFn catch → null → not-published）零改动；
   - `teacher/reviews/index.page.vue`：查询失败时表格隐藏、Alert 呈现后端 message；既有空态文案一字不改
     （成功且无数据时照常显示）；
   - `teacher/absences/index.page.vue`：同口径接线；
   - `teacher/makeups/index.page.vue`：候选人查询与最终成绩查询两处同口径接线。
2. **「成绩待发布」判定唯一入口仍是 `utils/scoreVisibility.ts` 的 `isNotPublishedError`**（后端 400 固定文案），
   本卡不放宽判别、不改该文件纯函数与类型；
3. **业务空态文案不改**：「该考试暂无复核申请」「该考试没有缺考记录（或考试尚未结束）」「请先选择主考考试并点击
   『查询补考候选人』」等一字不动；
4. **零后端改动**：`src/main`、`src/test`、`pom.xml`、`schema.sql`、`openapi.yaml` 均不动，零契约变更零 `gen:api`。

## Impact

### 受影响的规范
- `spec/specs/frontend/spec.md` — ADDED：查询失败与业务态分离 Requirement（学生成绩页失败显性呈现 /
  待发布仅认后端 400 文案 / 教师侧列表失败不伪装空态 / 补考最终成绩查询失败同口径）。

### 受影响的文件
- `frontend/src/pages/(dashboard)/student/scores/index.page.vue`；
- `frontend/src/pages/(dashboard)/teacher/reviews/index.page.vue`、`teacher/absences/index.page.vue`、
  `teacher/makeups/index.page.vue`；
- 新增 4 个前端 spec（学生成绩页含词法护栏：源码必须在调用 `mapMyScoreToView` 前判别 `error` 与
  `isNotPublishedError`）；既有两个成绩页 spec 的 vue-query mock 补 `error` ref（测试基建适配，断言零改动）。

### 需要迁移
- 无（零表变更、零后端、零契约变更）。

## 边界与不做

- 不做重试按钮/全局错误页/错误边界组件等增强（一次只改一类）；不改 `ScoreVisibilityCard` 组件与
  `scoreVisibility.ts`；不改加载态呈现；不改考试下拉查询（`createTeacherExamsQueryOptions`）的错误呈现
  （不在 U-1 登记范围）；教师成绩页 `teacher/scores` 不在台账 U-1 登记的四处之内，不顺手扩卡。

## 验收判据

- 先红后绿：新用例在实施前跑一次留红（行为断言 + 词法护栏），实施后转绿；
- 实施笔仅含 `frontend/src`；收口笔仅含 `spec/`（`git diff --name-only <实施笔> HEAD -- src frontend pom.xml openapi.yaml` 为空）；
- 前端三项退出码 0，vitest 相对基线（47 文件 403 例 @ `3b7783e` 当次实测）只增不减；
- 仓库根 `mvnw.cmd clean test` 与基线（368/0/0/1 @ `3b7783e` 当次实测）持平——本卡零后端改动；
- `git status` 终态除白名单未跟踪文件外干净。
