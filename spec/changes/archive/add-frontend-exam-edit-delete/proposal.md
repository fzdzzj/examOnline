# 提案：考试编辑与删除入口（add-frontend-exam-edit-delete，前端台账 F-1+F-2）

## Why

教师端考试列表 `teacher/exams/index.page.vue` 操作列只有「详情 / 发布考试 / 强制结束」——考试创建后若填错标题、时间、班级或试卷，没有任何修改入口；填错的废卷考试也无法删除，只能在列表里永久残留（前端能力缺口 F-1 考试编辑、F-2 考试删除，登记于 `docs/frontend-improvement-candidates.md`）。后端契约早已就绪而前端零调用：`PUT /api/exams/{id}`（`ExamService.update`）与 `DELETE /api/exams/{id}`（软删，`ExamService.delete`）在 `openapi.yaml` 与生成 SDK（`update2`/`delete2`/`detail2`）中均已存在，属「接口缺口为零、纯前端接线」的收口。

## What Changes（关键裁决：按钮显隐只做乐观口径，最终裁决在后端）

1. **列表操作列新增「编辑」「删除」**（形态对齐既有 link 按钮组）：
   - 「编辑」显隐与「发布考试」按钮同源（未开始 + 未发布），点击跳 `create?examId=<id>`；
   - 「删除」乐观放宽为「仅未发布」即显示（含进行中/已结束的未发布考试）——后端
     `ExamService.assertEditable` 的最终裁决是「未发布且未开始」，非未开始的删除请求会被
     400 拒绝，失败 message 原文呈现，前端不拦截、不本地编造文案；
2. **`create.page.vue` 支持编辑模式**：route query 带 `examId` 时卡片标题「编辑考试」，经既有
   详情端点 `GET /api/exams/{id}`（`detail2`）回填全部表单初始值（含 `antiCheatConfig` 两开关），
   提交改调 `PUT /api/exams/{id}`（`update2`），成功后 `router.push('/teacher/exams')` +
   `message.success`；创建路径零改动；
3. **编辑/删除成功后失效列表查询**：`queryClient.invalidateQueries({ queryKey: ['exams'] })`
   （对齐发布/强制结束既有做法）；
4. **删除必须二次确认**：声明式确认弹窗（`v-model:open` Modal + warning Alert 明示不可逆），
   形态对齐「强制结束」弹窗；
5. **零后端改动**：`src/main`、`src/test`、`pom.xml`、`schema.sql`、`openapi.yaml` 均不动，
   零契约变更零 `gen:api`。

## Impact

### 受影响的规范
- `spec/specs/frontend/spec.md` — ADDED：考试编辑与删除入口 Requirement（操作列乐观门控 /
  编辑复用创建页并回填 / 删除知情确认 / 失败原文呈现不本地编造）。

### 受影响的文件
- `frontend/src/pages/(dashboard)/teacher/exams/index.page.vue`（操作列 + 删除确认弹窗 + confirmDelete）；
- `frontend/src/pages/(dashboard)/teacher/exams/create.page.vue`（编辑模式：标题 / 回填 / 提交分支）；
- 新增 1 个前端 spec（操作列门控、编辑回填与提交、删除确认与失败文案）；既有
  `examCreatePublishFlow.spec.ts` 的 vue-router mock 补 `useRoute`（测试基建适配，断言零改动）。

### 需要迁移
- 无（零表变更、零后端、零契约变更）。

## 边界与不做

- 不做行内编辑/抽屉编辑等新形态（复用创建页）；不做批量删除；不改发布 / 强制结束 / 详情
  既有行为与弹窗；删除不做本地二次校验拦截（乐观口径由后端裁决）；`published` 判定沿用
  既有 truthy 口径，不引入新类型；`ExamUpdateRequest` 的 `courseId` 等表单未承载字段不扩
  （创建页本就不含）。

## 验收判据

- 先红后绿：新用例实施前跑一次留红（行为断言），实施后转绿；
- 实施笔仅含 `frontend/src`；收口笔仅含 `spec/`（`git diff --name-only <实施笔> HEAD -- src frontend pom.xml openapi.yaml` 为空）；
- 前端三项退出码 0，vitest 相对基线（51 文件 414 例 @ `04d7b69` 当次实测）只增不减；
- 仓库根 `mvnw.cmd clean test` 与基线（368/0/0/1 @ `04d7b69` 当次实测）持平——本卡零后端改动；
- `git status` 终态除白名单未跟踪文件外干净。
