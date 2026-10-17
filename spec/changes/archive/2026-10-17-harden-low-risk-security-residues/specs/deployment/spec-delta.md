# deployment 规范变更

## ADDED Requirements

### Requirement: 生产环境 API 文档暴露面收敛

系统 SHALL 在生产 profile（prod）下默认关闭 Swagger UI 与 OpenAPI 契约接口（`/v3/api-docs`），避免未授权泄露管理端点与全站内部结构 schema，同时支持通过环境变量显式覆盖。

#### Scenario: 生产环境默认禁用 swagger-ui 与 api-docs (harden-low-risk-security-residues)

GIVEN 运行环境激活 prod profile
WHEN 外部访问 `/swagger-ui.html` 或 `/v3/api-docs`
THEN 系统返回 404 Not Found 或禁用状态
AND 配置文件中 `springdoc.api-docs.enabled` 缺省为 false，由词法护栏测试钉住
