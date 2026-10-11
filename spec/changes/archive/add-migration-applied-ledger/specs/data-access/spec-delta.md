# data-access spec-delta：存量库迁移执行台账与核验（add-migration-applied-ledger）

## ADDED Requirement: 存量库迁移执行台账与核验

WHEN `docker/mysql/migrations/` 下的存量库迁移脚本需要对某个已存在的库确认或执行应用,
系统 SHALL 以入库的执行台账（`docker/mysql/migrations/APPLIED.md`）作为「环境 × 已应用脚本」的唯一结构化载体，且登记真值 SHALL 来自对该库现场执行核验 SQL 的实测（验证对象而非「命令没报错」）；文献与回报中的「已应用」声称仅作对照，不构成登记真值。

#### Scenario: 登记表唯一结构化载体

GIVEN `docker/mysql/migrations/` 下的迁移脚本集

WHEN 需要回答「某环境的哪些脚本已应用、哪些未应用」

THEN 唯一的结构化答案是 APPLIED.md 登记表（每脚本一行：脚本名、对象、幂等预期错误码、核验 SQL、各环境状态与核验时间、当时 commit）

AND 登记表脚本集与 `git ls-files docker/mysql/migrations` 恒对齐，表内不写脚本计数（计数随新增即过期，与本目录 README 红线同源）

AND 本目录 README 与 AGENTS 只保留指向登记表的指针，不复制清单

#### Scenario: 现场实测优先于文献自述

GIVEN 归档 evidence、决策记录或任何回报中出现「脚本 X 已应用于某库」的声称

WHEN 该声称需要成为登记真值

THEN 必须以对目标库现场执行核验 SQL（SHOW CREATE TABLE / SHOW INDEX / information_schema 计数口径）的实测结果为准，文献声称仅作对照

AND 实测与文献冲突时如实登记差异并上报裁决，不取任何一方静默覆盖

#### Scenario: 应用后必须更新登记表

GIVEN 一次对存量库的人工迁移应用已完成（应用 + 执行后验证）

WHEN 该应用被认定为「完成」

THEN 同笔更新 APPLIED.md 对应行（状态、核验输出摘要、核验时间、当时 commit）——应用、验证、登记三件套齐才算完成

AND 只提交脚本不应用、或只应用不登记，均不构成迁移完成（与 AGENTS 约定 4「脚本已提交 ≠ 迁移已完成」同源）

#### Scenario: 未应用缺口显性可见

GIVEN dev 主/从库的现场盘点完成

WHEN 某脚本的目标对象在库中不存在

THEN 登记表该行状态为「未应用」，缺口对任何读者显性可见

AND 补应用按文件名周期升序执行（同表多脚本存在依赖顺序）；从库先核复制状态——复制正常由复制同步并逐对象复核，异常即停上报不擅修

AND 每次补应用留存完整链证据：应用前状态 → 应用命令 → 应用后验证输出

#### Scenario: 新环境从零建库走 runbook

GIVEN 一个新建的空 MySQL 环境

WHEN 需要建库

THEN 按 APPLIED.md 的 runbook 走：起库 → `schema.sql` 一次建全 → 迁移脚本一律无需执行（新建库不是存量库）

AND 需要与既有环境核对一致性时，按登记表的核验 SQL 口径逐对象核对，不重放迁移脚本
