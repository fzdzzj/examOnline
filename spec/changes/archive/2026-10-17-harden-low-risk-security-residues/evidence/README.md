# 证据索引：harden-low-risk-security-residues（站内消息查询 limit 上界钳制 + prod 默认关闭 api-docs 契约暴露）

> 门禁唯一命令：仓库根 `mvnw.cmd clean test`（带 clean，未用 `-o`）。本目录所有日志均为当次原样输出，未经手工编辑；`06` 绑定产生它的 revision（实施笔 `a201d76`）。

## 01 变异 m1 击红（消息 limit 钳制移除）

- `01-m1-red.log` —— 变异 m1（移除 `MessageService.listMine` 中的 `safeLimit` 钳制，改回旧形态 `last(limit > 0 ? "LIMIT " + limit : "")`）：
  - `MessageServiceTest#listMineClampsOverLimitTo100` 确定性断言级击红：`超大 limit 必须被钳制在 100，防止慢查询与 OOM ==> expected: <true> but was: <false>`；
  - `MessageServiceTest#listMineClampsNonPositiveLimitTo1` 确定性断言级击红：`0 必须回退为 LIMIT 1，杜绝无界查询 ==> expected: <true> but was: <false>`；
  - `Tests run: 8, Failures: 2, Errors: 0, Skipped: 0`，`BUILD FAILURE`。

## 02 变异 m1 复原回绿

- `02-m1-green.log` —— 复原 `MessageService.java` 中 `safeLimit = Math.min(Math.max(limit, 1), 100)` 钳制逻辑后重新执行：
  - `MessageServiceTest` 8/8 全绿（`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`），`BUILD SUCCESS`；
  - 消息查询 limit 钳制验证完成。

## 03 变异 m2 击红（生产 api-docs 设为裸 true）

- `03-m2-red.log` —— 变异 m2（将 `application-prod.yml` 的 `springdoc.api-docs.enabled` 改回裸 `true`）：
  - `ProdApiDocsGuardTest#apiDocsIsDisabledByDefaultInProd` 确定性断言级击红：`prod 环境 api-docs.enabled 必须配置为 ${API_DOCS_ENABLED:false}，默认关闭且允许按需覆盖 ==> expected: <${API_DOCS_ENABLED:false}> but was: <true>`；
  - `Tests run: 2, Failures: 1, Errors: 0, Skipped: 0`，`BUILD FAILURE`。

## 04 变异 m2 复原回绿

- `04-m2-green.log` —— 复原 `application-prod.yml` 中的 `enabled: ${API_DOCS_ENABLED:false}` 后重新执行：
  - `ProdApiDocsGuardTest` 2/2 全绿（`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`），`BUILD SUCCESS`；
  - 生产契约暴露默认收敛与环境变量按需覆盖词法护栏留证完成。

### 等价变异登记（如实登记，不强杀）

- `MessageService.listMine` 的钳制计算：`Math.min(Math.max(limit, 1), 100)` 与 `limit < 1 ? 1 : (limit > 100 ? 100 : limit)` 在全量输入区间数学等价；
- `application-prod.yml` 中环境变量占位符的空格形态：`${API_DOCS_ENABLED:false}` 在 Spring Boot 属性解析器中无论带无空格均正确解析默认布尔值 false。

## 05 范围外自查

- `05-out-of-scope-diff.txt` —— `git diff main -- src/main/resources/schema.sql docker/mysql/migrations/ openapi.yaml pom.xml frontend/` **输出 0 字节**（零 DDL、零契约、零前端改动）。
- 代码修改严格限制在派工白名单范围内（`MessageService`、`application-prod.yml`、`MessageServiceTest`、`ProdApiDocsGuardTest`）。

## 06 全量门禁

- `06-full-gate-summary.log`：
  - 执行命令：`mvnw.cmd clean test`
  - 门禁结果：`Tests run: 702, Failures: 0, Errors: 0, Skipped: 1` + `BUILD SUCCESS`（总耗时 05:52 min）。
  - baseline 前后值：`.github/ci/gate-baseline.json` `backendTests` **692 → 702**（+10：`MessageServiceTest` +8 用例，`ProdApiDocsGuardTest` +2 用例），与门禁实数同笔上调；
  - `frontendTests` 908 / `openapiPaths` 97 保持不变（零前端、零契约变更）。
