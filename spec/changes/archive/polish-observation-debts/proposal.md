# 提案：观察项清偿与工程卫生打包（polish-observation-debts，T 级）

> **变更级别**：T（打磨/卫生，零 DDL、零契约、零迁移）
> **目标能力域**：`reliability`（对账补例）、`agent-harness`（测试超时预算）；README 瘦身为无行为变更的实施内容。

## 1. 背景（三件挂起物）

1. **C7 复核观察项①（欠账）**：`ReconciliationService` 补齐调用对「主观未批完但客观已判」分支会写入带 `partial_graded=1` 的分部总分（与 `computeSummary` 同源、保留 partial 标记、不写任何主观分）——该分支暂无用例覆盖，复核已实名挂后续补例。
2. **spec/README.md 归档速览膨胀**：CHANGES_ARCHIVE.md 冷存储迁移后，速览区仍堆积 40+ 条数千字超长行（与冷存储重复），侵蚀总账可读性。
3. **前端 vitest 超时抖动**：C6/C7 两轮复核实测默认 5s 超时在宿主 CPU 争用下随机超时（受害集合随机、单跑全过、`--testTimeout=30000` 独占复跑稳定），CI 与本地体验不一致且浪费复核轮次。

## 2. 方案

### 2.1 观察项①补例与行为显性化（reliability 域）

- `ScoreChainReconciliationIntegrationTest` 补 1~2 例：构造主观未批完但客观已判的答卷，对账 run 后断言——`total_score` = 客观判分汇总（`computeSummary` 同源）、`partial_graded=1`、生成 `SUBJECTIVE_NOT_FULLY_GRADED` 差异票、零主观分写入；
- spec-delta 以 MODIFIED Requirement 追加 Scenario「主观未批完但客观已判的分部总分补齐」，把复核注记中的隐性行为显性入基线，用例获得规范锚点。

### 2.2 归档速览瘦身（无行为变更，不出 Scenario）

- 机械口径：速览区仅保留**最近 10 条**（按归档时间倒序），其余删除；
- **防丢档前置**：删除前逐条覆盖校验该变更在 `spec/changes/archive/CHANGES_ARCHIVE.md` 有等价或更详条目，校验结论（逐条对照结果）留证 evidence；校验不过的条目保留并如实上报；
- 速览表头补指针：完整台账以 CHANGES_ARCHIVE.md 为准。

### 2.3 vitest 超时预算固化（agent-harness 域）

- `frontend/vitest.config.ts` `test` 块加 `testTimeout: 30000`（两轮复实测争用下稳定值）；**不动** `include`/`setupFiles`/`restoreMocks`/`environment`；
- 用例数不得变化（980）；spec-delta 出 ADDED Requirement「前端单测超时预算固化」。

## 3. 红线

- 零 DDL（schema.sql 不动）、零迁移脚本、零契约变更（openapiPaths 保持 114）；
- 后端门禁只增（853 → 854+，新增用例须全绿）；前端用例数 980 不变（配置改动不得增删用例）；
- README 瘦身不得丢档（覆盖校验先行、结论留证）；
- evidence 清单 LF 行尾（Git Bash `sha256sum -c` 须过）。

## 4. 变异计划

| 变异 | 抽除点 | 预期击红 |
|---|---|---|
| m1 | 补齐分支的 partial 分部总分写入 | 新增用例（total_score/partial_graded 断言） |
| m2 | `SUBJECTIVE_NOT_FULLY_GRADED` 出票 | 新增用例（差异票断言） |
| m3 | 补齐 CAS 的 `total_score IS NULL` 幂等护栏 | 涉分改写相关用例（C7-m2 已证可击红） |

三击均须断言级失败、回绿零残留（字节级复原留证）。
