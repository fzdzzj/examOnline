# spec-delta: question-bank (add-paper-pdf-export)

### Requirement: 试卷标准纸质版 PDF 导出

#### Scenario: 试卷学生考试作答版 PDF 导出 (ADDED)
- **WHEN**: 教师导出试卷学生作答版 PDF（`GET /api/papers/{id}/export-pdf?withAnswer=false`）
- **THEN**: 系统 SHALL 返回标准 A4 排版的 PDF 字节流，包含试卷标题、基本说明、考生信息填空区（姓名/学号/班级/考场）、大题得分汇总表格
- **AND**: 各大题按题型（单选、多选、判断、填空、简答）规整分组排列，输出题号、分值、题干及选项
- **AND**: 填空题与简答题留出作答空白区域，不展示任何答案与解析内容
- **AND**: 仅试卷所属教师或管理员可成功导出，非所有者请求 SHALL 返回 403 Forbidden，不存在试卷返回 404

#### Scenario: 试卷教师参考答案版 PDF 导出 (ADDED)
- **WHEN**: 教师导出试卷参考答案版 PDF（`GET /api/papers/{id}/export-pdf?withAnswer=true`）
- **THEN**: 系统 SHALL 在返回的 PDF 题目后清晰输出【参考答案与评分标准】
- **AND**: 客观题输出标准答案，填空题输出可接受答案列表，简答题输出参考答案及解析
- **AND**: 文本内容经 Unicode 特殊字符安全清洗，生僻字与特殊符号不中断导出
