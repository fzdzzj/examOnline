# absence-makeup 规范

> 能力域：缺考与补考（阶段 9，W10-W11）。
> 来源：`spec/changes/archive/add-class-and-post-exam-closure` 合入（缺考标记、补考独立记录、补考成绩规则）。
> 实施注记：缺考口径 = 应考名单（考试 `class_id` → `user_class` 当前学生）− 有答卷者，考试状态机「进行中 → 已结束」时触发，写 `exam_absence`（唯一索引 + INSERT IGNORE 幂等）。补考是独立考试记录（`exams.parent_exam_id` 关联主考），准入由 `exam_candidates` 名单限制。

## Requirements

### Requirement: 缺考标记

WHEN 考试结束,

系统 SHALL 将「应考名单中无答卷记录的学生」标记为缺考，缺考学生 SHALL 可被教师筛选指定补考。

#### Scenario: 考试结束标记缺考

GIVEN 考试到达结束时间

AND 应考名单中某学生未点击开始（无答卷）

WHEN 系统识别缺考

THEN 标记该学生为缺考

AND 记录缺考状态

#### Scenario: 缺考名单可筛选

GIVEN 教师查看缺考名单

WHEN 教师按考试查询

THEN 返回该考试全部缺考学生

AND 可勾选进入补考名单

---

### Requirement: 补考独立记录

WHEN 教师组织补考,

系统 SHALL 创建独立补考考试记录，与主考互不影响，且 SHALL 限制仅名单内学生可进入。

#### Scenario: 补考独立

GIVEN 教师为主考指定补考

WHEN 创建补考

THEN 生成独立考试记录（独立时间窗/时长/规则）

AND 与主考成绩互不影响

#### Scenario: 名单限制进入

GIVEN 补考有指定名单

WHEN 名单外学生尝试进入补考

THEN 拒绝进入

---

### Requirement: 补考成绩规则

WHEN 计算补考最终成绩,

系统 SHALL 按考试配置的规则（取最高分/取最近一次/取平均分）合并，历史成绩 SHALL 保留不覆盖。

#### Scenario: 取最高分

GIVEN 补考成绩规则为取最高分

AND 学生主考与补考均有成绩

WHEN 计算最终成绩

THEN 取两者较高分

AND 历史成绩均保留

#### Scenario: 历史成绩保留

GIVEN 学生多次补考

WHEN 计算最终成绩

THEN 各次成绩记录均保留

AND 不覆盖删除
