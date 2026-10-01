# 提案：拆分题目统计导出的分页取数与逐题解析归因

> 状态：**已归档（归因方法有效；找到稳定主导因素，仅作下一份独立提案候选）**。仅补归因证据；本提案不实施导出优化，也不把隔离测量外推生产。
> 收口（2026-09-27）：测量工具提交 `94ca842c20a771246a3cbf7c90c13906e01c6a5d`（被测业务基线仍为 `8085c57`）。三次独立 JVM 调用各 3 轮、每形状 9 样本：`resolveQuietly` 稳定主导分页取数（全局 min 解析份额 0.510 > 全局 max 分页份额 0.490），27/27 个 normal 桶样本中答案解析段为最大段（份额 0.381–0.656）。`src/main`/Mapper SQL/schema/JVM/线程池/导出行为零触碰；稳定主导因素只作下一份独立提案候选，未实施优化。仓库根 `mvnw.cmd clean test` @ `94ca842` → 314/0/0/1 BUILD SUCCESS 退出码 0；逐轮原始 JSON/控制台与 sha256 见 `evidence/`（`measurement-rounds.md` / `evidence-sha256.txt`）。

## Why

已归档的 `update-question-stats-export-memory` 在隔离 H2 + 进程内直调中判定：`pairs` 并非主要可控分配因素，故对其改写 NO-GO。其最大相位 `pagingResolve` 同时包含答卷分页 `selectList` 和 `resolveQuietly`（其中又有主观分批查、答案解析与评分）。该相位的绝对分配量不能证明 SQL、答案解析或评分分别占主导；后续也不能据此直接做 SQL 投影、解析缓存或 JVM 调参。既有 JSON 的 `revision=8085c57` 指被测业务基线，测量工具提交为 `91bf892`；其归档口径已在 `01be429` 校正。

## What Changes

1. 在现有 `QuestionStatsExportAttributionMeasureIT` 基础上，仅扩展隔离测量：同一页、同一次调用分别围住分页查询、`resolveQuietly` 与汇总循环；记录每段墙钟、当前线程分配、SQL 次数/目标语句与返回行数。若解析段为主导，再用 test-only 定点探针或受控测量副本区分 `resolveBatch` 内主观分取数与答案解析/逐题评分；测量方式、额外开销与嵌套口径必须写清，不用不同轮次的 P50 相减冒充计算耗时。
2. 沿用归档的可复现负载形状（含不同题数及必要的坏卷降级边界），三轮或更多独立运行；记录预热、JDK/堆、样本、输出 XLSX 语义 oracle、SQL 数和 JSON SHA-256。每轮保持生产入口与副本/探针的关联，报告同窗口分母；无可比窗口只并列绝对值。坏卷降级单独分桶，不用异常页污染正常页归因。
3. 给出有界裁决：可重复地观察到分页取数、主观分读取、解析/评分中某一因素主导且高于轮间噪声，才把该因素作为**下一份独立优化提案的候选**；否则记「归因未定」，停止，不试改多个因素。无论哪种结果，本变更都不改 `src/main`、Mapper SQL、schema、JVM/线程池或导出语义。
4. 在已提交测量工具的 revision 上执行仓库根 `mvnw.cmd clean test`；保留原始命令、退出码、四计数和日志。原始 JSON 须在 `clean` 前转存，落盘后逐一核 SHA-256；真实验证不把 IT 的跳过当通过。按实际结论合入或保留 delta 草案、更新 `spec/README.md` 并归档，未完成步骤不得假勾。

## Impact

- 规范：仅拟增加 `spec/specs/performance/spec.md` 的题目统计归因判据；若测量无效，不合入草案。
- 代码：仅 `src/test/java/com/exam/score/measure/QuestionStatsExportAttributionMeasureIT.java` 或同目录必要的 test-only 辅助；`src/main`、`pom.xml`、数据库和 API 均不改。
- 环境：隔离 H2、进程内服务测量；不启 Docker，不写共享 dev，不跑真实 MySQL/Tomcat。已有未跟踪的 `docs/backend-optimization-candidates.md` 不修改、不暂存。
- 不影响交卷双宿主 `isolate-submit-load-generator` 的待验证状态，也不将交卷 P99 与导出相混。

## 停止条件与反例

- 新相位无法与既有导出数据/语义对齐、观测开销淹没差异、SQL 返回行数不可核或三轮结果反复逆转：记录原始结果，判归因未定，不做业务优化。
- 即便解析内的 SQL 墙钟很小，不能仅以 `resolveQuietly` 墙钟残差声称全是 CPU；GC、调度、JIT 和探针开销仍要作为反例核对。
- 局部 H2 数据可能放大应用分配、缩小真实网络/数据库耗时；结论只适用于本隔离负载，不宣称生产瓶颈或收益。
