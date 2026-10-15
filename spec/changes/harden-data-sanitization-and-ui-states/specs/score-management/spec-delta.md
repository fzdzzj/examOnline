# spec-delta: score-management (harden-data-sanitization-and-ui-states)

### Requirement: 成绩导出与报表生成

#### Scenario: Excel 导出文本单元格公式防注入清洗 (ADDED)
- **WHEN**: 导出服务调用 `ExcelSheetWriter` 写出包含用户可控数据的单元格，且文本以敏感公式字符（`=`, `+`, `-`, `@`, `\t`, `\r`）开头
- **THEN**: 系统 SHALL 在生成单元格值时自动前置单引号 `'`，强制电子表格软件将其作为纯文本解析，防止触发公式解析或 DDE 命令执行（Formula / CSV Injection）
- **AND**: 对于不以敏感字符开头的正常文本、数值或空字符串，系统 SHALL 保持原样输出，不产生冗余字符
