# 提案：题目统计导出答案 JSON 解析热点的条件优化

> 状态：待审批。`answerParse` 是隔离副本的**复合段**，并非已证实 `parseAnswers` 单独为瓶颈。本提案先拆分测量；只有 GO 才实施一类改动。

## Why

归档 `update-question-stats-paging-resolve-attribution` 在 H2 + 进程内 Service 的正常页测得 `answerParse` 复合段为嵌套最大段，但其中同时有 `GradingPaperReader.parseAnswers` 循环和主观分按题映射构建。该测量不能单独授权改 Jackson 解析、主观分映射、数据库或 JVM。`parseAnswers` 还被客观判分及单份成绩解析共用，若改变它，可能使 JSON 键、空值、损坏答案与判分隔离语义发生回归。原始 JSON 中 XLSX 长度校验为 39/45 true，逐格内容与排除 `docProps/core.xml` 的 ZIP 条目比较为 45/45 true；不得把长度误写为逐字节等价。

## What Changes

1. 在已有 test-only 测量工具的同一受控副本调用内，把复合 `answerParse` 进一步拆成 `paperReader.parseAnswers` 调用与主观分映射构建两段；逐页记录墙钟、线程分配、输入答卷/题数、返回行数、正常/降级页分桶、未归属与额外副本成本。测试副本必须与现行生产结果逐页 `result.equals`，导出按现有内容 oracle 对账。不能拿跨调用分位数相减当精确归因。
2. 旧实现同形状、同预热至少三次独立运行；仅当 `parseAnswers` **单独**持续高于主观分映射及其他嵌套段、改善空间超出轮间/探针噪声，且风险与目标范围可控，裁决 GO。若映射构建占主导、交替主导、差异不可辨或副本不等价，NO-GO，`src/main` 零改动；映射构建如值得优化须另立项。
3. GO 时只优化 `GradingPaperReader.parseAnswers` 的解析/转换一类因素，先用旧实现作语义 oracle 和确定性/可复现的资源护栏，再实施；不得同时改 `QuestionScoreResolver` 主观分映射、SQL、schema、缓存、JVM 或导出接口。所有调用者（客观判分、成绩导出/统计）须保持：顶层必须为 JSON 对象、数值/字符串形式的 questionId 键、null/普通值的映射、非法键或损坏 JSON 的异常处理、重复键的现行结果与坏卷降级跳过规则。无法验证旧语义或红线不成立即停，不为追求红灯放宽规则。
4. 同数据同环境复测单独解析段、完整 `resolveQuietly`、完整导出及 SQL/堆/分配/GC/临时盘、XLSX 内容 oracle；收益若落在波动内或影响语义，撤销实现并 NO-GO。先在已提交最终代码态跑仓库根 `mvnw.cmd clean test`（原始日志与 revision），再据实合入 delta 或保留未采用草案、登记/归档。

## Impact

- 规范：仅 GO 并验收后拟合入 `spec/specs/performance/spec.md` 中的“答案 JSON 解析条件优化”条款；NO-GO 不合入实施条款。
- 测试：`src/test/java/com/exam/score/measure/QuestionStatsExportAttributionMeasureIT.java` 与必要定向语义护栏；GO 才可能改 `src/main/java/com/exam/grading/support/GradingPaperReader.java`。该解析器由判分和成绩查询共享，护栏须覆盖所有调用者。
- 不改 schema、Mapper SQL、前端、OpenAPI、连接池、JVM/线程池；不启 Docker、不写共享 dev，不把 H2 结论外推真实 MySQL/Tomcat 或生产 P99。不触碰未跟踪的 `docs/backend-optimization-candidates.md` 和待真实双宿主复验的 `isolate-submit-load-generator`。

## 停止条件与反例

- 同一副本内 parseAnswers 并非单独最大，或工具开销/轮间噪声淹没差异；副本与生产语义不符；坏卷异常处理不能等价；负载没有值得减少的分配或端到端收益不可辨：NO-GO，不改生产解析器。
- 即使 H2 下解析占主导，也不代表真实数据库链路的占比；不因算法看似更快就跳过同负载复测。
