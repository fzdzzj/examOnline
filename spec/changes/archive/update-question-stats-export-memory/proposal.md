# 提案：先归因题目统计导出的内存与临时文件，再条件性收口

> 状态：**已归档（NO-GO）**。本提案经隔离同负载三轮实测后判定 **NO-GO**，**未实施**（`src/main` 零改动）；其拟实施条款（spec-delta）按**未采用草案**随本目录归档，**未合入** score-management 基线。原「仅在隔离同负载测得 `pairs` 是占比最高的可控内存因素且语义可等价时实施；不是已证实 OOM」这一实施前置条件**未成立**，故不实施。测量为隔离 H2 内存库 + 进程内直调 Service，**非真实 MySQL/Tomcat 生产性能，不外推生产 P99**。逐轮原始 JSON 的 `revision=8085c57` 是**被测的旧业务基线**（被测代码所处提交）；测量工具本身的提交为 `91bf892`（`QuestionStatsExportAttributionMeasureIT`，类名以 IT 结尾不进全量门禁）——两者不是同一提交，此说明仅为澄清，**不改机器原始证据及 sha256**。

## Why

`ScoreExportService.exportQuestionStats` 已通过答卷分页和 SXSSF 窗口控制部分内存，但每道题的 `pairs` 会保留所有可解析答卷的 `(总分, 该题得分)`，规模随学生数×题目数增长。源码的 `5000×30×16B` 只计两个 double，不含 `double[]` 对象、引用与列表容量；此外 `resolveQuietly` 的单页映射、SXSSF 临时文件和最终返回 `byte[]` 也可能更贵。代码能证明持有量增长，不能证明内存故障或占比最高。现有 `docs/backend-optimization-candidates.md` 是未跟踪静态台账，不是新测量。

## What Changes

1. 先审阅导出路径、既有 `GradingScoreIntegrationTest` 与 score-management 基线；在隔离测试数据上以相同分布/题数/学生规模多轮记录完整导出耗时、堆峰值与 GC 后占用、分配热点、SXSSF 临时文件峰值、最终 XLSX 字节数与数据库调用数。测量窗口区分造数、答卷分页/评分解析、`pairs` 积累及排序、工作簿写入和输出 `byte[]`；不将不同窗口或独立分位数相减当精确占比。
2. GO/NO-GO 先于实现：只有 `pairs` 持有或对象开销在同负载下是主要可控因素、改善空间高于轮间噪声且不会以不可接受的 SQL/CPU/临时盘代价交换，才 GO。若主要成本是解析页、SXSSF 或最终字节，或者无内存风险/收益不明，NO-GO，停止该实现，不顺手改其它因素。
3. GO 后只改题目统计导出的一类内存因素，先用旧实现作 oracle 加确定性红线，再实现保持题序、平均分/得分率/答对率、区分度“总分最高/最低各 floor(n×0.27)”及总分并列时稳定顺序、缺分/损坏答卷跳过、少样本口径、输出表头与单元格结果。方案须实测 DB 读取次数、CPU 与临时文件代价；不为省堆无界重复扫描。
4. 相同数据、JDK、堆上限、预热与轮次复测；若内存收益不可辨或语义/资源回归则回退 GO 实现并按 NO-GO 收口。仓库根唯一门禁 `mvnw.cmd clean test` 在已提交实现态执行，保留原始输出与 revision。仅在真实 GO 验收后合入 delta；否则 delta 作为未采用草案归档。

## Impact

- 规范：条件性 MODIFIED `spec/specs/score-management/spec.md`「成绩导出」，不更改其他导出或排名 Requirement。
- GO 代码：预计仅 `ScoreExportService.exportQuestionStats` 及必要定向测试/隔离测量工具；NO-GO 不改 `src/main`。
- 不改 schema、Mapper SQL、前端、OpenAPI、JVM/线程池/缓存参数；不写共享 dev、不清理数据卷、不启 Docker。测量输出放忽略目录，原始证据需在 `clean` 前另存，不混入业务提交。

## 验收边界与停止条件

- 区分静态复杂度、隔离测量与真实 MySQL/Tomcat；隔离 H2/MockMvc 或纯服务测量不外推生产 P99。堆峰值须说明测量窗口与堆上限，临时盘与输出 `byte[]` 分开记账。
- 测量不可复现、只得到整体导出数字、`pairs` 并非主要可控因素、旧语义 oracle 无法可靠建立或新方案退化任一成立，停止实施并如实 NO-GO。
- 不把现有未跟踪 `docs/backend-optimization-candidates.md` 纳入提交；`isolate-submit-load-generator` 仍待独立负载宿主，互不夹带。
