# 归因裁决记录（测量有效性与主导因素分节）

> 机械复算：`analyze-count-attrib.cjs`（算子冻结于 `PREREGISTRATION.md`，sha256 `4bdc237b…11ed8`，写定于测量前）。
> 环境：一次性 mysql:8.0（8.0.46）容器 `myscore-count-attr`，tmpfs 数据目录，整库执行仓库 `schema.sql` exit=0，镜像 digest `mysql@sha256:7dcddc01f13b…ae2b`；用毕 `docker rm -f` 销毁。捕获 revision `84d25a2`（生产路径基线），门禁 revision `c97e420`（本变更唯一代码提交）。

## 一、测量是否有效（M 组——全成立）

- **M1 PASS**：MyBatis 侧捕获（`MyScoreCountCaptureMeasureIT`，StatementHandler.prepare 拦截器，非手写）。目标语句每次 myScore 调用恰好出现一次，原文与绑定参数逐形状记录：
  `SELECT COUNT( * ) AS total FROM exam_submissions      WHERE  (exam_id = ? AND status = ? AND total_score IS NOT NULL AND total_score > ?)`
  绑定值 `[examId=9300000{n}, status=3, X=40.0]`（X=本人总分=score(0)=BigDecimal.valueOf(400,1)，与种子行/返回值现场核对一致）；`?`→字面量机械替换后无残留 `?`；rank 语义自检 50/200/997/2991 全过、reviewing=false。
- **M2 PASS**：容器 `SHOW CREATE TABLE exam_submissions` 含全部 6 个既有索引、基线态无候选索引；逐形状行数精确（50/200/1000/3000）；基线臂 EXPLAIN（X=40.0，全形状）`key=uk_exam_student`、`type=ref`、`Extra=Using where`（**无 Using index ⇒ 基线确用回表**，归因对象成立）。
- **M3 PASS**：基线/候选臂各 5 轮×4 形状（另加辅助臂 X_mid 各 5 轮）、漂移复测 2 轮×4 形状，全部轮次 exit=0、ANALYZE 可解析、逐轮 ISO 时间戳与原始输出逐字落盘；无挑轮。

## 二、是否找到稳定主导因素（D 组——GO 不成立，NO-GO）

### 逐轮原始数值（主臂 X=40.0，EXPLAIN ANALYZE 根节点 actual time，ms）

| n | 基线 5 轮 | 候选 5 轮 | 漂移复测 2 轮 | speedup_low | 判定带 | 结论 |
|---|---|---|---|---|---|---|
| 50 | 0.0908 / 0.0783 / 0.0896 / 0.0919 / 0.0725 | 0.0335 / 0.0459 / 0.0512 / 0.0629 / 0.0353 | 0.0697 / 0.0723 | 1.153 | 1.319 | 波动吞没收益（非门禁形状，只记录） |
| 200 | 0.341 / 0.288 / 0.247 / 0.285 / 0.269 | 0.0766 / 0.0646 / 0.0660 / 0.0704 / 0.0664 | 0.206 / 0.167 | 3.224 | 2.042 | 达标（非门禁形状，只记录） |
| **1000（门禁）** | 0.950 / 0.930 / 0.940 / 1.050 / 1.290 | 0.927 / 0.964 / 0.837 / 0.957 / 0.933 | 0.965 / 1.520 | **0.965** | 1.634 | **无可见收益** |
| **3000（门禁）** | 2.920 / 2.750 / 2.950 / 2.780 / 2.640 | 2.750 / 2.600 / 2.900 / 2.650 / 2.180 | 2.650 / 2.550 | **0.910** | 1.157 | **无可见收益** |

### 计划层面（机制证据）

- 基线臂（全形状）：`ref` on `uk_exam_student`（exam_id 前缀，rows=n），逐行回表取 `total_score` 过滤，`Extra=Using where`（无 Using index）——回表真实存在，逐轮 ANALYZE 实际行数=n。
- 候选臂 X=40.0：**n=50/200 优化器切换到 `idx_submissions_score_rank`（range，key_len=13，`Using where; Using index`，无回表）；n=1000/3000 优化器留在 `uk_exam_student` ref+回表——计划与基线相同，故 speedup≈1。**
- **optimizer_trace（n=3000, X=40.0）直接给出原因**：ref on `uk_exam_student` rows=3000 cost=366（chosen=true）；range on `idx_submissions_score_rank` rows_to_scan=2990 **cost=600.171 → chosen=false**。覆盖 range 的模型代价被估得高于非覆盖 ref，候选索引在最贵的查询形状上按代价模型被理性拒绝——这不是统计信息缺失，是代价模型对「宽 range 覆盖扫描 vs 唯一前缀 ref」的定价结果。
- 辅助臂 X_mid（只记录不裁决）：优化器在全部形状采纳候选索引且收益显著——n=1000（X=61.9）6.69×、n=3000（X=51.9）2.91×、n=200（X=41.9）2.08×、n=50（X=49.4）1.70×。⇒ 收益**存在但被优化器选择性阻断**：只在「查询本来就近乎免费」的形状（小班、选择性 X）兑现，在最贵的形状（大班 + 最差 X）不兑现。
- FORCE INDEX 诊断（只记录不裁决，生产 wrapper 无法携带 hint）：n=3000 X=40.0 强制覆盖 1.05–1.21ms vs 无提示 2.70–2.83ms（潜力 2.23×）；n=1000 强制 0.223–0.340ms vs 无提示 0.985–1.470ms。⇒ 若要兑现该潜力，需要的是**改 SQL（hint/自写语句）这另一类候选**，须按「一次只改一类」另行归因立项，不属于本笔（纯加索引）候选。

### 判据复算（按 PREREGISTRATION 算子）

- **D1 = false（部分成立）**：候选臂 EXPLAIN 覆盖仅在 n=50/200 出现，门禁形状 n=1000/3000 未出现。
- **D2 = false（门禁形状）**：n=1000 speedup_low=0.965 ≤ 1、n=3000 speedup_low=0.910 ≤ 1 ⇒ 按措辞纪律写「**无可见收益**」（计划未变所致，非波动问题）；n=50 的 1.153 也未过其判定带 1.319（波动吞没），如实记录为「稳定性不达标」——不写「收益不存在」。
- **D3 = true**：两臂同形状同 X 的 COUNT 值逐轮完全一致（49/199/996/2990）；`MyScoreRankEquivalenceTest` E1–E8 在门禁全绿（见 tasks.json 阶段 5 证据）。
- **D4 = true**：写路径无回归——INSERT 中位比 1.036 / max 1.078；casSummarize 0.896 / 0.997；casSubmit 0.906 / 0.957（均 ≤1.30/2.00 界）。
- **裁决主式 GO ⇔ M1∧M2∧M3∧D1∧D2(1000)∧D2(3000)∧D3∧D4 = false ⇒ NO-GO，不实施，无独立实施提案。**

## 三、B3 副作用核对（同批）

- 写路径代价：见 D4（无回归；候选臂 casSummarize/casSubmit 反而略快，量级在会话噪声内，不作收益主张）。
- `schema.sql` 本笔零改动；`SchemaSqlMysqlCompatibilityTest` 在门禁全绿（回归确认）；真 MySQL 8.0.46 整库执行 `schema.sql` exit=0 是本次的活体兼容证据。
- `docker/mysql/migrations/` 零改动（`git status` 可复核）；因 NO-GO 无迁移脚本产生——「脚本提交≠生效」的责任说明将随任何未来实施提案强制携带，本笔不涉及。
- 临时容器已销毁（`docker ps -a` 零匹配）；共享 dev 全程未连接。

## 四、边界与未知

- 临时容器（tmpfs、单机、空并发）只证明隔离口径下的机制与倍数；生产 MySQL 参数、真实数据量/分布、真实并发与请求频度未观测，无获准来源，记为未知。不外推生产 P99。
- X 维度只覆盖了捕获参数 40.0（最差选择性）与两个辅助点；「优化器在哪个选择性阈值切换」未做系统扫描（超出本笔授权，且已足够裁决）。
- 造数行 `paper_json`/`answers` 为 NULL（同既有 IT 口径）；生产已批改行更宽，回表代价只高不低——该方向性说明不改变 NO-GO 结论（瓶颈在优化器选择，不在回表行宽）。
