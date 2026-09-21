# api-contract 规范

> 能力域：API 契约（阶段 18，W17）。
> 来源：`spec/changes/archive/add-backend-openapi` 合入（springdoc 暴露 OpenAPI 契约，000bbf0 + 47d42f8）；`spec/changes/archive/fix-contract-export-charset` 合入（导出编码无损，提案⑧，2026-09-21）。
> 实施注记：
> - 依赖 `springdoc-openapi-starter-webmvc-ui:2.8.13`（本地 `.m2-repo` 离线可解析），配置类 `com.exam.common.config.SpringDocConfig`。
> - 契约范围由 `springdoc.packages-to-scan: com.exam` + `paths-to-match: /api/**` 限定；**导出实测 65 paths、`openapi: 3.1.0`**，覆盖全部 14 个 Controller。
> - 鉴权语义：全局 `SecurityRequirement` 挂 `Authorization`（HTTP bearer / JWT）；5 个公开端点（`/api/auth/login`、`/register`、`/refresh`、`/password/reset-code`、`/password/reset`）由 `OpenApiCustomizer` 显式置 `security: []`，**清单集中在 `SpringDocConfig.PUBLIC_PATHS` 常量，必须与 `WebMvcConfig.addInterceptors` 的 `excludePathPatterns` 同步**；改白名单必须同步该常量并重新导出契约。`/actuator/**` 不在契约范围内（被 `paths-to-match` 排除）。
> - 导出是**显式动作**：`OpenApiContractTest.exportOpenApiContract()` 受 `@EnabledIfSystemProperty(named = "exportContract", matches = "true")` 控制，不随常规 `mvn test` 运行，避免 CI 产出脏工作区。常规运行下该方法计入 `Skipped`，**CI 基线因此为 `Skipped: 1`，属设计使然，不是被禁用的断言**。
> - 生产 profile 关闭 swagger-ui（`springdoc.swagger-ui.enabled: false`），`api-docs` 保持开启以便按需导出。
> - **契约文件 `openapi.yaml` 位于仓库根，是前端 `@hey-api/openapi-ts` 生成客户端的唯一来源**；后端接口变更后必须重新导出，否则前端类型漂移。
> - 导出**推荐路 A（离线测试内导出）**：`mvn -o test -Dtest=OpenApiContractTest#exportOpenApiContract -DexportContract=true`。编码缺陷修复后（fix-contract-export-charset，2026-09-21）路 A 产物与真 dev 实例直接拉取（`/v3/api-docs.yaml`，路 B）SHA256 比对字节级一致；路 B 保留为真环境校验手段。护栏断言（无 U+FFFD、中文描述完整、servers.url 带端口）随导出测试入库防回归。

## Requirements

### Requirement: OpenAPI 契约暴露

WHEN 前端或其他消费方需要与后端 API 集成,

系统 SHALL 暴露与代码实现同步生成的 OpenAPI 3 文档，且 SHALL 提供可入仓库的契约文件作为客户端生成的唯一来源，且导出路径 SHALL 编码无损。

#### Scenario: 文档覆盖全部端点

GIVEN 后端存在 14 个 Controller

WHEN 获取 /v3/api-docs

THEN 每个 Controller 的路径均出现在文档中

AND 新增端点后文档自动同步

#### Scenario: 鉴权语义标注

GIVEN 端点需要 Access Token

WHEN 消费方读取文档

THEN 能识别 Bearer 鉴权方案与免鉴权端点清单

AND 免鉴权端点在文档中显式声明为空 security 而非仅依赖运行时白名单

#### Scenario: 契约可导出复现

GIVEN 需要为前端生成类型化客户端

WHEN 按记录的命令导出契约

THEN 产出 openapi.yaml 且入仓库

AND 后端接口变更后可重新导出更新

#### Scenario: 契约冒烟护栏

GIVEN 文档端点可能因配置或依赖变化失效

WHEN 运行测试

THEN api-docs 可达性与路径规模下限由测试守住

AND 导出的契约文件非空且含 OpenAPI 版本声明

#### Scenario: 导出不污染常规测试运行

GIVEN 常规测试运行不应改写仓库文件

WHEN 执行全量测试

THEN 契约导出不发生

AND 仅在显式开启导出开关时才写入 openapi.yaml

#### Scenario: 导出编码无损

GIVEN 导出开关开启且文档含非 ASCII 字段

WHEN 执行测试内导出

THEN 产出文件按字节流写入，中文不乱码

AND servers.url 保持完整形态不退化

AND 与真 dev 实例直接拉取的契约语义一致
