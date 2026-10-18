# 证据总览：add-exam-time-extension 任务 2/3（实施 + TDD 留证）

> 变更：考中应急顺延与个性化补时（POST /api/exams/{id}/extend-time）
> 分支：feature/add-exam-time-extension；实施笔 4c89c81（基于立案笔 0c91308 / main@24bc3ad）
> 本目录全部日志为当次执行的原始重定向输出，未做人工编辑。

## 1. TDD 先红后绿

| 阶段 | 执行 | 结果 |
| --- | --- | --- |
| 红 | `./mvnw test -Dtest=ExamTimeExtensionIntegrationTest`（端点未实施） | `Tests run: 5, Failures: 5, Errors: 0` —— 5 用例全部 `expected:<200|400|403> but was:<500>`（未实施端点落兜底 handler 的既有形态），见 `tdd-red.log` |
| 绿 | 同命令（实施后） | `Tests run: 5, Failures: 0, Errors: 0`，见 `tdd-green-integration.log` |
| 单测 | `./mvnw test -Dtest=ExamServiceExtendTimeTest,AuditLogServiceExamOperationTest,ExamLifecycleNotificationServiceTest,ExamTimeExtensionIntegrationTest` | 4 类共 `Tests run: 26, Failures: 0, Errors: 0`（其中本案新增 14 例：集成 5 + 服务单测 3 + 通知单测 4 + 审计单测 2），见 `tdd-green-unit.log` |

红→绿之间实施改动见实施笔 4c89c81（DTO×2、ExamSubmissionMapper.extendDeadline、ExamService.extendTime、
AuditLog.ACTION_EXAM_EXTEND_TIME、AuditLogService.logExamExtendTime、ExamLifecycleNotificationService.notifyExamTimeExtended、
ExamController 端点、openapi.yaml 101→102）。红档曾出现一次测试自身笔误（个人模式 newEndTime 断言误写 +20 分钟），
修正断言后回绿，修正内容与实施同笔入库；红档日志为端点未实施版本，两版差异仅为该断言行与审计 action 字面量→常量引用。

## 2. 变异三连（均断言级击红，复原后零残留回绿）

| 变异 | 注入点 | 击红用例 | 红档断言 | 复原 | 回绿 |
| --- | --- | --- | --- | --- | --- |
| m1 删状态门控 | `ExamService.extendTime` 注释 `status != STATUS_IN_PROGRESS` 抛 400 分支 | `rejectsNonInProgressExamWithoutAnyMutation` | `expected:<400> but was:<200>` | `git checkout HEAD --` 后 `git status` 干净 | `Tests run: 1, Failures: 0` |
| m2 删答卷顺延 | 注释 `submissionMapper.extendDeadline(...)`，置 `affected = 0` | `globalExtensionShiftsExamWindowAndAllInflightDeadlines` | `两名在途考生都应被顺延 ==> expected: <2> but was: <0>` | 同上 | `Tests run: 1, Failures: 0` |
| m3 删审计记账 | 注释 `auditLogService.logExamExtendTime(...)` | 同上 | `全场顺延应恰好落一条 EXAM_EXTEND_TIME 审计 ==> expected: <1> but was: <0>` | 同上 | `Tests run: 1, Failures: 0` |

变异执行命令：`./mvnw test -Dtest=ExamTimeExtensionIntegrationTest#<用例>`；三组红绿日志分别为
`mutation-m{1,2,3}-red.log` / `mutation-m{1,2,3}-green.log`。每次复原后工作树对 HEAD 零 diff（仅未跟踪的 evidence 目录）。
变异均在实施笔 4c89c81 入库之后执行（checkout 复原不丢工作）。

## 3. 双端门禁（结论绑定产生它的执行）

- **后端**：`./mvnw clean test`（仓库根，带 clean）@ **4c89c81 已入库工作树**：
  `Tests run: 761, Failures: 0, Errors: 0, Skipped: 1`，`BUILD SUCCESS`，Total 07:25，见 `gate-backend-full.log`。
  基线 747 → 761（+14 即本案新增用例），skip 1 为存量既有。
  注：该门禁在实施笔入库后于同一 revision 复跑取证；首次跑（入库前工作树，内容与 4c89c81 字节一致）同为
  761/0/0/1 绿，最终证据以入库后复跑为准。
- **前端**：`npx vitest run --maxWorkers=4`（frontend/）：`Test Files 122 passed (122)`，`Tests 943 passed (943)`，
  与基线 943 持平（本案零前端改动），见 `gate-frontend-full.log`。

## 4. 契约与基线同笔上调

- `openapi.yaml`：`grep -c "^  /" openapi.yaml` = **102**（101 → 102，新增 `/api/exams/{id}/extend-time`）。
- `.github/ci/gate-baseline.json`：`backendTests 747 → 761`、`openapiPaths 101 → 102`、`frontendTests 943` 不动。

## 5. 文件清单

| 文件 | 内容 |
| --- | --- |
| tdd-red.log | 端点未实施 5/5 红 |
| tdd-green-integration.log | 实施 5/5 回绿 |
| tdd-green-unit.log | 服务/通知/审计单测 + 集成共 26/26 |
| gate-backend-full.log | 后端全量门禁 761/0/0/1 @ 4c89c81 |
| gate-frontend-full.log | 前端 vitest 943/943 |
| mutation-m1-red.log / -green.log | m1 状态门控击红/回绿 |
| mutation-m2-red.log / -green.log | m2 答卷顺延击红/回绿 |
| mutation-m3-red.log / -green.log | m3 审计记账击红/回绿 |
| evidence-summary.md | 本文件 |
| sha256.txt | 全部证据文件哈希 |

SHA256 清单见 `sha256.txt`（对哈希文件自身不哈希）。
