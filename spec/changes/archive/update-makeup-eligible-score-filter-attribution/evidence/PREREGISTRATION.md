# 预登记（测量开始前冻结）：补考候选低分取数归因

> 本文件在**正式三轮测量之前**写定并记录 sha256；写定后不得修改。
> 规则来源是提案 `../proposal.md` 第 3 条的既有预登记（"仅当多轮同负载证据表明…才 GO"），本文件只做**可机械执行的算子化**，不新增、不放宽任何判据。
> 本文件只写判据与口径，不写任何测量数值（数值见 `measurement-rounds.md` 与 `rounds-analysis.json`）。

## 0. 披露：预登记之前发生的冒烟

在写定本文件之前，已用**工具 v1**（3 轮、无臂序轮转、无 prodFirst/prodLast 夹逼）跑过一次冒烟，其产物保留为
`smoke-toolv1-makeup-eligible-attribution-validate.json`（并在 `measurement-rounds.md` 中标注其为"不参与裁决的旧工具版本冒烟"）。
该冒烟影响了两处**设计选择**，在此明示以免被误认为事后择优：

1. 主族/敏感族的划分（见 §2）；
2. 增加臂序轮转与两侧生产夹逼（因为冒烟暴露出同轮先后顺序可造成 ±数十% 的漂移）。

该冒烟**不作为**任何结论的证据；其数值不得被引用为收益或噪声。

## 1. 有效性闸门（任一不成立 → 该轮/该次运行判无效，不得进入裁决计算）

- **G1 语义 oracle**：12 条语义用例全绿（`passLine=null` / 负值 / 60、60.00、60.000 三种 scale 同结果 / 严格小于边界 /
  总分 null 排除 / 缺考∪低分 / 同 ID 低分覆盖缺考 / 缺失姓名与软删姓名 / 空候选短路且**不发起姓名查询** /
  403 非归属 / 404 不存在 / 404 已软删 / ADMIN 放行）。任一不成立 → 工具抛错，该次运行无效。
- **G2 夹具 oracle**：6 个 combo 的候选规模与原因计数由夹具参数独立推算并与生产结果一致，且姓名非空。
- **G3 结果等价**：每轮四路 canonical（prodFirst、prodLast、OLD、PUSHDOWN、PROJECTION）按
  `(studentId|reason|name)` **排序集合**相等（不是顺序相等；现行查询无 ORDER BY，不制造排序契约）。
- **G4 受控臂形状**：SQL 捕获必须生效，且
  PUSHDOWN 的答卷 SQL **有且仅有**多出 `total_score < ?` 严格小于谓词；PROJECTION 的答卷 SQL **有且仅有**收窄列
  （保留 `answers` 列 → 假；去掉 `answers` 列 → 真）。任一不符 → 工具抛错，该轮无效。
- **G5 探针隔离**：请求级批量样本在 SQL 捕获关闭状态下采集；分相位探针是额外一遍完整路径，单列上报；
  `instrumented.*` 的耗时/分配**不并入**请求级均值。
- **G6 独立性**：三次运行是三次**独立 JVM**（各自 `mvnw.cmd clean? 否——独立 mvnw 调用、各自 Spring/MyBatis 新进程），
  每次运行内每 combo 每臂预热 2 次（不计入窗口），每轮 4 次重复全部原样上报（**不挑最好一轮**）。

## 2. 形状族（在正式测量前定稿）

- **主族（代表场景，非人为极端）**：`s200-p10-a2k`、`s1000-p10-a2k`、`s1000-p10-a20k`、`s3000-p10-a2k`
  ——低分占比 10%（"少数人不及格"的常见补考场景）、含最小规模（200 人）与长字段放大（20KB/份）。
- **敏感族（人为极端/反例形状）**：`s1000-p50-a2k`（一半人不及格）、`s1000-p90-a2k`（九成人不及格，下推几乎不省行）。
  敏感族的结论**只作边界披露**：主族成立 GO 时，收口注记必须写明"低分占比很高时不降推收益、甚至不可辨"这一形状边界。

## 3. 算子（逐轮、逐 combo 计算）

- `prodRef(r)` = `mean(prodFirst.meanMillis, prodLast.meanMillis)`（同轮两侧生产样本的中点，用于抵消顺序漂移）。
- `armRatio(r, arm)` = `prodRef(r) / armMean(r, arm)`；>1 表示该臂比同轮生产快。
- `noiseBand(combo)` = 该 combo 在三次运行全部轮次中 `armRatio(r, OLD)` 的 **[min, max]**。
  OLD 副本臂与生产逐句等价，因此这个区间就是"不改行为时同轮对照能漂到多大"的实测噪声下限。
- `orderDrift(combo)` = 全部轮次 `prodLastVsFirstRatio` 的 **[min, max]**（同一入口前后两次的比值，同样是不改行为的读数）。
- `probeOverhead(combo)` = 探针 wall 与请求级 mean 的并列原值（单列，不相减求占比）。

## 4. 裁决规则（与提案第 3 条一一对应）

**D1（对应 a：占比最高的可控因素）** 在主族每个 combo 上，旧路径探针的 5 相位中
`gradingSelect`（答卷取回）+ `absencePutAndJavaFilter`（Java 过滤）的**同窗内**耗时占比为最大一项；
且结构上可消除量（`gradingRowsEliminatedByPushdown × answersCharsMax`，由行数×字段长度直接可算）是主族各 combo 中
最大的单一可消除体积。敏感族不满足 D1 时如实记录（p90 预期由 nameLoad 主导），不据此翻主族结论。

**D2（对应 b：收益稳定超过轮间/探针波动）** 在主族每个 combo 上，
`min over 全部轮次/运行 of armRatio(r, PUSHDOWN) > max(noiseBand(combo))`
且 `min armRatio(r, PUSHDOWN) > max(orderDrift 上界对应的比值)`；
即下推的收益在**每一轮**都高于"等价工作量"能达到的最大漂移。

**D3（对应 c：行为等价）** G3 在全部轮次成立（硬护栏，不成立即运行时抛错）。

**D4（对应 d：收益不只是人为极端形状）** D2 在主族全部 4 个 combo 上成立，其中必须包含最小规模
`s200`（若只有大考试才有收益，则判不成立）与长字段放大 `a20k`。

**D5（NO-GO 的另一条触发：投影或其他因素主导）** 若在主族上 `armRatio(r, PROJECTION) > armRatio(r, PUSHDOWN)`
在全部轮次中成立的轮次占多数（> 半数），则判"投影主导"，按提案第 3 条 NO-GO（且不转手实施投影）。

**D6（可靠性）** G1、G2、G4、G5 全部成立，且三次运行都产出完整 JSON；任何一次运行缺轮、崩溃或需要放宽口径才成立，
即"探针/语义不可靠"→ NO-GO。

**结论算子**：

- 主族 GO ⇔ D1 ∧ D2 ∧ D3 ∧ D4 ∧ ¬D5 ∧ D6。
- 主族 GO 时，结论只能写"**隔离负载技术收益**"，并绑定：形状族（主族 4 个 combo）、
  口径（进程内直调 Service + H2）、以及敏感族边界（p50 收益减弱、p90 不可辨）。
- **不得**由本测量写生产请求频度、MySQL/Tomcat 或线上 P99 的任何数字或结论；频度记为未知。

## 5. 口径与边界（随结论一同回报）

- 分配量 = `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes` 的**单线程窗口差分**（精确字节）。
- `answers` 体量 = Java 侧对返回对象统计的字符数；夹具为 ASCII，1 字符 = 1 字节。
  **不是** MySQL 线上传输字节，**不是**网络/RTT 估计。
- 相位耗时是墙钟，含 JDBC 驱动 / H2 进程内执行 / MyBatis 映射 / 对象分配；跨相位求占比只在**同一探针窗口内**进行；
  探针与受控臂是额外一遍路径，不与请求级批量样本混算。
- 全部数据在隔离 H2 内存库（`mem:exam`，测试 profile，由仓库正式 `schema.sql` 建表），进程内直调 Service；
  未连接共享 dev、未启 Docker、未读未授权运行时日志、未采信前端调用点或 dev 演示记录作为频度证据。
- 已知未知：真实请求频度、真实考试人数分布、真实 `answers` 字段大小分布、真实 MySQL 传输与连接池行为、
  Tomcat 线程/GC 行为、真实索引统计。以上均为未知，不外推。
