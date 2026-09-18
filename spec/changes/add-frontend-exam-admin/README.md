# 阶段 21 - 前端考务系统开发

## 概述

本阶段实现教师端考务管理功能，包括班级管理、考试创建/发布/强制结束、监考视图等核心功能。

## 当前进度（40%）

### ✅ 已完成
- Constants 与工具函数（状态映射、严重度映射、轮询配置）
- 班级管理页面（CRUD + 学生入班转班）
- 考试列表页面（分页、状态标签、发布/force-end 确认）
- 考试创建页面（表单校验、时间窗设置、防作弊配置）

### 🚧 待完成
- 考试详情页面（试卷快照预览、考生名单、提交进度）
- 监考视图页面（在线/离线统计、逐学生进度、异常高亮）
- 行为日志时间线（按学生查看事件轨迹）
- Grafana 观测入口链接
- 单测编写
- 真实联调验证

## 关键文件

### Constants
- `frontend/src/constants/examStatus.ts` - 考试状态映射
- `frontend/src/constants/severity.ts` - 严重度映射
- `frontend/src/constants/monitor.ts` - 监考轮询配置

### Pages
- `frontend/src/pages/(dashboard)/teacher/classes/index.page.vue` - 班级管理
- `frontend/src/pages/(dashboard)/teacher/exams/index.page.vue` - 考试列表
- `frontend/src/pages/(dashboard)/teacher/exams/create.page.vue` - 考试创建

### Documents
- `backend-contract-verification.md` - 后端契约核实结论
- `progress-report-1.md` - 进展报告
- `final-summary.md` - 最终总结

## Commit 记录

```bash
git rev-parse HEAD
378328cf90cb8a6747938a80063ea1c7e9b047e2

git log --oneline -2
378328c docs(add-frontend-exam-admin): 后端契约核实与进展报告
2789f4b feat(frontend): 班级管理与考试列表页面开发
```

## 门禁状态

```bash
✅ lint:check - 通过
✅ type-check:check - 通过
⏳ vitest - 待补充单测
```

## 约束遵守

| 约束 | 状态 |
|-----|------|
| 不改后端 | ✅ |
| 状态机权威在后端 | ✅ |
| force-end 知情确认 | ✅ |
| 准实时轮询不声称实时 | ✅ |
| 门禁不许放宽 | ✅ |

## 下一步

1. 开发考试详情页面
2. 开发监考视图页面
3. 开发行为日志时间线
4. 添加 Grafana 入口链接
5. 编写单测
6. 真实联调验证

---

**生成时间**: 2026-09-19  
**分支**: `feature/add-performance-deepening-readwrite`
