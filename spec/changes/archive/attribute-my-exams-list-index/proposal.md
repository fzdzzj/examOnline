# 提案：学生考试列表（myExams）取数的索引归因——先归因、后裁决、仅 GO 才实施

> 状态：**已归档（GO）**（2026-09-30）。首跑开工停手（K8 Docker 不可用）已于续跑轮排除（Docker Desktop 启动后 K1–K8 全绿，Docker 29.6.2）。在真 MySQL 8.0 容器（一次性 mysql:8.0 镜像 7dcddc01f13b，tmpfs 数据目录、独立库 my_exams_index_measure、127.0.0.1:13320 端口、容器 95349f4c39f1）上完成双形状（n1=100,500/n2=400,000）7 臂读路径左轮转（5 轮）与 4 臂热写批轮转（5 轮）实测。机械裁决脚本（analyze-index-arms.cjs）按 PREREGISTRATION.md 冻结规则（sha256 1b3a5f0e…2ea9d）裁决：整卡 **GO**，分渠道采用 **submissions=A1 (`idx_submissions_student (student_id)`), candidates=未采纳**。A1 臂确定性扫描行数比缩减 560~2480 倍，耗时由 260~1440ms 降至 0.06~0.16ms，热写无写放大；C0 补考渠道最快轮耗时 0.11~0.338ms < 5ms 瓶颈门槛，依指导裁决未采纳 C1 且不否决整卡。阶段 4 实施提交 `02d933e`（schema.sql 新增 A1 索引与存量迁移脚本）；已提交状态全量门禁 329/0/0/1 BUILD SUCCESS。阶段 5 归档收口并随收口提交捕获 IT `MyExamsSqlCaptureIT.java`；F-D 常量提取独立第三笔提交。

## Why

`ExamTakingService.myExams()`（src/main/java/com/exam/taking/service/ExamTakingService.java，
grep 锚点 `public List<ExamListItem> myExams`）在 `fix-my-exams-list-scope`（4c007c5，遗留 #20 收口）
之后按"本人归属集合（答卷 ∪ 班级 ∪ 补考候选）"取数，修掉了真功能缺陷。但改后的第一条查询
（答卷渠道，阶段 1 捕获原文）：

`SELECT   exam_id,status,deadline_time   FROM exam_submissions      WHERE  (student_id = ?)`

丢掉了唯一能用上的索引锚：改前形状带 `exam_id IN (…)`，可走 uk_exam_student(exam_id, student_id)
最左列；改后只剩 `student_id = ?`，而 exam_submissions（本项目最大、写最热的表）没有任何
student_id 打头的索引（K6 清单核实），MySQL 上结构上只能全表扫。该查询是学生端每次打开
列表都走的路径。exam_candidates 的补考渠道（T2）同型。

这是**结构推演，不是实测**：未跑过 EXPLAIN、未测时延，不断言实际代价。
按 docs/需求决策记录.md §20（grep 锚点 `exam_bench`）："性能类 SQL 改写必须先有执行计划与
数据量再动手"；§20 同时给出反向判据："不为超出容量模型的形态新增索引"——收益点在包络外、
代价是交卷热写路径永久写放大，则不加。因此先归因，裁决后再谈实施。

## What Changes（按卡面阶段）

1. **阶段 1 只读核实**（已完成）：myExams() 五条 SQL 逐字捕获与逐条索引归因（含 LIMIT 在
   Java 侧、SQL 无 LIMIT 的确认）；数据量锚从仓库文档抄录（§20 exam_bench：10 万答卷 /
   500 场 / paper_json 4KB / ANALYZE 后 EXPLAIN ANALYZE；交卷压测：5000 并发在途、消费者
   批次 100）；候选臂集合复核——"锚定形状（exam_id IN）不能保语义"成立（答卷渠道的存在
   意义就是发现无班级绑定的本人历史考试，§12.6 转班成绩随人；锚定会重新引入 4c007c5
   刚修掉的缺陷）；exams 表默认不纳入（量级 500、非新增退化、无反证文档）。
   新增唯一仓库文件 MyExamsSqlCaptureIT.java（IT 后缀，不进 Surefire）。
2. **阶段 2 冻结判据**（已完成）：本目录 evidence/PREREGISTRATION.md 写死两形状（n1=10 万
   答卷/500 场/4KB、n2=×4）、七臂（A0/A0rep/A1/A2/C0/C0rep/C1）、逐轮左轮转、造数公式、
   判据 B1–B5 与阈值、机械裁决脚本（analyze-index-arms.cjs）与 adjudication.json 格式。
3. **阶段 3 真 MySQL 测量**（未执行——K8）：一次性 mysql:8.0 容器（tmpfs、独立库
   my_exams_index_measure、高位端口、utf8mb4），整库执行 schema.sql，逐形状逐臂逐轮
   EXPLAIN/EXPLAIN ANALYZE 与热写批测量，机械裁决。
4. **阶段 4 仅 GO 实施**（未执行）：只改 src/main/resources/schema.sql（仅 KEY 行，
   H2/MySQL 双兼容、禁前缀索引/禁 FORCE INDEX）+ docker/mysql/migrations/ 存量库脚本
   （头注写明重复执行预期错误码；提交信息写明该目录没有自动执行者，脚本入库 ≠ 迁移生效）；
   门禁 329/0/0/1。NO-GO 则 src/ 零改动（除阶段 1 捕获工具随收口提交）。
5. **阶段 5 收口**（未执行）：归档、spec-delta、spec/README 三处登记、F-F 观察项登记、
   F-D 措辞处置（NO-GO 分支：仅改措辞不动代码）、AGENTS 四自查、分笔提交。

## Impact

- **规范**：data-access 与 exam-taking（F-D 措辞）为拟变更域；spec-delta 在收口时按
  GO（合入索引条款）或 NO-GO（未采用登记 + 机械可复算判别式）落笔。
- **代码**：当前仅 src/test 一个捕获工具（未提交）。GO 时 schema.sql 仅增 KEY 行 +
  migrations 脚本；NO-GO 时 src/main 零改动。
- **用户与部署**：本变更自身不新增端点、不改可见行为。临时 MySQL 容器一次性、tmpfs、
  用毕销毁留证；不连共享 dev、不写 dev 库、不动 Redis 6379。
  docs/backend-optimization-candidates.md 全程未跟踪、不读、不改、不暂存。

## 停止条件与证据边界

- 卡面 §10 八条停手条款全文适用；**已触发第 2 条（K8）**，按其预定义分支停手。
- 临时容器（tmpfs、单机、空并发）结果只证明隔离口径下的机制与倍数，不外推生产 P99、
  生产数据量与真实并发；生产负载频度无获准来源，记为未知。
- 判据冻结后任何修改（含阈值、形状、臂、配方）都停手回报，不为结果迁就判据。
