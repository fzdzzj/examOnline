# 答案 JSON 解析归因测量轮次记录（update-answer-json-parse-hotspot）

- 测量工具提交 revision：`e23393618612daf4166b064dc6e71f64d1046610`（`test(score): 题目统计导出测量工具拆分答案解析复合段`，仅 `src/test`，`src/main` 零改动）
- 运行宿主：Windows 11 amd64，20 处理器，JDK 21.0.9 (Oracle)，maxHeap 3952MB
- 隔离口径：`@SpringBootTest` + `test` profile（H2 `mem:exam MODE=MySQL`，进程内直调 Service），无 Docker、无共享 dev、无真实 MySQL/Tomcat
- 夹具与口径与上一份归档（`update-question-stats-paging-resolve-attribution`）逐项一致：`pageSize=500`、`sxssfWindow=100`、内部 `rounds=3`、预热 2 轮不计入、`objectiveRatio=0.8`、`objectiveScore=2`、`shortAnswerScore=5`，5 个数据形状（s1000-q20 / s1000-q150 / s3000-q60 / s100-q20-bad2 / s3-q20-bad1）
- 本次工具改动：只在 test-only 受控副本 `resolveBatchReplica` 的同一次调用内，把原 `answerParse` 复合段拆成互不重叠子段 `parseAnswersCall`（逐份 `paperReader.parseAnswers`）与 `subjectiveMapBuild`（逐份主观分分组成 Map），父段 `answerParse` 原样保留；新增硬护栏：子段之和超过父段即抛错判本轮证据无效（未触发）

## 逐轮命令 / revision / 退出码 / 四计数

三轮为三次独立 JVM 调用，命令仅 label 不同（`-D` 参数带引号，否则 PowerShell 会把 `-Dmeasure.rev` 拆坏）：

```
.\mvnw.cmd -o test "-Dtest=QuestionStatsExportAttributionMeasureIT" "-DfailIfNoTests=false" "-Dmeasure.rev=e233936" "-Dmeasure.label=split-runN" "-Dmeasure.out=target/measure"
```

| 轮 | label | 退出码 | Tests run | Failures | Errors | Skipped | BUILD | Maven 总耗时 |
|----|-------|--------|-----------|----------|--------|---------|-------|--------------|
| 1 | split-run1 | 0 | 1 | 0 | 0 | 0 | SUCCESS | 01:10 min |
| 2 | split-run2 | 0 | 1 | 0 | 0 | 0 | SUCCESS | 01:09 min |
| 3 | split-run3 | 0 | 1 | 0 | 0 | 0 | SUCCESS | 01:06 min |

原始 JSON：`question-stats-attribution-split-run{1,2,3}.json`（内含 revision / label / JDK / 堆 / 逐形状逐轮相位与嵌套值）；SHA-256 见 `evidence-sha256.txt`。退出码为当次运行观测；控制台原件未随归档保留，四计数与 BUILD 结论以各轮日志（`%TEMP%\measure-split-run{1,2,3}.log`）为准。

## 裁决一：测量是否有效（有效）

3 次 JVM 调用 × 内部 3 轮 = 每形状 9 个样本（共 45 个）：

- `probe.unequalPages = 0`（45/45；受控副本逐页 `result.equals` 与生产等价 `resolveQuietly` 一致；不等即抛错判本轮无效，未触发）
- 内容语义校验 45/45 通过（均为内容/语义等价，**不是**整个 XLSX 原始字节相等）：`cellsEqualProduction = true`（生产与副本 XLSX 用 DataFormatter 逐格文本相等）、`zipContentEqual = true`（排除 `docProps/core.xml` 后逐 zip 条目解压字节相等）、顶层 `oracle` 独立重算与生产单元格对照 `oracleMatch = true`（每轮 5 combo，15/15）
- 原始长度校验：`outputBytesEqual` 只比较两数组长度（`prod.length == replica.length`），**不是**内容或字节比对；45 样本中 **38 true / 7 false**（逐份 JSON：run1 12/3、run2 12/3、run3 14/1），7 次 false 的长度差均为 1 字节（prod − replica：+1 六次 run1 s100-q20-bad2#r1 / run1 s3-q20-bad1#r1 / run2 s1000-q20#r1 / run2 s3000-q60#r3 / run2 s100-q20-bad2#r3 / run3 s3-q20-bad1#r3；−1 一次 run1 s3000-q60#r1），成因本轮证据未确定，不作推断
- 坏卷分桶正确：`s100-q20-bad2` / `s3-q20-bad1` 每轮 `fallbackPages = 1`（3 轮合计 3），其余形状 `fallbackPages = 0`；`resolveFallbackExtraSql` 与既有归档口径一致（s100-q20-bad2 = 98 共 3 样本，s3-q20-bad1 = 2 共 3 样本，其余 9 样本为 0）
- 复合段拆分护栏（子段之和 ≤ 父段）全程未触发；拆分回加一致性残差（`pa + sm + loop − answerParse`）≤ 1e-3 ms
- 副本内部未归属段（框架/映射/计时插桩残差）0.052–0.569 ms；`loopOverhead`（循环/put 与逐次计时插桩残差）0–1.561 ms

## 裁决二：是否存在稳定主导因素（不存在 → NO-GO）

**同一次调用内份额比较**（不跨轮、不跨窗口相减相除）：

解析复合段内部拆分（27 个 normal 桶样本；两个坏卷形状整批降级、normal 桶为空，无嵌套值）：

| 形状 | parseAnswersCall 占复合段 | subjectiveMapBuild 占复合段 | pa/sm 比值 min |
|------|---------------------------|-----------------------------|----------------|
| s1000-q20 | 0.8454–0.8984 | 0.0374–0.0553 | 15.3 |
| s1000-q150 | 0.9472–0.9758 | 0.0051–0.0171 | 55.5 |
| s3000-q60 | 0.9294–0.9541 | 0.0124–0.0306 | 30.4 |

→ `parseAnswers` 调用相对主观分映射构建稳定主导（27/27 样本，墙钟 15.3 倍起、分配轴同样 27/27 最大）。**但这只是复合段内部拆分。**

作为副本 resolve 的并列嵌套段（{parseAnswers, subjectiveRead, grade}）比较：

| 指标 | 结果 |
|------|------|
| parseAnswers 为最大嵌套段的样本 | 24/27 |
| parseAnswers 非最大的样本 | 3/27（全部为 s1000-q20：run1 r1 2.941 < 主观分读取 3.876；run2 r2 1.862 < 2.009；run2 r3 1.892 < 3.122） |
| pa / 次大段 比值 | min 0.61 / med 1.44 / max 2.05 |
| 份额区间 | pa min 0.2896 < 主观分读取 max 0.4778（全局交替） |
| 最窄 5 个余量 | −1.230 / −0.935 / −0.147 / +0.032 / +0.203 ms（全部 s1000-q20，对比同轮内抖动 0.44–1.44 ms） |
| 逐形状余量 vs 同轮抖动 | s1000-q20 −1.230–+0.468 ms vs 抖动 1.26/1.44/0.44 ms（余量被噪声吞没）；s1000-q150 2.604–7.580 ms vs 抖动 4.91/2.69/3.18 ms（最小余量小于最大抖动）；s3000-q60 4.217–13.664 ms vs 抖动 4.18/5.78/2.03 ms（同类） |
| 分配轴 | parseAnswers 27/27 最大（1.25–1.69 倍），但与墙钟判据不一致 |

→ 预登记判据要求「`parseAnswers` **单独稳定主导**、差异高于轮间及探针噪声且可保持共享语义」。实测中 s1000-q20 存在主观分读取交替超越、余量落在同轮抖动内（不可辨），全局份额区间交叠（0.2896 < 0.4778），不满足「稳定主导且差异高于噪声」。**按判据裁决 NO-GO**：不进入阶段 3，`src/main` 保持零改动，`specs/performance/spec-delta.md` 原样保留为未采用草案。

## 探针开销与口径声明

- **探针成本（额外一遍 resolve 副本）单列**：`probe.replicaResolveMillis` 全样本 4.768–80.063 ms。它是额外一次整遍 resolve（自身含三段与残差），**不是**未归属段，**不并入**生产 `phases.resolveTotal`；与生产段属不同调用，**不能**跨调用作精确占比或相减。
- **未归属段**：0.052–0.569 ms，为框架/映射/计时插桩残差；不作纯 CPU、不作生产 DB 耗时。
- 墙钟含 GC/JIT/调度；H2 为进程内，隔离结果不外推真实 MySQL/Tomcat 或生产 P99。
- 本夹具无 `subjective_grades` 数据行（`objectiveRatio=0.8` 仅决定题型构成），主观分读取与映射构建在接近地板的量级上比较；该边界不改变「parseAnswers 未稳定主导」的裁决方向，但也不能据此推断有主观分数据时的生产形态。
- 本变更不改 `src/main` 任何文件、不改 Mapper SQL、schema、JVM、线程池与导出行为；归因结论仅作为下一份独立提案的候选输入。
