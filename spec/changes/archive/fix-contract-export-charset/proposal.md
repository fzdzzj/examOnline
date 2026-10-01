# 提案：契约导出编码修复（前后端优化组合·提案⑧，P3）

## Why

`OpenApiContractTest.exportOpenApiContract()` 用 `MockHttpServletResponse.getContentAsString()` 取正文，**未设 charset 时按 ISO-8859-1 解码**（遗留 #12）：路 A 导出的 `openapi.yaml` 中文全乱码（`info.description`、`securitySchemes.Authorization.description`），`servers.url` 从带端口形态退化为不带端口形态。该方法受 `exportContract` 开关控制、常规 CI 不触发，故未污染基线——但 `-DexportContract=true` 一旦执行就会损坏仓库契约文件（此前已发生过一次，靠 `git checkout` 回滚）。修法明确：改用 `getContentAsByteArray()`。修复前重新导出必须走路 B（真 dev 实例）。

属后端独立小修复，不塞进前端阶段 19–23 串行链。

## What Changes

1. `OpenApiContractTest.exportOpenApiContract()` 改用 `getContentAsByteArray()`，按字节写文件（UTF-8 无损）；
2. 修复后走路 A 重新导出 `openapi.yaml`，与路 B（真 dev 实例拉取 `/v3/api-docs.yaml`）产物比对：中文不乱码、`servers.url` 不退化、其余内容一致；
3. 加护栏断言：导出产物中的非 ASCII 字段不得出现乱码替换符、`servers.url` 保持完整形态——防同类回归；
4. 交接文档/相关提示词中「推荐路 A」的表述按遗留 #12 校正（修复前路 A 是损坏操作，不是推荐路径）。

## Impact

### 受影响的规范
- `spec/specs/api-contract/spec.md` — MODIFIED：OpenAPI 契约暴露（补导出编码无损场景）。

### 受影响的文件
- `src/test/java/com/exam/support/OpenApiContractTest.java`
- `openapi.yaml`（修复后重导出，diff 核对后入库）
- 交接文档/提示词中路 A 表述

### 需要迁移
- [ ] 数据库迁移
- [ ] 业务 API

## 时间线评估

小：约 0.5 天。

## 风险

- **修复完成前不得执行路 A 导出**（会损坏契约文件——遗留 #12 既定纪律，重导出走真 dev 实例的路 B）；
- 重导出后须 diff 核对：除编码与 `servers.url` 外不应有其他漂移；若 springdoc 版本行为导致意外差异，停下回报，不静默接受；
- 导出受开关控制属既有设计（CI 基线 `Skipped` 语义不变），本变更不改开关语义。
