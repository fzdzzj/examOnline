# spec-delta：add-question-import-export

涉及域：question-bank、frontend。

## 域：question-bank

### MODIFIED Requirement: 题目管理
新增 Scenario：
- Scenario: 题库导入模板下载 —— 教师（question:manage 权限）
  GET /api/questions/import-template 下载 xlsx 模板：固定列序（题型/题干/选项A–F/
  答案/标签/难度/图片URL）+ 五题型示例行 + 填写说明；模板产出与导入校验共用同一份
  列定义，不漂移。
- Scenario: 题库批量导入（容错与错误报告）—— POST /api/questions/import
  （multipart xlsx）逐行校验：错误行（坏题型/答案与题型不匹配/选项不足/未知标签名/
  图片URL 非 /images/ 前缀等）跳过并记 {行号, 原因}，正确行入库（含标签关联、难度、
  图片URL），逐行独立事务；响应 {imported, failed, errors} 即报告，不持久化；数据
  行数超上限（默认 1000，可配置）整批 400；无 question:manage 权限 403。
- Scenario: 题库导出与 round-trip —— GET /api/questions/export 全量导出未软删题目，
  列口径与导入模板逐字一致（导出→修改→再导入可用）；SXSSF 流式写出（复用既有
  ExcelSheetWriter，防 OOM）；响应为 xlsx 附件下载（对齐既有成绩导出 attachment
  形态）。

## 域：frontend

### MODIFIED Requirement: 题库管理界面
新增 Scenario：
- Scenario: 批量导入导出入口与结果呈现 —— 题库管理页工具区提供「导入 / 导出 /
  下载模板」：导入上传 xlsx 后展示结果弹层（成功数/失败数/行号+原因表，与后端响应
  逐字段一致）；导出与模板为文件下载，对齐既有成绩导出下载形态。

### 既有约束引用（不重复正文）
- 断言有效性纪律（decorativeAssertionGuard 词法护栏）适用于本变更全部新增测试。
- SDK 按既有代码生成方式接入，新端点自动携带。

## 契约

- openapi.yaml：新增 POST /api/questions/import、GET /api/questions/export、
  GET /api/questions/import-template 三端点（paths 79→82，只增不减）；导入请求为
  multipart/form-data（file: string/binary，对齐 add-question-image 修复终态的
  multipart 契约口径）；导出/模板响应为 xlsx 附件（契约描述形态对齐既有成绩导出
  端点现状）。
- gate-baseline.json：后端 surefire 与前端 vitest 用例数按实测同批上调，
  openapiPaths 82。
