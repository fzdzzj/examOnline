# 证据索引：harden-cross-cutting-edge-cases（横切边缘与运维盲区收尾治理）

> 门禁唯一命令：仓库根 `mvnw.cmd clean test`（带 clean，未用 `-o`）。本目录所有日志均为当次原样输出，
> 未经手工编辑；`06` 绑定产生它的 revision（实施笔 `61595c8`）。

## 01 先红说明

- L-01（删班级引用检查）：实施前 `ClassService.delete` 无检查直接软删，新用例在实施前或变异注入时为断言级红（期望 400 实际放行）；
- L-03（慢 SQL 告警）：实施前 `AlertAssetsTest` 缺少 `DatabaseSlowSqlDetected` 规则与 `exam_database_slow_sql_total` 指标断言；
- L-05（复核 CAS）：实施前 `ScoreReviewService.handle` 无条件 `updateById`，并发冲突测试为断言级红。
各能力点均通过严格的变异注入实测击红（见 §03）。

## 02 定向绿（实施后）

`02-targeted-green-impl.log` —— `-Dtest="ClassServiceTest,ClassManagementIntegrationTest,ScoreReviewServiceTest,SlowSqlInterceptorTest,AlertAssetsTest"`：
31/31 全绿（含班级管理真实 HTTP 链路集成回归与各单元测试）。

## 03 变异击红（行为级）

- `03-m1-red.log` —— 注释 `ClassService.delete` 考试引用检查：`ClassServiceTest#deleteBlockedWhenExamReferencesExist` 确定性断言级失败（`Expected BusinessException to be thrown, but nothing was thrown`），`BUILD FAILURE`；
- `03-m2-red.log` —— 移除 `ScoreReviewService.handle` 的 CAS 状态条件更新并改回无条件 `updateById`：`ScoreReviewServiceTest#handleRejectedWhenCasUpdateFailsZeroRows` 确定性断言级失败（`Expected BusinessException to be thrown, but nothing was thrown`），`BUILD FAILURE`；
- `03-m3-red.log` —— 注释 `SlowSqlInterceptor` 中 `businessMetrics.countSlowSql()` 打点：`SlowSqlInterceptorTest#warnsWhenOverThreshold` 确定性断言级失败（`Wanted but not invoked: businessMetrics.countSlowSql()`），`BUILD FAILURE`。

## 04 复原回绿

- `04-m1-green.log` —— 复原 `ClassService.java` 后目标用例 `Tests run: 1, Failures: 0`，`BUILD SUCCESS`；
- `04-m2-green.log` —— 复原 `ScoreReviewService.java` 后目标用例 `Tests run: 1, Failures: 0`，`BUILD SUCCESS`；
- `04-m3-green.log` —— 复原 `SlowSqlInterceptor.java` 后目标用例 `Tests run: 1, Failures: 0`，`BUILD SUCCESS`。
先红后绿闭环留证完成。

### 等价变异登记（如实登记，不强杀）

- `application.yml` 中补齐的 9 组 `@Value` 旋钮在生产配置中的默认值若改为与代码默认值完全相同的字面量常量：属于**等价变异**（运行时取值与兜底值完全一致），代码行为无差异；此项价值在于运维可见性与环境变量覆写入口，不靠强杀破坏配置。
- `SlowSqlInterceptor` 中 MDC 获取 `requestId` 为 null 时的格式化字面量：属于日志呈现细节，断言只验证不抛 NPE 且日志包含字面量，不影响打点与核心阻断逻辑。

## 05 范围外自查

`05-out-of-scope-diff.txt` —— `git diff main -- src/main/resources/schema.sql docker/mysql/migrations/ openapi.yaml pom.xml frontend/` **输出 0 字节**（零 DDL、零契约、零前端改动）。
修改文件严格限定在派工白名单范围内（`ClassService`、`ScoreReviewService`、`BusinessMetrics`、`SlowSqlInterceptor`、`MybatisPlusConfig`、`application*.yml`、`exam-online-alerts.yml`、`AlertAssetsTest`、相关单测、`docker/observability/`、`docker/mysql/backup/`）。

## 06 全量门禁

`06-full-gate-summary.log`：
`mvnw.cmd clean test` → `Tests run: 689, Failures: 0, Errors: 0, Skipped: 1` + `BUILD SUCCESS`。

- baseline 前后值：`.github/ci/gate-baseline.json` `backendTests` **683 → 689**（+6：`ClassServiceTest` 4 用例 + `ClassManagementIntegrationTest` 1 用例 + `ScoreReviewServiceTest` 1 用例），与门禁实数同笔上调；
- `frontendTests` 908 / `openapiPaths` 97 保持不变（零前端、零契约变更）。
