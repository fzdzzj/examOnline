# spec-delta: frontend (add-paper-pdf-export)

### Requirement: 试卷管理界面

#### Scenario: 试卷导出 PDF 交互入口 (ADDED)
- **WHEN**: 教师在试卷详情页或试卷列表操作区点击「导出 PDF」
- **THEN**: 界面 SHALL 提供导出「学生作答卷」或「含答案解析卷」选项
- **AND**: 确认后发起流式下载并呈现 loading 交互状态，成功后自动下载对应 PDF 文件
