# 规范增量：score-management

## 新增需求：跨考试学情分析与知识点掌握度画像 (ADDED)

系统 SHALL 为学生提供跨考试的学情综合画像，基于学生历史全部已发布的考试成绩与题目标签，聚合分析个人成绩趋势与各知识点累计掌握度。

### Scenario: 学生查询个人跨考试学情画像 (ADDED)
- **WHEN**: 学生调用 `GET /api/scores/my/analytics` 查询个人学情
- **THEN**: 系统 SHALL 聚合该学生已交卷且已发布的所有考试（`status >= STATUS_PUBLISHED` 且 `published=1`）
- **AND**: 系统 SHALL 计算历史总览指标（参测场次、平均分、最高分、最低分、及格率）
- **AND**: 系统 SHALL 提取按时间排序的考试成绩趋势列表
- **AND**: 系统 SHALL 汇总各题目关联的知识点（Tag），统计各知识点的总分值、学生累计得分与掌握度（百分比），并将掌握度低于 75% 的标签标记为薄弱点（weakTags）
- **AND**: 若学生无已发布考试或无答卷记录，系统 SHALL 返回参测场次为 0 的空态响应，不得抛出 500 或 404 错误

---

## 新增需求：基于薄弱知识点的个性化靶向练习推荐 (ADDED)

系统 SHALL 能够根据学生在历史考试中表现最薄弱的知识点，从题库中智能筛选匹配客观机判题目进行个性化推荐。

### Scenario: 靶向练习推荐生成 (ADDED)
- **WHEN**: 学生调用 `GET /api/scores/my/recommendations?limit={limit}`
- **THEN**: 系统 SHALL 获取该学生掌握度最低的薄弱知识点标签
- **AND**: 系统 SHALL 从题库中检索属于该知识点、处于启用状态（status=1）、未删除（is_deleted=0）且属于机判题型（SINGLE/MULTIPLE/JUDGE/FILL_BLANK）的题目
- **AND**: 系统 SHALL 排除学生在错题重练中已消灭的题目
- **AND**: 系统 SHALL 返回带推荐原因（包含知识点名称与当前薄弱掌握度）的推荐题目列表，返回条数不超过 limit（默认 5，上限 10）
- **AND**: 若学生无可推荐题目或题库无匹配题目，系统 SHALL 优雅返回空列表

---

## 新增需求：教师端班级跨考试综合学情 (ADDED)

系统 SHALL 为教师提供所辖班级的跨考试整体学情分析与班级薄弱知识点汇总。

### Scenario: 教师查看班级学情与越权防护 (ADDED)
- **WHEN**: 教师调用 `GET /api/scores/classes/{classId}/analytics`
- **THEN**: 系统 SHALL 校验当前教师是否拥有该班级的所有权，非拥有者 SHALL 拒绝访问并抛出 403 错误
- **AND**: 系统 SHALL 聚合该班级所有已发布的考试，计算班级历史平均分趋势与班级各知识点平均掌握度
- **AND**: 系统 SHALL 识别全班掌握度最低的 Top 5 知识点并列入 weakTags
