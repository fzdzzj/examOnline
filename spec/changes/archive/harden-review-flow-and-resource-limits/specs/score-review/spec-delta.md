# spec-delta: score-review (harden-review-flow-and-resource-limits)

### Requirement: 成绩复核处理

#### Scenario: 复核调分终局操作不可逆警示防呆 (ADDED)
- **WHEN**: 教师处理学生成绩复核申请并选择「同意」且填入「调整后总分」
- **THEN**: 界面 SHALL 显性展示不可逆操作 warning 警示提示，告知调分将直接覆写答卷最终得分
- **AND**: 提交操作 SHALL 具备知情防呆确认机制，杜绝教师手误直接提交覆写成绩
