# 提案：考试列表服务端筛选（add-exam-list-filtering，U-3）

## Why

前端考试管理列表页面（`frontend/src/pages/(dashboard)/teacher/exams/index.page.vue`）此前在获取当前页考试（`GET /api/exams?page=1&size=10`）后，在客户端对单页记录进行标题与状态过滤（`filteredRows`），搜索框占位符显式注明「搜索考试标题（当前页）」。当教师名下考试超过一页时，非当前页考试无法被检索，存在明显体验缺口（台账 U-3）。

本变更通过在后端 `GET /api/exams` 端点支持 `title`（模糊匹配）与 `status`（精确匹配）参数，并在前端完成服务端请求参数绑定、防抖触发与切页重置，实现真正的考试列表服务端筛选。

## What Changes

1. **后端端点扩参**（`GET /api/exams`）：
   - `ExamController.page` 接收可选查询参数 `title`（String）与 `status`（Integer）；
   - `ExamService.page(page, size, title, status)` 动态构造查询条件：
     - 保留既有教师数据所有权隔离：非 ADMIN 强制限定 `createdBy = operator.id`；
     - `title` 过滤：非空且含有效字符（`StringUtils.hasText(title)`）时追加 `title LIKE %title.trim()%`；空白字符串（全空格或空串）视为不过滤；
     - `status` 过滤：非 null 时追加 `status = #{status}` 精确匹配；
     - 排序与分页：保留既有 `ORDER BY id DESC` 及 `size <= 100` 有界保护；
     - 响应体形状零变化：维持 `ApiResponse<List<ExamResponse>>`。
2. **契约导出**：
   - 经既有离线测试 `OpenApiContractTest#exportOpenApiContract` 导出最新 `openapi.yaml`（`servers.url` 维持 `http://localhost:8080`，不降级）。
3. **前端接线**：
   - 执行 `npm run gen:api` 基于最新契约重新生成 `frontend/src/api/axios/types.gen.ts` 等类型定义，不手写接口类型；
   - 考试列表页面（`index.page.vue`）将 `title` 与 `status` 纳入服务端请求参数与 `queryKey`；
   - 标题输入增加防抖处理（300ms），避免逐键请求后端；
   - 筛选条件（标题、状态）发生变化时自动重置至第 1 页；
   - 移除表格客户端过滤计算，表格直接绑定服务端返回的记录；
   - 搜索输入框 placeholder 移除「（当前页）」，变更为「搜索考试标题」。
4. **硬约束**：
   - `schema.sql`、`pom.xml`、`application*.yml` 零改动；
   - 教师所有权过滤与既有降序排序零变化；
   - 响应信封零变化。

## Impact

### 受影响的规范
- `spec/specs/exam-management/spec.md` — ADDED：考试列表服务端筛选与分页 Requirement；
- `spec/specs/frontend/spec.md` — ADDED：教师端考试列表服务端筛选联动 Requirement。

### 受影响的文件
- 后端：`src/main/java/com/exam/exam/controller/ExamController.java`、`src/main/java/com/exam/exam/service/ExamService.java`、`src/test/java/com/exam/exam/ExamIntegrationTest.java`；
- 契约：`openapi.yaml`；
- 前端：`frontend/src/api/axios/**`（由 gen:api 生成）、`frontend/src/pages/(dashboard)/teacher/exams/index.page.vue`、`frontend/src/pages/(dashboard)/teacher/exams/__tests__/examListFiltering.spec.ts`；
- 规范：`spec/` 目录。

## 验收判据

1. **先红后绿**：
   - 后端新增集成用例（title 命中/不命中/空格 blank/status 精确/组合/缺省全量）先红，后端实施后变绿；
   - 前端新增用例（带参请求/防抖/回第 1 页/移除当前页文案）先红，前端实施后变绿。
2. **双端门禁只增不减**：
   - 后端 `mvnw.cmd clean test` 通过（用例数只增不减，BUILD SUCCESS）；
   - 前端 `lint:check`、`type-check:check`、`test` 全部退出码 0，用例数只增不减。
3. **规范合入与归档**：
   - spec 两 Requirement 合入基线；README 三处登记；归档至 `spec/changes/archive/add-exam-list-filtering/` 并附 `evidence-sha256.txt`。
