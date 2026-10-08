# 需求规格对照证据（巡查面三）· survey-frontend-candidates-r2

> revision: 608adf5 (main)；基准 `docs/examOnline需求规格说明书.md`（§2.1 角色表 :38-44，§2.2 模块清单 :46-128）。

## F-4「管理员角色」状态复核（本卡重点更新项）

- 上一轮登记：管理员「前端仅复用教师页；后端有邀请码/审计/踢人五端点，前端零 UI → F-4」。
- 本轮现场核对：main @ 608adf5 已有
  - `frontend/src/pages/(dashboard)/admin/invite-codes/index.page.vue`（列表/生成/作废，全文件 251 行）
  - `frontend/src/pages/(dashboard)/admin/audit-logs/index.page.vue`（审计查询/强制下线，全文件 276 行）
  - admin 域五端点（listInviteCodes/createInviteCode/invalidateInviteCode/auditLogs/kickUser）均在 SDK 被消费（refscan 复核：不再零引用）。
- **结论**：管理员最小高价值面已覆盖；但 §2.1 典型操作中「用户管理 / 课程管理 / 系统配置 / 数据备份」前后端均无对应端点/页面（openapi.yaml admin 域仅 invite-codes / audit-logs / kick-user 三段）→ 其余运营面仍**整体未实现（范围决策）**。角色行由「→ F-4」更新为「最小集已覆盖，其余运营面整体未实现」。

## 上轮「部分覆盖 / 整体未实现」清单逐条复核

| 需求项 | 上轮定性 | 本轮复核 |
|---|---|---|
| §2.1 管理员角色 | 前端零 UI → F-4 | **已更新**：最小集接线，其余整体未实现 |
| 题库题型（9 种） | 仅 5 种 | 不变（范围决策，9 变更未触达题型） |
| §2.2-8.5 成绩导出 | 四类已接线（O-1） | 不变 |
| §2.2-9.1/9.5 数据/题目分析 | 部分 | 不变 |
| §2.2-7.6 浏览器锁定 | 仅两开关 | 不变（契约亦无） |
| §1.3 个人信息管理 | 改密覆盖 | 不变 |
| 学生错题本/解析 | 仅总分卡 | 不变 |

## Spec 明确排除 / 整体未实现（范围决策，不入候选主表）

- 答题页无计算器/标记/离线/人脸/设备指纹/浏览器锁定；切屏只警告不作废；不声称离线考试（以 spec/specs/frontend/spec.md 为准）。
- 微信扫码/教务对接/消息推送、题库批量导入含 OCR/AI/查重、蓝图/AI/模板库/A-B 卷、缓考后半、考前核验、判分引擎公式/代码/听力/口语、人脸/IP 指纹、学情/推荐/错题本——均未被 9 变更触及，维持不变。

## 性能维度模式级抽查结论（9 个新变更面）

- admin/audit-logs：双输入共用单一 300ms 防抖 + onBeforeUnmount 清理，无轮询无逐键请求 → 干净。
- admin/invite-codes：一次全量（无分页参数、量级小）→ 不构成反模式。
- exams/index：搜索 300ms 防抖 + 回第 1 页 + 服务端分页 → 干净。
- exams/create：回填单次请求；下拉走 fetchAllPages（maxPages 上限 + 失败保留）→ 无 deep watch/无逐键请求。
- grading：单卷动作单次请求 + 在途守卫 → 干净。
- papers/index：服务端分页 → 干净。
- makeups / landing / student-scores / reviews / absences：显式动作触发，无轮询/防抖短缺 → 干净。
- **结论**：9 个新变更面未引入新反模式，无需登记。