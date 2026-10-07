# frontend spec-delta：查询失败与业务态分离（fix-frontend-query-failure-states）

## ADDED Requirements

### Requirement: 查询失败与业务态分离

页面数据查询失败 SHALL 显性呈现错误态（Alert 承载后端 message），SHALL NOT 渲染为业务空态或「成绩待发布」业务态；「成绩待发布」SHALL 仅由后端 400 固定文案判别（`utils/scoreVisibility.ts` 的 `isNotPublishedError`），前端 SHALL NOT 放宽该判别或引入第二套本地推断；既有业务空态文案 SHALL 保持原样（成功且无数据时照常呈现）。

#### Scenario: 学生成绩页失败显性呈现

GIVEN myScore 或补考最终成绩查询返回非「成绩待发布」错误

WHEN 页面渲染

THEN 错误 Alert 呈现后端 message

AND 未发布卡片不出现（失败不得伪装成「成绩未发布」业务态）

#### Scenario: 待发布仅认后端 400 文案

GIVEN 后端返回 400「成绩待发布」

WHEN 页面渲染

THEN 仍渲染未发布业务态、不弹错误 Alert（回归保护，判别不放宽）

#### Scenario: 教师侧列表失败不伪装空态

GIVEN 复核申请 / 缺考名单 / 补考候选人查询失败

WHEN 页面渲染

THEN 错误 Alert 呈现后端 message 且既有空态文案不出现

AND 成功且无数据时空态文案原样保留

#### Scenario: 补考最终成绩查询失败同口径

GIVEN 教师侧补考最终成绩查询失败

WHEN 页面渲染

THEN 错误 Alert 呈现后端 message，成功空值提示文案保持原样
