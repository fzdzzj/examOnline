# question-bank 规范变更

## ADDED Requirements

### Requirement: 学生题目自主收藏管理 (add-student-question-favorites)

系统 SHALL 允许已登录学生对其有权查看的题库题目进行自主收藏与取消收藏，并提供按学生隔离的分页查询与状态检测能力。

#### Scenario: 学生收藏有效题目（幂等写入）

GIVEN 已登录合法学生身份
AND 题库中存在有效且未被软删除的题目 `questionId`
WHEN 学生发起收藏请求 `POST /api/student/favorites/{questionId}`
THEN 系统在 `student_question_favorites` 表中记录当前学生与该题目的关联
AND 若此前已收藏该题目，操作静默成功（幂等性满足）

#### Scenario: 收藏不存在或已软删除题目拦截

GIVEN 题目 ID 在系统不存在或 `deleted=1`
WHEN 学生发起收藏请求 `POST /api/student/favorites/{questionId}`
THEN 系统拒绝请求并返回 404 NOT_FOUND（错误码 `QUESTION_NOT_FOUND`）
AND 数据库不发生任何收藏写入

#### Scenario: 学生取消收藏题目（幂等删除）

GIVEN 已登录学生发起取消收藏请求 `DELETE /api/student/favorites/{questionId}`
WHEN 该题目此前已被收藏
THEN 系统移除该学生的对应收藏记录
AND 若此前未收藏过该题目，操作亦静默成功返回 200（幂等性满足）

#### Scenario: 查询本人收藏题目列表（分页与题型过滤）

GIVEN 已登录学生发起查询请求 `GET /api/student/favorites?page=1&size=10&type=1`
WHEN 请求合法
THEN 系统仅返回当前登录学生本人的收藏记录（严格用户隔离）
AND 结果按收藏时间倒序排序
AND 联查下发题目题干、选项、解析与题型信息

#### Scenario: 批量检测题目收藏状态

GIVEN 用户在答卷回顾或题库浏览页传入多个题目 ID `GET /api/student/favorites/check?questionIds=1,2,3`
WHEN 请求参数合法（不超过 100 个 ID）
THEN 系统返回当前学生已收藏的题目 ID 集合
AND 未收藏的题目 ID 不在返回列表中
