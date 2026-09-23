# 规范差异：frontend（阶段 19/20 Requirement 合入）

本文件包含对 `spec/specs/frontend/spec.md` 的规范变更（新增——把 `add-frontend-skeleton-auth`（阶段 19）与 `add-frontend-teacher-authoring`（阶段 20）各自 spec-delta 的 Requirement 原样迁移合入；两份 delta 见各自归档目录 `specs/frontend/spec-delta.md`）。

## ADDED Requirements

（以下由阶段 19 / 20 的 spec-delta 按 Requirement 标题逐个追加，合入时**原样迁移不改写**；此处列标题清单作对齐判据——合入后本基线的 Requirement 集合与五阶段 spec-delta 双向对齐，不多不漏。）

### 阶段 19（add-frontend-skeleton-auth）应合入的标题

- 前端工程骨架与生成式 API 层
- 令牌续期单飞
- 角色路由守卫（前端侧呈现；权限裁决在后端 `@RequireRole`）
- 认证四页（登录/登出/改密 + 会话失效处理）

### 阶段 20（add-frontend-teacher-authoring）应合入的标题

- 题库列表与题型驱动编辑表单
- 标签管理
- 手动组卷与标签随机抽题
- 试卷预览

> 注：以两份 spec-delta 的实际 Requirement 标题为准——若本清单与 delta 实际标题不一致，以 delta 为准并在收尾回报里指出差异（清单是索引不是权威）。
