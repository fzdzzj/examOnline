# 规范差异：agent-harness 域前端单测超时预算固化

> **能力域**：`agent-harness`
> **对应变更**：`polish-observation-debts`

---

## ADDED Requirements

### Requirement: 前端单测超时预算固化

前端 vitest 单测超时预算 SHALL 在 `frontend/vitest.config.ts` 显式固化为 `testTimeout: 30000`，消除宿主 CPU 争用下默认 5s 超时的随机抖动（受害集合随机、单跑全过、双轮复核实测 `--testTimeout=30000` 稳定）；该改动 SHALL 不增删任何用例（用例数与既有基线一致）且不动 `include`/`setupFiles`/`restoreMocks`/`environment` 既有配置。

#### Scenario: 超时预算配置生效且用例集不变

- **GIVEN** `frontend/vitest.config.ts` 的 `test` 块含 `testTimeout: 30000`，`include`/`setupFiles`/`restoreMocks`/`environment` 与既有配置逐项一致
- **WHEN** 以默认命令运行前端单测（不加 `--testTimeout` 覆盖参数）
- **THEN** 全量用例在宿主存在合理 CPU 争用时不再出现 `Test timed out in 5000ms` 型失败
- **AND** 用例总数与变更前基线一致（配置改动不得增删用例）
