# spec-delta: frontend (add-exam-lifecycle-notifications)

### Requirement: 消息中心界面

#### Scenario: 消息中心一键全部标为已读 (ADDED)
- **WHEN**: 用户在消息中心下拉弹窗（`MessageCenter.vue`）中且当前未读消息数大于 0
- **THEN**: 弹窗头部 SHALL 呈现「全部已读」操作按钮
- **AND**: 点击「全部已读」后 SHALL 调用 `PUT /api/messages/read-all` 端点
- **AND**: 响应成功后系统 SHALL 联动将本地未读数角标更新为 0，并刷新消息列表中所有消息展示为已读态
