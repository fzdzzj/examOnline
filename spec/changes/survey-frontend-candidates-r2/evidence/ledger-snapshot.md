# 台账快照（ledger-snapshot）· survey-frontend-candidates-r2

> 产出文件：docs/frontend-improvement-candidates-r2.md（不入库纪律文件）。
> 快照日：2026-10-08；revision 608adf5。
> 候选 = 3 项（面二 2 项 + 面一观察 1 项）。候选 ≠ 已确认缺陷 ≠ 已批准变更。

| 编号 | 面向 | 锚点 | 疑点 | 最小下一步 |
|---|---|---|---|---|
| U-6 | 面二·UX | frontend/src/pages/(dashboard)/teacher/exams/index.page.vue:272-292 | 列表/搜索查询失败无 error 反馈，静默落空态（U-1 已在四处收口，本页残留）| 本页 useQuery 补 error 消费，失败 Alert + Table 隐藏；先量该页查询失败实际频度 |
| U-7 | 面二·UX | frontend/src/pages/(dashboard)/teacher/exams/create.page.vue:181-224 | 编辑回填失败静默、表单停默认值、全文件 0 Alert，默认值误写风险 | 补回填失败 error 反馈 + 回填前禁保存；纯前端 |
| O-2 | 面一·观察 | sdk.gen.ts:249 addQuestion；papers/[id].page.vue 无单题入卷实引 | 单题入卷端点被批量 addQuestions 取代、全仓零 SDK 实引（第 1 轮漏记）| 观察项；如需收编契约驱动口径单独决策 |

面一复核：上轮剩余 11 个零引用项判定均仍成立（detail/detail3/getSnapshot/generateSnapshot/paper/replay/progress/export×4）→ 记「复核、结论不变」，未重报。
U-3 复核：GET /api/exams title/status 两参数均被消费（exams/index.page.vue:272-290），无缺口。
面三复核：F-4 管理员角色状态更新（最小集已覆盖，其余整体未实现）；上轮未实现清单逐条不变。
性能维度：9 个新变更面未引入新反模式。

台账全量内容以 docs/frontend-improvement-candidates-r2.md 为准（本快照仅非规范摘要）。
