# frontend spec-delta：考试列表查询失败与编辑回填失败显性化（fix-frontend-exam-page-error-states）

## ADDED Requirements

### Requirement: 考试列表查询失败与编辑回填失败显性化

考试列表页（`teacher/exams/index.page.vue`）的列表/搜索/筛选查询失败 SHALL 以错误 Alert 显性呈现后端 message 且隐藏 Table，SHALL NOT 渲染为业务空态；成功且无数据时 SHALL 照常渲染既有业务空态文案。考试编辑页（`teacher/exams/create.page.vue`）在编辑模式下详情回填失败 SHALL 以错误 Alert 显性呈现后端 message，且回填成功前 SHALL 禁止保存（防默认值误写真实考试），回填成功后 SHALL 恢复保存可用；创建模式与编辑回填成功路径 SHALL 保持既有行为零改动。两处 SHALL NOT 自动重试、SHALL NOT 自动跳转。

#### Scenario: 列表查询失败显性呈现且隐藏表格

GIVEN 教师进入考试列表页

WHEN 列表查询（或搜索/筛选联动查询）失败

THEN 错误 Alert 呈现后端 message 且 Table 隐藏

AND 既有业务空态文案不出现（失败不得伪装成「无考试」）

#### Scenario: 列表查询成功空数组保留空态

GIVEN 教师在考试列表页

WHEN 列表查询成功且返回空数组

THEN 既有业务空态文案原样呈现

AND 不出现错误 Alert

#### Scenario: 列表查询成功数据零回归

GIVEN 教师在考试列表页

WHEN 列表查询成功且返回数据

THEN Table 照常渲染数据行、不出现错误 Alert、既有分页/筛选/操作列行为不变

#### Scenario: 编辑回填失败显性呈现并禁用保存

GIVEN 教师以编辑模式（route query 携带 examId）进入创建页

WHEN 详情回填查询失败

THEN 错误 Alert 呈现后端 message 且「保存修改」按钮禁用、提交被拦截（不落默认值写真实考试）

#### Scenario: 编辑回填成功零回归

GIVEN 教师以编辑模式进入创建页且详情回填查询成功

THEN 详情返回值回填全部表单字段（含防作弊两开关）

AND 「保存修改」可用、提交按既有链路调用 `PUT /api/exams/{id}` 成功后跳回列表并失效 exams 查询

#### Scenario: 创建路径零回归

GIVEN 教师以新建模式（无 examId）进入创建页

THEN 标题为「新建考试」、不触发详情回填查询、不出现回填失败 Alert

AND 提交按既有链路调用 `POST /api/exams`，行为与既有创建路径一致