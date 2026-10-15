# spec-delta: question-bank (harden-data-sanitization-and-ui-states)

### Requirement: 题库批量导入导出

#### Scenario: Excel 导入单元格 Unicode 全角空格与不可见空白清洗 (ADDED)
- **WHEN**: 教师上传 Excel 文件批量导入题目（`POST /api/questions/import`），单元格内容首尾包含中文全角空格（`\u3000`）、不换行空格（`\u00A0`）或多余空白字符
- **THEN**: 系统 SHALL 在单元格提取阶段（`cellText`）自动剥除首尾全部 Unicode 空白，再进行题型、题干、选项、答案、标签及难度解析
- **AND**: 首尾含全角空格的合法文本（例如 `　单选　` 或 `　易　`）SHALL 成功解析并入库，杜绝因输入法全角空白导致整行校验报错跳过
