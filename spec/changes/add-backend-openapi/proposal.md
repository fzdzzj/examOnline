# 提案：后端暴露 OpenAPI 契约（阶段 18，前端系列前置）

## Why

前端将按五个方向立项（阶段 19–23），技术栈对齐 `D:\code\crm\font\crm-front`（已核实 package.json）：**@hey-api/openapi-ts 从 openapi.yaml 生成类型化 axios 客户端**，契约先行，避免手写 API 层与后端漂移。

**已核实（2026-09-17）**：

- `pom.xml` 无 springdoc/swagger 依赖（grep 零命中），后端当前无任何 OpenAPI 文档端点；
- 后端共 14 个 Controller，全部挂 `/api/**` 前缀；
- 鉴权拦截器只拦 `/api/**`（`WebMvcConfig.addInterceptors`），`/v3/api-docs`、`/swagger-ui` 不在拦截范围，**无需动认证白名单代码**；
- 全量基线 210 全绿（阶段 17 归档时独立重跑）。

**期望状态**：`GET /v3/api-docs` 输出覆盖全部 14 个 Controller 的 OpenAPI 3 文档；可导出 `openapi.yaml` 入仓库，作为前端 `gen:api` 的唯一契约来源。

## What Changes

1. `pom.xml`：新增 `springdoc-openapi-starter-webmvc-ui`（2.8.x，兼容 Spring Boot 3.5.5）；
2. 新增 `SpringDocConfig`（`com.exam.common` 或独立 config 包）：定义 BearerAuth security scheme（Authorization: Bearer 头），使生成客户端携带鉴权语义；公开端点（login/register/refresh/password）标注免鉴权；
3. 契约导出：仓库根新增 `openapi.yaml`（由 `/v3/api-docs` 导出，手工脚本或 curl 落盘），后续后端接口变更需同步再导出；
4. 新增冒烟测试：`/v3/api-docs` 可达且 paths 数量 ≥ 现有端点规模下限（防 controller 新增后契约静默缺失）。

## Impact

### 受影响的规范
- `spec/specs/api-contract/spec.md` — ADDED（新能力域）：OpenAPI 契约暴露。

### 受影响的文件
- `pom.xml`（+1 依赖）
- `src/main/java/com/exam/.../SpringDocConfig.java`（新增）
- `openapi.yaml`（新增，仓库根）
- `src/test/java/...`（新增 1 个冒烟测试类）

### 需要迁移
- [ ] 无数据库迁移
- [ ] 业务 API

## 时间线评估

小：约 0.5 天（含契约导出与回归）。

## 风险

- **springdoc 与 Boot 3.5.5 兼容**：选 2.8.x 已验证线；若启动失败立即停下回报，不硬凑；
- **Jackson `non_null` 影响 schema**：响应 DTO 缺失字段在 schema 中应为非 required，生成客户端类型会出现可选字段——前端用 `path()` 语义处理，不在本提案解决；
- **不改任何 controller 签名与 DTO**：契约如实描述现状，不为了「好看的 schema」改后端；
- **swagger-ui 暴露**：默认自带 UI，本机/内网使用无碍；若用户要求生产禁用，加 `springdoc.swagger-ui.enabled=false` 于 prod profile（属配置项，实施时问一次）；
- **测试基线只增不减**：210 → 211+，禁止放宽断言。
