# 规范增量：validate-question-import-answer-length

## 能力域：question-bank

### Requirement: 题库批量导入（容错与错误报告）

#### Scenario: 答案长度超限应用层前置拦截

GIVEN 导入 xlsx 文件中包含答案（归一化后）长度超过 512 字的题目行
WHEN 教师调用 POST /api/questions/import
THEN 该行被跳过并记入错误报告，错误原因明确为「答案最长 512 字」
AND 不触发底层数据库异常
AND 同批次其他合法题目正常入库
