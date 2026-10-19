# 规范差异：reliability 域对账补齐行为显性化

> **能力域**：`reliability`
> **对应变更**：`polish-observation-debts`

---

## MODIFIED Requirements

### Requirement: 成绩链全链路对账

系统 SHALL 提供交卷→判分→成绩链路的对账批处理：按考试维度检测「未判分答卷」与「判分明细-总分不一致」两类差异；差异 SHALL 以差异票形式持久化并支持状态流转（OPEN / AUTO_FIXED / MANUAL_CLOSED）；确定性缺失（判分完成但总分未回填）SHALL 幂等自动补齐并记审计，涉分矛盾 SHALL 仅出票不自动改分；对账 SHALL 支持手动触发与定时兜底，且满足本域「定时任务的并发与多实例安全」既有口径。

#### Scenario: 未判分答卷检测与确定性缺失补齐

- **GIVEN** 某已达已判分状态的考试，存在一答卷交卷成功但判分记录缺失（或主观题未批完）
- **WHEN** 管理员触发对账（或定时兜底扫描执行）
- **THEN** 生成差异票记录考试/答卷/题目定位与差异类型（`SUBMISSION_UNGRADED`）
- **AND** 若为「判分完成但总分未回填」的确定性缺失，幂等自动补齐并记 `audit_log`，票状态置 `AUTO_FIXED`
- **AND** 数据矛盾类差异仅出票，状态保持 `OPEN` 待人工

#### Scenario: 判分明细与总分一致性比对

- **GIVEN** 某答卷的判分明细汇总分与最终成绩记录不一致
- **WHEN** 对账运行
- **THEN** 生成差异票（`DETAIL_TOTAL_MISMATCH`）记录期望值、实际值与偏差
- **AND** 系统不自动改分，差异票保持 `OPEN` 待人工处置

#### Scenario: 定时兜底与多实例安全

- **GIVEN** 多实例部署且定时对账已启用
- **WHEN** 定时触发到达
- **THEN** 同一批次不重复执行（按本域既有并发与多实例安全口径），运行记录标记 `SCHEDULED` 来源
- **AND** 定时对账失败不影响下一次运行（失败原因入运行记录）

#### Scenario: 差异票闭环与权限隔离

- **GIVEN** 存在 `OPEN` 状态差异票
- **WHEN** 无 `exam:manage` 权限的账号调用任一对账端点
- **THEN** 请求被拒绝（403）
- **WHEN** 管理员经 `POST /api/admin/reconciliation/diffs/{id}/close` 关闭差异票
- **THEN** 票状态流转 `MANUAL_CLOSED` 且关闭动作记审计，列表按状态筛选可查

#### Scenario: 主观未批完但客观已判的分部总分补齐

- **GIVEN** 某答卷客观题已全部判分、主观题未批完（`partial_graded=1`）且 `total_score` 未回填
- **WHEN** 对账运行
- **THEN** 幂等补齐写入口径与 `computeSummary` 同源的分部总分（客观判分汇总），保留 `partial_graded=1` 标记
- **AND** 同时生成 `SUBJECTIVE_NOT_FULLY_GRADED` 差异票如实暴露未批完状态，不写任何主观分（补批属改分，落既有负范围）
