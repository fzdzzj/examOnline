# 归档速览瘦身：逐条覆盖校验结论（polish-observation-debts 任务 2③）

> **执行时点**：2026-10-19（分支 feature/polish-observation-debts）
> **校验对象**：`spec/CHANGES_ARCHIVE.md`（冷存储台账）中是否存在等价或更详条目
> **校验方法**：对速览区 56 条去重条目逐条在归档表中按 ``| `change-id` |`` 精确匹配行号；另以全文子串匹配复核（无「只在注记里被提及」的假命中）

## 1. 前置事实：冷存储台账在本笔之前滞后 5 天

- `spec/CHANGES_ARCHIVE.md` 最后一次更新是 `f1c42d8`（2026-10-14，规范冷热分离立案笔），归档表最后一行是 `add-paper-template-clone`（2026-10-14）；
- 2026-10-15 之后归档的变更**从未回填**冷存储（精确行命中 0、全文子串命中 0，复核输出见 `archive-digest-verify.txt`），而速览区自 10-15 起持续追加，形成「热区重复、冷区缺档」的反向失衡；
- 因此本笔不能只删不搬：按派工卡「覆盖校验不过的保留并上报」升级为「先原样移入冷存储，再瘦身」（用户当轮裁决同此）——41 条无冷存储等价条目的行**逐字节原样**追加进 `CHANGES_ARCHIVE.md` 归档表末尾（同表头同四列，按归档日期升序），随后速览只留最近 10 条。零丢档：41 条搬移行与速览原件逐字节一致（74627 字节，逐行比对见 `archive-digest-verify.txt`）。

## 2. 逐条结论（56 条去重条目，按速览原顺序：新 → 旧）

| # | 变更 ID | 速览原行 | 归档日期 | 原冷存储行 | 结论 | 搬移后冷存储行 |
|---|---|---|---|---|---|---|
| 1 | `add-score-chain-reconciliation` | 60 | 2026-10-19 | 无（缺档） | KEEP-NEWEST10 | —（留在速览） |
| 2 | `add-irt-adaptive-testing` | 61 | 2026-10-19 | 无（缺档） | KEEP-NEWEST10 | —（留在速览） |
| 3 | `add-subjective-double-blind-grading` | 62 | 2026-10-19 | 无（缺档） | KEEP-NEWEST10 | —（留在速览） |
| 4 | `add-student-wrong-question-mastery` | 63 | 2026-10-18 | 无（缺档） | KEEP-NEWEST10 | —（留在速览） |
| 5 | `add-exam-question-discrimination` | 64 | 2026-10-18 | 无（缺档） | KEEP-NEWEST10 | —（留在速览） |
| 6 | `add-exam-time-extension` | 65 | 2026-10-18 | 无（缺档） | KEEP-NEWEST10 | —（留在速览） |
| 7 | `harden-register-rate-limit` | 66 | 2026-10-18 | 无（缺档） | KEEP-NEWEST10 | —（留在速览） |
| 8 | `harden-data-retention-scope` | 67 | 2026-10-18 | 无（缺档） | KEEP-NEWEST10 | —（留在速览） |
| 9 | `add-exam-analysis-export` | 68 | 2026-10-18 | 无（缺档） | KEEP-NEWEST10 | —（留在速览） |
| 10 | `add-exam-operation-audit-logs` | 69 | 2026-10-18 | 无（缺档） | KEEP-NEWEST10 | —（留在速览） |
| 11 | `harden-draft-and-grading-boundaries` | 70 | 2026-10-18 | 无（缺档） | MOVED | 167（本笔原样追加） |
| 12 | `polish-scratchpad-touch-support` | 71 | 2026-10-18 | 无（缺档） | MOVED | 166（本笔原样追加） |
| 13 | `add-exam-scratchpad-canvas` | 72 | 2026-10-18 | 无（缺档） | MOVED | 165（本笔原样追加） |
| 14 | `add-student-question-favorites` | 81 | 2026-10-18 | 无（缺档） | MOVED | 164（本笔原样追加） |
| 15 | `harden-low-risk-security-residues` | 90 | 2026-10-17 | 无（缺档） | MOVED | 163（本笔原样追加） |
| 16 | `harden-cross-cutting-edge-cases` | 91 | 2026-10-17 | 无（缺档） | MOVED | 162（本笔原样追加） |
| 17 | `add-prod-profile-enforcements` | 92 | 2026-10-17 | 无（缺档） | MOVED | 161（本笔原样追加） |
| 18 | `harden-actuator-exposure` | 93 | 2026-10-17 | 无（缺档） | MOVED | 160（本笔原样追加） |
| 19 | `harden-slave-read-failover` | 94 | 2026-10-17 | 无（缺档） | MOVED | 159（本笔原样追加） |
| 20 | `harden-scheduler-pool-config` | 95 | 2026-10-17 | 无（缺档） | MOVED | 158（本笔原样追加） |
| 21 | `harden-spring-cache-resilience` | 96 | 2026-10-17 | 无（缺档） | MOVED | 157（本笔原样追加） |
| 22 | `harden-token-store-redis-degradation` | 97 | 2026-10-17 | 无（缺档） | MOVED | 156（本笔原样追加） |
| 23 | `add-redis-dependency-runbook` | 98 | 2026-10-17 | 无（缺档） | MOVED | 155（本笔原样追加） |
| 24 | `harden-submit-lock-redis-degradation` | 99 | 2026-10-17 | 无（缺档） | MOVED | 154（本笔原样追加） |
| 25 | `harden-login-guard-redis-degradation` | 100 | 2026-10-17 | 无（缺档） | MOVED | 153（本笔原样追加） |
| 26 | `fix-mail-smtp-timeout-and-catch` | 101 | 2026-10-17 | 无（缺档） | MOVED | 152（本笔原样追加） |
| 27 | `fix-reservation-remind-transaction` | 102 | 2026-10-17 | 无（缺档） | MOVED | 151（本笔原样追加） |
| 28 | `harden-auth-interceptor-redis-degradation` | 103 | 2026-10-17 | 无（缺档） | MOVED | 150（本笔原样追加） |
| 29 | `fix-prod-container-tz` | 104 | 2026-10-17 | 无（缺档） | MOVED | 149（本笔原样追加） |
| 30 | `fix-subjective-grading-panel-flaky` | 105 | 2026-10-16 | 无（缺档） | MOVED | 148（本笔原样追加） |
| 31 | `fix-behavior-counter-ttl-atomicity` | 106 | 2026-10-16 | 无（缺档） | MOVED | 147（本笔原样追加） |
| 32 | `optimize-wrong-question-derivation-cost` | 107 | 2026-10-16 | 无（缺档） | MOVED | 146（本笔原样追加） |
| 33 | `add-pre-exam-device-readiness-check` | 108 | 2026-10-16 | 无（缺档） | MOVED | 145（本笔原样追加） |
| 34 | `harden-auth-edge-cases` | 109 | 2026-10-16 | 无（缺档） | MOVED | 144（本笔原样追加） |
| 35 | `optimize-practice-pool-hot-path` | 110 | 2026-10-16 | 无（缺档） | MOVED | 143（本笔原样追加） |
| 36 | `harden-behavior-report-and-monitor` | 111 | 2026-10-16 | 无（缺档） | MOVED | 142（本笔原样追加） |
| 37 | `fix-exam-state-machine-transaction` | 112 | 2026-10-16 | 无（缺档） | MOVED | 141（本笔原样追加） |
| 38 | `optimize-backend-batch-write-paths` | 113 | 2026-10-16 | 无（缺档） | MOVED | 140（本笔原样追加） |
| 39 | `polish-student-taking-and-review-markers` | 114 | 2026-10-16 | 无（缺档） | MOVED | 139（本笔原样追加） |
| 40 | `polish-authoring-monitoring-and-grading-flow` | 115 | 2026-10-16 | 无（缺档） | MOVED | 138（本笔原样追加） |
| 41 | `add-batch-tagging-and-score-balancing` | 116 | 2026-10-16 | 无（缺档） | MOVED | 137（本笔原样追加） |
| 42 | `add-student-learning-analytics` | 117 | 2026-10-15 | 无（缺档） | MOVED | 136（本笔原样追加） |
| 43 | `add-exam-lifecycle-notifications` | 118 | 2026-10-15 | 无（缺档） | MOVED | 135（本笔原样追加） |
| 44 | `add-question-duplicate-detection` | 119 | 2026-10-15 | 无（缺档） | MOVED | 134（本笔原样追加） |
| 45 | `add-paper-pdf-export` | 120 | 2026-10-15 | 无（缺档） | MOVED | 133（本笔原样追加） |
| 46 | `harden-review-flow-and-resource-limits` | 121 | 2026-10-15 | 无（缺档） | MOVED | 132（本笔原样追加） |
| 47 | `harden-data-sanitization-and-ui-states` | 122 | 2026-10-15 | 无（缺档） | MOVED | 131（本笔原样追加） |
| 48 | `harden-core-security-and-state-boundaries` | 123 | 2026-10-15 | 无（缺档） | MOVED | 130（本笔原样追加） |
| 49 | `harden-exam-paper-and-practice-boundaries` | 124 | 2026-10-15 | 无（缺档） | MOVED | 129（本笔原样追加） |
| 50 | `add-question-import-export` | 125 | 2026-10-14 | 122 | ALREADY-IN-ARCHIVE | 122（原行即等价条目，实删安全） |
| 51 | `update-analysis-option-distribution` | 126 | 2026-10-14 | 123 | ALREADY-IN-ARCHIVE | 123（原行即等价条目，实删安全） |
| 52 | `add-wrong-question-practice` | 127 | 2026-10-14 | 124 | ALREADY-IN-ARCHIVE | 124（原行即等价条目，实删安全） |
| 53 | `add-user-import-export` | 128 | 2026-10-14 | 125 | ALREADY-IN-ARCHIVE | 125（原行即等价条目，实删安全） |
| 54 | `add-paper-template-clone` | 129 | 2026-10-14 | 126 | ALREADY-IN-ARCHIVE | 126（原行即等价条目，实删安全） |
| 55 | `add-anti-cheat-restrictions` | 130 | 2026-10-15 | 无（缺档） | MOVED | 128（本笔原样追加） |
| 56 | `validate-question-import-answer-length` | 131 | 2026-10-14 | 无（缺档） | MOVED | 127（本笔原样追加） |

## 3. 汇总

- 速览区原始表头块 3 个（含两个历史快照块）、变更行 58 行、去重后 56 条；重复条目 2 条（`add-exam-scratchpad-canvas`、`add-student-question-favorites`），另有 4 行陈旧能力域行（`| 19 | `dev-config` …` / `| 20 | `deployment` …`）系旧表格形态残留，其内容已在 README「已合入规范」表 52/53 行完整在场，随块删除；
- KEEP-NEWEST10（留在速览）= 10 条；MOVED（本笔搬入冷存储）= 41 条；ALREADY-IN-ARCHIVE（原行即等价条目，实删安全）= 5 条；
- 5 条 ALREADY-IN-ARCHIVE 的速览行比冷存储行各多 4 字节，逐字符比对差异仅为 ``--no-ff`` 的 Markdown 双反引号包法（冷存储行同样记录了合并笔号，内容等价），非明细缺失；
- 速览区从 3 个表头块 / 79 行压到 1 个表头块 / 10 行，表头补「完整台账以 CHANGES_ARCHIVE.md 为准」指针；笔误「以 ind spec/...」同笔修正为「以 find spec/...」（README 表头 + 冷存储文件头各一处）。
