# harden-actuator-exposure

## 一、背景与问题（安全专项首扫发现 2 卡，2026-10-17 立案）

`WebMvcConfig` 对 `/actuator/**` **整体**豁免认证 + `application.yml`（base，全 profile 生效）
`management.endpoint.health.show-details: always` + 暴露 `health,info,metrics,prometheus` 四端点——
未认证可读：①健康细节（DB/Redis/SMTP/Rabbit 组件名、连接状态、错误信息——内部拓扑探测面）；
②全量 metrics 明细（全部指标名与数值）；③info 端点。 prometheus 抓取与 compose healthcheck
（`/actuator/health/db`，docker-compose.prod.yml:101）依赖 `/actuator/**` 免认证——收敛必须保观测链路不断。

## 二、方案（「观测链路不断、暴露面最小」双约束收敛）

- **exposure 按档收敛（真正的暴露面控制）**：base/dev 保持 `health,info,metrics,prometheus`
  （开发便利 + 测试 profile 继承 base 供既有集成测试观测）；**prod 覆盖为 `health,prometheus`**——
  info 审视结论：Boot 3 默认零配置下 info 端点返回 `{}`（仓库无 management.info.* 配置、无
  build-info/git-info 注入），零信息价值即从 prod 暴露面剔除；metrics 的聚合出口是 prometheus
  （Grafana 面板与告警全部走 prometheus 抓取），JSON 形态明细 metrics 在 prod 不再暴露；
- **show-details prod 收敛**：prod `management.endpoint.health.show-details: never`——compose
  healthcheck 用 `curl -fsS` 只看 HTTP 状态码（UP=200/DOWN=503），不读细节；DB/Redis/SMTP 等
  组件名与连接状态不再未认证可读。dev 保持 always（本地排障便利）；
- **认证豁免白名单式收窄**：`/actuator/**` 整体豁免 → 逐端点白名单 `/actuator/health/**`、
  `/actuator/prometheus`、`/actuator/metrics/**`——health（含 prod healthcheck 的 /health/db
  分组）与 prometheus 是部署栈硬依赖；metrics 保留豁免供 dev/测试观测与既有集成测试
  （SubmitObservabilityIntegrationTest 未认证打 `/actuator/metrics/{name}` 做现场留证），
  **prod 已由 exposure 剔除该端点（未暴露=404），保留豁免不影响 prod 实际暴露面**。
  收窄后其余 actuator 端点（含未来新增）一律要求认证（默认拒绝）；
- **护栏**：词法护栏测试钉住 prod 收敛面与 WebMvcConfig 白名单（防回退）；
- 观测链路保全证明：prometheus.yml `metrics_path: /actuator/prometheus` 不变且豁免在；
  prod healthcheck `/actuator/health/db` 命中 `/actuator/health/**` 豁免且 show-details never
  不影响状态码语义（-f 依 200/503 判定）。

## 三、白名单

- `src/main/resources/application-prod.yml`（management 块：exposure 收敛 + show-details never）
- `src/main/java/com/exam/auth/security/WebMvcConfig.java`（豁免清单收窄 + javadoc）
- `src/test/java/com/exam/deployment/ActuatorExposureGuardTest.java`（新文件：护栏三断言）
- `.github/ci/gate-baseline.json`、`spec/changes/harden-actuator-exposure/**`、`spec/README.md`、`docs/功能待办清单.md`
- **禁触**：application.yml / application-dev.yml / application-test.yml（base/dev/test 档不动）、
  docker/observability/**（prometheus 抓取配置与告警零改动）、docker-compose*.yml、
  SubmitObservabilityIntegrationTest（既有集成测试零改动——豁免白名单为其保留 metrics）、
  schema.sql、docker/mysql、openapi.yaml、pom.xml、frontend/

## 四、验收要点

- 护栏三断言：prod exposure 恰为 `health,prometheus`；prod show-details 恰为 `never`；
  WebMvcConfig 豁免含三个白名单模式且不再含 `/actuator/**` 整体豁免
- 变异击红：WebMvcConfig 恢复 `/actuator/**` 整体豁免 → 护栏确定性失败 → 复原回绿留证；等价变异如实登记
- 观测链路回归：SubmitObservabilityIntegrationTest（/actuator/metrics + /actuator/prometheus）
  与 AlertAssetsTest 零回归
- 范围外零改动核对（base/dev/test yml + docker/observability + compose + schema.sql + openapi.yaml + pom + frontend）
- 门禁 `mvnw.cmd clean test` 全绿（仓库根、带 clean、禁 -o）；backendTests 基线在 674 上只增不减，收口笔同笔上调
- S 级理由：单配置档 + 单豁免清单收窄 + 词法护栏；无业务代码行为变化；观测链路保全论证落 proposal
