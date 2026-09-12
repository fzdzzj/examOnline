# question-bank 规范

> 能力域：题库与组卷（阶段 3，W2-W3）。
> 来源：`spec/changes/add-question-bank` 合入（题目管理、答案归一化、标签体系、手动组卷、标签随机抽题、抽题锁定与试卷快照）。

## Requirements

### Requirement: 题目管理

WHEN 教师操作题目,
系统 SHALL 支持单选、多选、判断、简答四类题型的创建、查询、更新与软删除。

#### Scenario: 创建四种题型题目

GIVEN 教师已登录且具备题库操作权限
WHEN 教师创建单选题、多选题、判断题或简答题
THEN 系统创建对应题目
AND 记录题目类型、内容、选项、正确答案与分值

#### Scenario: 软删除题目

GIVEN 存在一道已被引用或未被引用的题目
WHEN 教师删除该题目
THEN 系统标记 is_deleted 而非物理删除
AND 题目不参与后续组卷

#### Scenario: 非所有者越权操作被阻止

GIVEN 教师 A 试图修改教师 B 的题目
AND 教师 A 不具备管理员权限
WHEN 教师 A 发起修改请求
THEN 系统返回 403 Forbidden
AND 不执行任何变更

---

### Requirement: 答案归一化

WHEN 题目答案被存储,
系统 SHALL 以统一格式存储客观题答案；判断题 SHALL 使用 T/F，多选 SHALL 使用有序选项。

#### Scenario: 判断题答案归一化

GIVEN 教师录入判断题答案（如"正确"、"对"、"A"）
WHEN 系统存储
THEN 答案统一归一化为 T 或 F

#### Scenario: 多选题答案归一化

GIVEN 教师录入多选题答案
WHEN 系统存储
THEN 答案以有序选项列表存储
AND 消除顺序歧义

---

### Requirement: 标签体系

WHEN 教师管理标签,
系统 SHALL 提供学科、难度、题型、自定义四类扁平标签，且题目 SHALL 可多选关联多个标签。

#### Scenario: 题目多标签关联

GIVEN 存在学科与难度标签
WHEN 教师为主题目关联标签
THEN 题目可同时关联多个标签
AND 支持按标签筛选题目

---

### Requirement: 手动组卷

WHEN 教师手动组卷,
系统 SHALL 支持将题目加入试卷、调整题号顺序，并 SHALL 允许在试卷内覆盖题目的默认分值。

#### Scenario: 组卷与分值覆盖

GIVEN 一道默认 5 分的题目
WHEN 教师将其加入试卷并设置本题分值 10 分
THEN 试卷内该题分值为 10
AND 题目默认分值仍为 5（互不影响）

#### Scenario: 总分校验

GIVEN 试卷各题分值之和与试卷总分不一致
WHEN 教师保存试卷
THEN 系统校验失败并提示调整

---

### Requirement: 标签随机抽题

WHEN 教师按规则随机抽题,
系统 SHALL 按标签/难度/题型比例抽取指定数量题目，并在题库容量不足时 SHALL 提示教师调整。

#### Scenario: 按规则抽题成功

GIVEN 题库中存在满足标签/难度/题型条件的题目
WHEN 教师设置抽取条件与数量
THEN 系统返回满足条件的随机题目

#### Scenario: 题库容量不足

GIVEN 满足条件的题目数量少于抽取数量
WHEN 教师发起抽题
THEN 系统提示教师调整条件或数量

---

### Requirement: 抽题锁定与试卷快照

WHEN 抽题结果确定,
系统 SHALL 生成试卷快照（含题目/答案/分值/顺序），之后读取 SHALL 以快照为准，刷新 SHALL 不改变题目与顺序。

#### Scenario: 快照锁定

GIVEN 一次抽题已完成并生成快照
WHEN 教师或学生刷新页面
THEN 读取到与首次一致的题目与顺序
AND 不重新抽题

#### Scenario: 题目变更不影响快照

GIVEN 试卷快照已生成
AND 之后某题目被修改或软删除
WHEN 读取快照
THEN 快照内容保持不变
AND 历史组卷结果不受影响
