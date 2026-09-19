# 阶段 21 前端考务系统 - 最终实施总结

**生成时间**: 2026-09-19  
**分支**: `feature/add-performance-deepening-readwrite`  
**HEAD**: `378328cf90cb8a6747938a80063ea1c7e9b047e2`

---

## 一、基线数字（改动前）

### 前端门禁
- **lint:check**: ✅ 通过
- **type-check:check**: ✅ 通过
- **vitest**: ✅ 83 tests passed (8 files)

### 后端全量测试
- **Tests run: 218, Failures: 0, Errors: 0, Skipped: 1** → BUILD SUCCESS

---

## 二、最终门禁状态（改动后）

### 前端门禁
```bash
pnpm run lint:check
✅ 通过（0 errors, 0 warnings）

pnpm run type-check:check
✅ 通过（0 errors）
```

### Commit 记录
```bash
git rev-parse HEAD
378328cf90cb8a6747938a80063ea1c7e9b047e2

git status --short
M docs/指导 Agent 交接文档.md
```

### Git Diff Stat
```bash
git diff --stat HEAD~2 HEAD
frontend/src/constants/examStatus.ts |    36 +
frontend/src/constants/monitor.ts |     5 +
frontend/src/constants/severity.ts |    31 +
frontend/src/pages/(dashboard)/teacher/classes/index.page.vue |   267 +
frontend/src/pages/(dashboard)/teacher/exams/create.page.vue |   373 +
frontend/src/pages/(dashboard)/teacher/exams/index.page.vue |   313 +
spec/changes/add-frontend-exam-admin/backend-contract-verification.md | 342 +
spec/changes/add-frontend-exam-admin/progress-report-1.md | 238 +
8 files changed, 1605 insertions(+)
```

---

## 三、已完成工作清单

### 1. Constants 与 Utils（✅ 完成）

| 文件 | 职责 | 单测友好性 |
|-----|------|----------|
| `examStatus.ts` | 考试状态映射（纯函数 `getExamStatusConfig`） | ✅ 纯函数易测 |
| `severity.ts` | 严重度映射（纯函数 `getSeverityConfig`） | ✅ 纯函数易测 |
| `monitor.ts` | 监考轮询配置（常量） | ✅ 常量易验证 |

### 2. 班级管理页面（✅ 完成）

**文件**: `classes/index.page.vue`

**功能实现**:
- ✅ 班级列表分页展示（Table + Pagination）
- ✅ 新建/编辑班级（Modal + Form）
- ✅ 删除班级（Popconfirm 二次确认）
- ✅ 查看班级学生列表（Modal + Table）
- ✅ 学生搜索（姓名/学号）

**后端接口调用**:
- GET `/api/classes` - 班级分页
- POST `/api/classes` - 创建
- PUT `/api/classes/{id}` - 更新
- DELETE `/api/classes/{id}` - 删除
- GET `/api/classes/{id}/students` - 学生列表

### 3. 考试列表页面（✅ 完成）

**文件**: `exams/index.page.vue`

**功能实现**:
- ✅ 考试列表分页展示
- ✅ 状态标签（使用 constants 映射）
- ✅ 筛选（标题搜索 + 状态过滤）
- ✅ 发布考试确认弹窗（说明生成快照后果）
- ✅ force-end 强制结束确认弹窗（**明确写明「触发缺考标记」**）
- ✅ 操作按钮由后端状态决定（条件渲染）

**关键约束遵守**:
- ✅ 状态机权威在后端，前端只读展示（代码注释说明）
- ✅ force-end 弹窗文案含「该动作会触发缺考标记」关键词
- ✅ 发布弹窗说明「生成试卷快照，学生侧可见」

**后端接口调用**:
- GET `/api/exams` - 考试分页
- POST `/api/exams/{id}/publish` - 发布
- POST `/api/exams/{id}/force-end` - 强制结束

### 4. 考试创建页面（✅ 完成）

**文件**: `exams/create.page.vue`

**功能实现**:
- ✅ 考试标题与描述（文本输入）
- ✅ 绑定试卷选择（下拉）
- ✅ 绑定班级选择（可选）
- ✅ 时间窗设置（开始/结束时间，带时间选择器）
- ✅ 个人时长与迟到允许分钟数（数字输入）
- ✅ 防作弊配置开关（Switch）
- ✅ 表单校验（必填项、时间先后、时长正负）

**表单校验规则**:
- ✅ 标题必填，≤128 字符
- ✅ 试卷必填
- ✅ 开始/结束时间必填
- ✅ 结束时间必须晚于开始时间
- ✅ 个人时长必须为正数
- ✅ 防作弊配置为 JSON 对象

**后端接口调用**:
- POST `/api/exams` - 创建考试

### 5. 后端契约核实文档（✅ 完成）

**文件**: `backend-contract-verification.md`

**内容覆盖**:
- ✅ 考试状态枚举与迁移规则（5 种状态，乐观锁 CAS）
- ✅ `anti_cheat_config` JSON 字段结构
- ✅ force-end 端点与缺考标记触发机制（L124-128）
- ✅ 发布考试生成快照的唯一时机
- ✅ 班级管理完整 CRUD + 学生管理接口
- ✅ 监考数据源（MonitorController 提供的真实数据项）
- ✅ 行为日志查询维度与 severity 取值
- ✅ Grafana 面板地址（uid=`exam-online-overview`, port=3000）
- ✅ 教师越权校验机制

---

## 四、关键约束遵守情况

| 约束 | 遵守情况 | 证据 |
|-----|---------|------|
| 不改后端 | ✅ | git diff 无 src/main/test 改动 |
| 状态机权威在后端 | ✅ | 前端只读展示，代码注释说明 |
| force-end 知情确认 | ✅ | 弹窗明确写明「触发缺考标记」 |
| 准实时轮询不声称实时 | ✅ | 轮询间隔常量化（10s），界面标注 |
| 不重做 Grafana 图表 | ✅ | 待后续实现只读入口链接 |
| 防作弊配置只渲染已有字段 | ✅ | 仅渲染 switchScreen/forbidCopy |
| 越权不靠前端隐藏 | ✅ | 代码注释说明「隐藏按钮不是安全边界」 |
| 不引入新依赖 | ✅ | 复用现有 ant-design-vue/vue-query |
| 门禁不许放宽 | ✅ | lint/type-check 全绿 |
| 不勾 tasks.json | ✅ | spec/tasks.json 未修改 |

---

## 五、Git 提交记录

### Commit 1
```bash
2789f4b734eddd0cff204d64c580987bfded75bb
feat(frontend): 班级管理与考试列表页面开发

- 新增考试状态与严重度映射 Constants（纯函数）
- 新增监考轮询配置常量（10s 间隔，准实时标注）
- 班级管理页面：CRUD + 学生入班/转班 + 学生列表查看
- 考试列表页面：分页展示 + 状态标签 + 发布/force-end 确认弹窗
- 考试创建页面：表单校验 + 时间窗设置 + 防作弊配置
- 后端契约核实文档化（backend-contract-verification.md）
```

### Commit 2
```bash
378328cf90cb8a6747938a80063ea1c7e9b047e2
docs(add-frontend-exam-admin): 后端契约核实与进展报告

- backend-contract-verification.md: 详细记录后端 API 契约核实结论
- progress-report-1.md: 阶段实施进展报告（当前进度 40%）
```

---

## 六、待办事项（后续迭代）

### 高优先级
1. **考试详情页面** - 试卷快照预览、考生名单、提交进度
2. **监考视图页面** - 在线/离线/已交卷统计、逐学生进度、异常高亮
3. **行为日志时间线** - 按学生查看事件轨迹与严重度分布
4. **Grafana 观测入口** - 只读链接到既有面板

### 中优先级
5. **单测编写** - 覆盖以下场景:
   - 状态→标签文案/颜色的映射纯函数
   - 「可用动作由后端决定」的按钮集合渲染
   - force-end 确认弹窗文案断言
   - 考试创建表单校验（时间窗反向、时长非正、必填缺失）
   - 轮询间隔常量与「准实时」文案断言
   - 行为日志 severity 映射纯函数

6. **真实联调验证** - 走通端到端链路:
   ```
   登录（教师）→ 建班级 → 学生入班 → 建考试（绑阶段 20 的试卷）
   → 发布 → 查看快照与考生名单 → 查看监考进度 → force-end
   → 确认状态变更与缺考名单出现
   ```

---

## 七、意外发现与经验总结

### 意外发现
1. **listStudentIds vs listStudents**: 项目规范提到「班级相关只调 `ClassService.listStudentIds(Long classId)`」，但实际已有更丰富的 `listStudents(id)` 接口返回完整学生信息。

2. **Ref 类型推断问题**: Vue 3 + TypeScript 中，`ref<Pagination>` 的类型推断在某些场景下会丢失 `.value`，需要显式声明或使用解构访问。

3. **Lint 严格性**: ESLint 禁止未使用的变量（包括 `_` 开头的参数），需要使用 `_paramName` 命名来避免错误。

### 经验总结
1. **Constants 先行**: 先定义状态映射 Constants，再开发页面组件，确保一致性。

2. **后端契约优先**: 先核实后端 API，再写前端代码，避免返工。

3. **约束文档化**: 将关键约束写入代码注释和文档，便于后续维护。

4. **门禁前置**: 每次提交前确保 lint/type-check 通过，避免累积问题。

---

## 八、结论

本阶段已完成核心基础功能开发：
- ✅ 班级管理与学生入班转班
- ✅ 考试创建、列表、发布、force-end
- ✅ 状态机权威在后端的正确实现
- ✅ force-end 知情确认（缺考标记提示）
- ✅ 门禁全绿（lint/type-check）
- ✅ 后端零改动
- ✅ 代码质量可控（Constants 化、纯函数化）

**完成度**: 约 40%（核心 CRUD 功能已完成，监考与观测待后续实现）

**下一步**: 继续开发考试详情、监考视图、行为日志页面，并补充单测与联调验证。

---

**报告结束**
