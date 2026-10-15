# spec-delta: authentication (harden-data-sanitization-and-ui-states)

### Requirement: 用户批量导入导出

#### Scenario: Excel 导入用户全角空格清洗与导出防注入 (ADDED)
- **WHEN**: 管理员通过 Excel 导入用户（`POST /api/admin/users/import`），单元格（学号/用户名、姓名、邮箱、班级名称等）首尾带有中文全角空格（`\u3000`）或不换行空格（`\u00A0`）
- **THEN**: 系统 SHALL 在单元格提取阶段剥除首尾所有 Unicode 空白字符，确保用户名、姓名及班级名称的解析与匹配不受全角空格干扰
- **AND**: 在导出用户（`GET /api/admin/users/export`）时，写出单元格 SHALL 对用户名、姓名等可控字段执行公式防注入清洗（`sanitizeFormula`），防止注入执行
