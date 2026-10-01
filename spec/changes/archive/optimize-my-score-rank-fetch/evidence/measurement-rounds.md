# measurement-rounds：optimize-my-score-rank-fetch 逐轮数值与红绿转录

> 控制台原件未入库（红色阶段/绿色阶段/门禁日志存于仓库外 `D:\code\examOnline-measure\`：`red-e.log`、`red-e7.log`、`green-e.log`、`gate-39d64d0.log`）；机器可读逐轮 JSON 见本目录（sha256 见 `evidence-sha256.txt`）。本文只转录，不当数字的第二副本使用——数值以 JSON 原文为准。

## 命令与 revision 绑定

- 基线（旧实现，隔离工作树 `D:\code\examOnline-myscore-old` detach 于 `3a5c532`——该提交 = 三件套 + 测量工具扩展，`src/main` 为旧「取回全班」实现）：仓库根=该工作树，
  `./mvnw test -Dtest=RankAttributionMeasureIT -DfailIfNoTests=false -Dmeasure.rev=3a5c532 -Dmeasure.label=old-run{1,2,3} -Dmeasure.out=D:\code\examOnline-measure\old-run -Dmaven.repo.local=D:\code\examOnline\.m2-repo`，三轮均 `Tests run: 1, Failures: 0, Errors: 0`、BUILD SUCCESS。
- 复测（新实现，主树提交 `39d64d0`，与基线同数据生成/同负载/同预热/同工具/同 JDK21 同机）：
  `./mvnw test -Dtest=RankAttributionMeasureIT -DfailIfNoTests=false -Dmeasure.rev=39d64d0 -Dmeasure.label=new-run{1,2,3} -Dmeasure.out=D:\code\examOnline-measure\new-run -Dmaven.repo.local=D:\code\examOnline\.m2-repo`，三轮均 `Tests run: 1, Failures: 0, Errors: 0`、BUILD SUCCESS。
- 事件插曲（如实登记）：首轮基线曾写入主树 `target/measure/`，其后该目录在仓库内被不明原因清空（pom 无 clean 绑定，成因未定），已按 update-score-ranking-calculation 先例改用隔离工作树重跑并以本目录 JSON 为准；丢失的中间产物不作证据。

## 阶段 2 基线（old-run1/2/3，serviceDirect=进程内直调 Service，业务段分账）

fetchMap = GradingSubmissionMapper 全班 selectList（取数+MyBatis 逐行反射映射）；rank = 请求内 RankCalculator 分账；otherSql = 其余语句；rest = 墙钟−fetchMap−rank（含 otherSql 与未归属段）。

| 轮 | n | fetchMap ms(份额%) | rank ms(份额%) | otherSql ms | rest ms | 答卷行级取数/请求 | p99 ms | 堆峰值 MB |
|---|---|---|---|---|---|---|---|---|
| run1 | 50 | 0.425 (51.708) | 0.011 (1.291) | 0.226 | 0.387 | 51 | 1.499 | 143.390 |
| run1 | 200 | 0.740 (59.530) | 0.042 (3.420) | 0.274 | 0.460 | 201 | 1.904 | 183.390 |
| run1 | 1000 | 2.901 (78.414) | 0.210 (5.672) | 0.334 | 0.589 | 1001 | 8.267 | 225.390 |
| run1 | 3000 | 8.394 (82.941) | 0.649 (6.409) | 0.583 | 1.078 | 3001 | 16.352 | 234.555 |
| run2 | 50 | 0.381 (52.036) | 0.010 (1.358) | 0.199 | 0.341 | 51 | 1.729 | 129.778 |
| run2 | 200 | 0.628 (60.094) | 0.037 (3.572) | 0.223 | 0.379 | 201 | 2.916 | 179.778 |
| run2 | 1000 | 3.053 (76.383) | 0.243 (6.074) | 0.394 | 0.701 | 1001 | 8.442 | 243.778 |
| run2 | 3000 | 8.749 (84.248) | 0.646 (6.218) | 0.522 | 0.990 | 3001 | 16.202 | 239.923 |
| run3 | 50 | 0.298 (51.992) | 0.005 (0.910) | 0.167 | 0.270 | 51 | 1.276 | 130.649 |
| run3 | 200 | 0.636 (62.312) | 0.031 (3.050) | 0.207 | 0.354 | 201 | 1.861 | 174.649 |
| run3 | 1000 | 2.157 (74.223) | 0.241 (8.280) | 0.277 | 0.508 | 1001 | 5.177 | 242.649 |
| run3 | 3000 | 6.769 (83.497) | 0.566 (6.983) | 0.400 | 0.772 | 3001 | 17.601 | 247.598 |

MockMvc 端到端臂（同口径对照，与归档基线可比）：n=3000 并发1 mean 19.866/14.129/14.596 ms、fetchShare 50.789/46.570/48.256%、rankShare 3.403/4.101/3.742%（归档 4.176–4.840% 同量级）；并发8 fetchShare 92.651/92.220/92.648%。每请求 SQL 条数恒 4.0（exam selectById + 本人行 selectOne + 全班 selectList + 复核 selectCount）。

### 停止条件判定

- **S1（取数+映射非占比最高）未命中**：三轮×四规模 fetchMap 全部最高（份额 51.7–84.2%；次高为 rest 未归属段（=restMeanMs/meanMs），n=3000 时三轮 10.651/9.533/9.523%，复算自同目录 JSON）。
- **S2（占比未稳定高于轮间波动）未命中**：n=3000 fetchMap 份额三轮 82.941/84.248/83.497%，轮间极差 1.307 个百分点（=84.248−82.941），领先次高段 72.3/74.7/74.0 个百分点（次高段=rankSharePct、otherSqlMeanMs/meanMs、restMeanMs/meanMs 三者最大，三轮均为 rest 份额）；n≥200 每轮领先 ≥22 个百分点（逐轮最小 22.5）。三轮值与份额均可机械复算自同目录 rank-attribution-old-run{1,2,3}.json 的 e2e[arm=serviceDirect].fetchMapSharePct。
- **S3（探针开销不可忽略）未命中**：分账探针 145.039/115.895/111.890 ns/语句（单列微基准），每请求 4 条语句 ≈ 0.45–0.58 μs，占 serviceDirect n=3000 请求均值 0.005% 量级；探针开销未并入任何生产段。

## 阶段 3 红绿（等价性测试 E1–E8，`MyScoreRankEquivalenceTest`）

旧实现（3a5c532 工作树，测试先行）红灯转录（`mvnw test -Dtest=MyScoreRankEquivalenceTest`，退出码 1）：

```
[ERROR] Tests run: 9, Failures: 2, Errors: 0, Skipped: 0 -- in com.exam.score.service.MyScoreRankEquivalenceTest
[ERROR]   ...e6AggregateRankMatchesReferenceOraclePerStudent:338 myScore 路径存在成批取回答卷行的查询（单次语句最多返回 3000 行） ==> expected: <true> but was: <false>
[ERROR]   ...e7SubmissionRowsFetchedByMyScoreDoNotGrowWithClassSize:350 小班 myScore 对答卷表的行级取数应恒为常数，实测=201 ==> expected: <true> but was: <false>
```

（E6 语义对拍半段在旧实现全过、红灯只来自取数形态结构断言；E7 在 200 人班即红——实测 201 行、护栏上限 10，1000 人班未在该红灯轮内执行（JUnit 在前置断言失败即止），其 1001 行由同目录 old-run JSON 的 `submissionsRowsPerRequest=1001`（同 revision 同实现）佐证。其余 7 用例（E1–E5/E8a/E8b）旧实现即绿——它们断言的是须保持的语义。）

新实现（39d64d0）绿灯转录（`mvnw test "-Dtest=MyScoreRankEquivalenceTest,ScoreServiceTest,ScoreServiceReviewHideTest"`，退出码 0）：

```
[INFO] Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- in com.exam.score.service.MyScoreRankEquivalenceTest
[INFO] Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- in com.exam.score.service.ScoreServiceReviewHideTest
[INFO] Tests run: 40, Failures: 0, Errors: 0, Skipped: 0 -- in com.exam.score.service.ScoreServiceTest
[INFO] Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
```

## 阶段 4 复测（new-run1/2/3，与基线同口径；比值=旧/新）

serviceDirect 请求级（mean 比值逐轮，不取单次最好值）：

| n | 旧 mean ms（三轮） | 新 mean ms（三轮） | mean 比值 | 旧 p99（三轮） | 新 p99（三轮） | p99 比值 |
|---|---|---|---|---|---|---|
| 50 | 0.823/0.732/0.573 | 0.727/0.886/0.577 | 1.13×/0.83×/0.99× | 1.499/1.729/1.276 | 1.185/1.713/1.326 | 1.26×/1.01×/0.96× |
| 200 | 1.242/1.044/1.021 | 0.870/0.666/0.637 | 1.43×/1.57×/1.60× | 1.904/2.916/1.861 | 2.242/1.229/1.346 | 0.85×/2.37×/1.38× |
| 1000 | 3.700/3.997/2.906 | 0.547/0.526/0.502 | 6.76×/7.60×/5.79× | 8.267/8.442/5.177 | 1.086/1.325/1.172 | 7.61×/6.37×/4.42× |
| 3000 | 10.121/10.385/8.107 | 0.676/0.430/0.525 | 14.97×/24.15×/15.44× | 16.352/16.202/17.601 | 4.814/0.857/1.492 | 3.40×/18.91×/11.80× |

- 段级：n=3000 fetchMap 段 8.394/8.749/6.769 → 0.143/0.100/0.121 ms；rank 段 0.649/0.646/0.566 → 0（myScore 不再调用 RankCalculator；`RankCalculatorTest` 护栏与预览/导出路径不受影响）。
- 取数形态：答卷表行级取数 51/201/1001/3001 → **2**（本人行 selectOne 1 行 + 聚合 COUNT 标量 1 行），不随班级人数增长；每请求 SQL 语句条数保持 4.0（逐语句明细见 JSON `perStatement`：新实现语句为 selectCount + selectOne(内部 selectList, 1 行) + 复核 selectCount + exam selectById）。
- 堆峰值（n=3000 serviceDirect 臂）：234.555/239.923/247.598 → 160.903/162.248/157.979 MB（上界口径，含夹具）。
- MockMvc 端到端 n=3000 并发1 mean：19.866/14.129/14.596 → 8.471/6.937/8.830 ms（2.35×/2.04×/1.65×；被 MockMvc 约 4–5ms 固定底座稀释，如实分开报告）。
- 小规模（n=50）比值 1.13×/0.83×/0.99× 落在轮间波动内 = 该规模无可见收益（0.2ms 量级请求），如实报告，不叠加其他参数。
- 语义指纹：new-run 三轮 microbench ranksum=4488000、expectedRank={50=50, 200=200, 1000=997, 3000=2991}，与归档 update-score-ranking-calculation 一致；12 个 e2e 臂 errors 全 0。

## 门禁与自查（39d64d0，仓库根）

- `./mvnw clean test` → `Tests run: 323, Failures: 0, Errors: 0, Skipped: 1`、BUILD SUCCESS、退出码 0（Skipped 1 为契约导出开关既有设计；基线 314 + 本变更新增 9 用例）。
- AGENTS 自查：端口/口令/计数、README 连接事实、`@Sql` 注解（过滤后 0）、裸行号、flyway 0 命中、`src/main/resources/db` 不存在、实体↔schema 表级双向集合差为空、wrapper 四件套在位——全部绿。
