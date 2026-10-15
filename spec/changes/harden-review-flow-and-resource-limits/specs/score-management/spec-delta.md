# spec-delta: score-management (harden-review-flow-and-resource-limits)

### Requirement: 成绩单 PDF 导出

#### Scenario: 个人成绩单 PDF 导出生僻特殊字符容错 (ADDED)
- **WHEN**: 导出个人成绩单 PDF 且考试标题或试题题干中含有超出基础中文字符集（`UniGB-UCS2-H`）编码范围的生僻字符或特殊数学符号
- **THEN**: 系统 SHALL 在写入 PDF 段落与表格单元格前进行字符安全过滤与替代，避免抛出 iText 内部不支持字符异常中断导出
- **AND**: PDF 文件 SHALL 依然能够正常生成并成功下载
