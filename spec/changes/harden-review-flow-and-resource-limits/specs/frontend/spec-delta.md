# spec-delta: frontend (harden-review-flow-and-resource-limits)

### Requirement: 考后处理终局操作防呆与细节交互打磨

#### Scenario: 成绩复核弹层不可逆调分警示与确认 (ADDED)
- **WHEN**: 教师在复核处理弹窗（`ReviewHandleModal.vue`）选择「同意」并输入调分数值
- **THEN**: 弹层中 SHALL 呈现 warning 警示提示，强调调分操作不可逆
- **AND**: 点击确认时 SHALL 经二次防呆确认，防止手误直接提交

#### Scenario: 班级列表移除学生按钮 Loading 态 (ADDED)
- **WHEN**: 教师在班级管理中点击移出学生
- **THEN**: 移出操作按钮 SHALL 呈现 `:loading="removing(record)"` 动画状态并处于禁用状态，与请求在途状态一致
