# spec-delta: question-bank (add-question-duplicate-detection)

### Requirement: 题库查重与相似度预检

#### Scenario: 题干文本查重预检 (ADDED)
- **WHEN**: 教师调用题目查重端点（`GET /api/questions/check-duplicate?stem=...`）
- **THEN**: 系统 SHALL 结合字符 Bigram Jaccard 与编辑距离混合算法，计算题干与题库中有效题目（`is_deleted=0`）的相似度
- **AND**: 仅检索属于当前教师的题目（ADMIN 可检索全量），若指定 `excludeId` 则排除该题目自身
- **AND**: 响应返回相似度达到或超过阈值（默认 0.6 即 60%）的前 N 条题目（默认 5 条），按相似度降序排列
- **AND**: 题干内容为空或少于 2 个有效字符时返回 HTTP 400 Bad Request
