# 分页 / 解析归因测量轮次记录（update-question-stats-paging-resolve-attribution）

- 测量工具提交 revision：`94ca842c20a771246a3cbf7c90c13906e01c6a5d`
- 运行宿主：Windows 11 amd64，20 处理器，JDK 21.0.9 (Oracle)，maxHeap 3952MB
- 隔离口径：`@SpringBootTest` + `test` profile（H2 `mem:exam MODE=MySQL`，进程内直调 Service），无 Docker、无共享 dev、无真实 MySQL/Tomcat
- 每轮内部 3 次重复（`loadParams.rounds=3`，预热 2 轮不计入），5 个数据形状（s1000-q20 / s1000-q150 / s3000-q60 / s100-q20-bad2 / s3-q20-bad1）

## 逐轮命令 / revision / 退出码 / 四计数

三轮为三次独立 JVM 调用，命令仅 label 不同（`-D` 参数须带引号，否则 PowerShell 会把 `-Dmeasure.rev` 拆坏）：

```
.\mvnw.cmd -o test "-Dtest=QuestionStatsExportAttributionMeasureIT" "-DfailIfNoTests=false" "-Dmeasure.rev=94ca842" "-Dmeasure.label=attr-runN" "-Dmeasure.out=target/measure"
```

| 轮 | label | 退出码 | Tests run | Failures | Errors | Skipped | BUILD | 测试耗时 |
|----|-------|--------|-----------|----------|--------|---------|-------|----------|
| 1 | attr-run1 | 0 | 1 | 0 | 0 | 0 | SUCCESS | 80.70 s |
| 2 | attr-run2 | 0 | 1 | 0 | 0 | 0 | SUCCESS | 79.21 s |
| 3 | attr-run3 | 0 | 1 | 0 | 0 | 0 | SUCCESS | 78.94 s |

原始 JSON：`question-stats-attribution-attr-run{1,2,3}.json`（内含 revision / label / JDK / 堆 / 逐形状逐轮相位与嵌套值）；SHA-256 见 `evidence-sha256.txt`。四计数与退出码取自各轮 Maven 控制台输出（见上表）。

## 裁决一：测量是否有效（有效）

3 次 JVM 调用 × 内部 3 轮 = 每形状 9 个样本（共 45 个）：

- `probe.unequalPages = 0`（45/45；受控副本逐页 `result.equals` 与生产等价 `resolveBatch` 一致；不等即抛错判本轮无效，未触发）
- 内容语义校验 45/45 通过（均为内容/语义等价，**不是**整个 XLSX 原始字节相等）：`cellsEqualProduction = true`（生产与副本 XLSX 用 DataFormatter 逐格文本相等）、`zipContentEqual = true`（排除 `docProps/core.xml` 后逐 zip 条目解压字节相等）、顶层 `oracle` 独立重算与生产单元格对照 `oracleMatch = true`（每轮 5 combo，15/15）
- 原始长度校验：`outputBytesEqual` 只比较两数组长度（`prod.length == replica.length`），不是内容比对；45 样本中 **39 true / 6 false**（逐份 JSON：run1 14/1、run2 11/4、run3 14/1），6 次 false 的长度差均为 1 字节（差值为 prod − replica；+1：run1 s1000-q20 r1、run2 s1000-q150 r1、run2 s3000-q60 r3、run3 s3000-q60 r1；−1：run2 s3000-q60 r1、run2 s100-q20-bad2 r3），具体成因本轮证据未确定，本记录不作推断
- 坏卷分桶正确：`s100-q20-bad2` / `s3-q20-bad1` 的 `fallbackPages = 1`，其余形状 `fallbackPages = 0`；`resolveFallbackExtraSql` 与既有归档口径一致（s100-q20-bad2 = 98，s3-q20-bad1 = 2）
- 副本内部未归属段（框架/映射/计时插桩残差）占**副本自身** resolve 总耗时约 0.28%–2.24%（27 个嵌套样本 min 0.2839%、max 2.2402%；分母为副本自身总耗时，不与生产 `phases.resolveTotal` 跨调用相除）

## 裁决二：是否存在稳定主导因素（存在）

**同一次调用内份额比较**（不跨轮、不跨窗口相减相除）：

| 形状 | pagingSelect 份额 min/med/max | resolveTotal 份额 min/med/max |
|------|------------------------------|-------------------------------|
| s1000-q20 | 0.388 / 0.431 / 0.490 | 0.510 / 0.569 / 0.612 |
| s1000-q150 | 0.135 / 0.167 / 0.203 | 0.797 / 0.833 / 0.865 |
| s3000-q60 | 0.262 / 0.287 / 0.324 | 0.676 / 0.713 / 0.738 |
| s100-q20-bad2 | 0.027 / 0.050 / 0.079 | 0.921 / 0.950 / 0.973 |
| s3-q20-bad1 | 0.318 / 0.341 / 0.442 | 0.558 / 0.659 / 0.682 |

→ 全部 9 样本、全部 5 形状中 `resolveTotal` 份额 > `pagingSelect` 份额（全局 min resolve 份额 0.510 > 全局 max paging 份额 0.490）。**隔离 H2 负载下解析段稳定高于分页取数**（结论仅限本隔离口径），故进入嵌套分账。

嵌套分账取自 normal 桶（坏卷页不并入正常页归因；两个坏卷形状整批降级、normal 桶为空，无嵌套值）：

| 形状 | subjectiveRead 份额 med | answerParse 份额 med | grade 份额 med | parse 为最大段样本数 |
|------|------------------------|----------------------|----------------|---------------------|
| s1000-q20 | 0.317 | 0.408 | 0.248 | 9/9 |
| s1000-q150 | 0.103 | 0.578 | 0.307 | 9/9 |
| s3000-q60 | 0.192 | 0.541 | 0.253 | 9/9 |

→ 27/27 个 normal 桶样本中 **`answerParse` 复合段**（`paperReader.parseAnswers` 循环 + 主观分映射构建；含循环内的映射构建开销，**不等同于纯解析 CPU**）为最大段，份额 0.381–0.656；主观分读取 0.073–0.370、评分 0.200–0.359。隔离 H2 负载下正常桶最大段 = 该复合解析段（结论仅限本隔离口径）。

## 探针开销与口径声明

- **探针成本（额外一遍 resolve）单列**：受控副本是额外一遍 resolve，其耗时记 `probe.replicaResolveMillis`，逐形状 min–max：s1000-q20 7.442–24.400ms、s1000-q150 42.069–56.788ms、s3000-q60 60.890–78.740ms、s100-q20-bad2 11.342–34.112ms、s3-q20-bad1 0.596–1.251ms。它是额外一次的整遍 resolve（该副本自身含三段与残差），**不是**未归属段，**不并入**生产 `phases.resolveTotal`；与生产 `resolveTotal` 属不同调用，**不能**跨调用作精确占比。
- **未归属段**：normal 桶嵌套副本三段之和与副本自身总耗时的差（`unaccountedMillis`，框架/映射/计时插桩残差）占**副本自身** resolve 总耗时约 0.28%–2.24%（分母为副本自身，非生产段）；不作纯 CPU、不作生产 DB 耗时。
- 墙钟含 GC/JIT/调度；H2 为进程内，隔离结果不外推真实 MySQL/Tomcat 或生产 P99。
- 主导因素仅作为下一份独立提案的候选，本变更不改 `src/main`、Mapper SQL、schema、JVM、线程池与导出行为。

---

## 返修记（2026-09-27）

本记录按归档三份 `question-stats-attribution-attr-run{1,2,3}.json` 逐份复算校正：`outputBytesEqual` 仅比较长度且 39 true / 6 false（6 次长度差 1 字节）、内容语义校验与原始长度校验分列裁决（不再对两类校验作笼统的同一结论）、未归属段区间与探针成本分列、`answerParse` 标注为复合段（含主观分映射构建）。原始 JSON、`evidence-sha256.txt`、测量工具与 `src/main` 均未改动。