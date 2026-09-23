# 规范差异：frontend（补考最终成绩展示）

本文件包含对 `spec/specs/frontend/spec.md` 的规范变更（修改）。

## MODIFIED Requirements

### Requirement: 缺考与补考界面

WHEN 考试结束需要处理缺考学生,

系统 SHALL 提供缺考名单查看、补考创建与补考最终成绩展示，且 SHALL NOT 在前端本地推算补考合并规则。

#### Scenario: 缺考名单可见

GIVEN 一场考试已结束

WHEN 教师查看缺考名单

THEN 呈现被标记缺考的学生

AND 自然到点与强制结束两条路径产生的标记均可见

#### Scenario: 补考可创建

GIVEN 某学生缺考或需要补考

WHEN 教师创建补考

THEN 补考作为独立考试记录建立

AND 准入范围按后端返回渲染

#### Scenario: 补考最终成绩展示

GIVEN 后端已提供补考最终成绩端点（沿主考家族按考试配置规则合并，历史成绩保留不覆盖）

WHEN 教师或学生查看补考关联成绩

THEN 呈现后端返回的最终成绩

AND 合并规则完全由后端计算，前端不本地推算

#### Scenario: 学生侧复核语义同构

GIVEN 学生查询本人补考最终成绩

WHEN 后端返回 reviewing 为 true

THEN 隐藏分数（防「看了分数再申请」），与 myScore 口径同构

AND 未发布统一按后端「成绩待发布」渲染
