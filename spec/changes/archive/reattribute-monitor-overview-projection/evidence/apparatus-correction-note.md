# 装置勘误记录（两处装置缺陷与其处置）

> 事实登记，非判据改写。PREREGISTRATION.md 冻结文本一字未改（sha256 保持 1481d739…）。
> 记录两次作废运行、两处装置缺陷与修正；两处均为装置自身的问题（一处被装置自检拦截、一处暴露为 PROJ 臂 500），
> 判据（M1–M3、S1–S4、5.0 倍数、臂与轮数、站点定义、措辞纪律、s2 量级锚）零修改。

## 勘误 1：`paper_json` 构型分解算式（冻结文本 §1 内部算术错误）

- 冻结原文：「…构型 `{"questions":[{"id":1},…,{"id":40}],"pad":"B…B"}`：题干数组 40×8 + 39 逗号 = 359 字符；…`pad` 为 20096 个 `B` ⇒ 359 + 25 + 20096 = 20480。」
- 实际：id 1–9 共 9 项 × 8 字符 = 72；id 10–40 共 31 项 × 9 字符 = 279；题干数组 = 72 + 279 + 39 = **390**（非 359；「40×8」漏记 31 个两位 id 各多的 1 字符）。骨架 14（`{"questions":[`）+ 9（`],"pad":"`）+ 2（`"}`）= 25 ✓。
- 现场（void run 1）：在 n=200 造数阶段被夹具自检拦下 `IllegalStateException: paper_json 构造长度不符: 20511`（= 390 + 25 + 20096），未产生任何测量数据。
- 修正：`PAPER_PAD_CHARS` 20096 → **20065**（= 20480 − 415）；冻结可观测值（`paper_json` 恰 20480、40 题 `questions`、与 s2 同口径可比）不变。

## 勘误 2：窄读参数取自投影行（装置缺陷）

- 现象（void run 2）：三个形状 PROJ 臂 HTTP 500（`owner 调用语义不符 … projStatus=500`、`预热失败 arm=PROJ status=500`），护栏 `narrowQueries=1, narrowRows=0, narrowParams=[]`，而 `dbPaperJsonNonNull=200>0`。
- 根因：窄读的 `exam_id` 从返回实体第 0 行回读（`((ExamSubmission) rawRows.get(0)).getExamId()`），而 PROJ 臂主语句已投影为 `student_id,status`——投影行不含 `exam_id` ⇒ 值恒为 null ⇒ 窄读谓词 `exam_id = NULL` 恒 0 行；随后 `List.of(examId)` 对 null 抛 NPE ⇒ 该请求 500。
- 修正：窄读参数改用「被查看的考试」本身（装置在发起调用时已知，与生产方法 `overview(examId)` 的参数同源）：`ArmCtx.examId` 于 `begin(arm, examId)` 设定，`narrowReadAndSupply()` 以其为参数；不再从实体回读。
- 影响范围：void run 2 的全部产物（`capture-n*.json`、`rounds-n*.json`、manifest 61 条 problems）作废，原始产物留存于 `raw/void-run2/`；重跑为整轮重新执行。

## 作废轮清单（重跑前均留存原始产物）

| 运行 | 时间（本地） | 结局 | 原始产物 |
|---|---|---|---|
| void run 1 | 2026-09-29 21:02:07–21:02:38 | 夹具自检拦截（20511），零测量数据 | `raw/it-run-run1-void.log` |
| void run 2 | 2026-09-29 21:03:31–21:04:30 | 装置缺陷（PROJ 臂 500 / S1 不成立，61 条 problems） | `raw/void-run2/`、`raw/it-run-run2-void.log` |

两次作废均无「挑选可用轮次」的余地：void run 1 无任何测量输出；void run 2 的 PROJ 臂全部 500 且护栏不成立，不存在可用的 PROJ 数据。重跑均为整轮重新执行，而非事后择取。
