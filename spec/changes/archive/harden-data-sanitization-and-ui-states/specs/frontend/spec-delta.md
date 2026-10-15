# spec-delta: frontend (harden-data-sanitization-and-ui-states)

### Requirement: 核心管理界面失败三态分离与交互防呆

#### Scenario: 班级/题库/试卷详情/标签页面查询失败显性呈现 (ADDED)
- **WHEN**: 教师端班级列表（`classes/index`）、题库列表（`questions/index`）、试卷详情（`papers/[id]`）或标签列表（`tags/index`）页面的数据查询遭遇失败（如网络中断、HTTP 500、403 等）
- **THEN**: 页面 SHALL 解构并捕获 `error` 状态，在界面顶部显性渲染 `<Alert type="error" show-icon :message="errorText" />` 错误提示
- **AND**: 页面 SHALL 隐藏主数据表格及空状态提示（如「暂无数据」），禁止将系统错误降级或伪装为业务空态

#### Scenario: 题库批量删除操作 Loading 状态与并发防抖锁 (ADDED)
- **WHEN**: 教师在题库管理页面触发批量删除确认操作（`onBatchDelete`）
- **THEN**: 批量删除操作按钮 SHALL 呈现 `:loading="batchDeleting"` 状态，且按钮处于禁用状态
- **AND**: 在批量删除请求在途未决期间，所有对 `onBatchDelete` 的并发或重复触发 SHALL 被入口防抖锁拦截，防止产生重复并发请求
