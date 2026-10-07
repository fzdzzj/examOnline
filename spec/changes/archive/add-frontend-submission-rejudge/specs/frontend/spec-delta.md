# frontend spec-delta：判分失败答卷逐卷重判（add-frontend-submission-rejudge）

## ADDED Requirements

### Requirement: 判分失败答卷逐卷重判

WHEN 教师在批改工作台运行整场判分后得到失败清单,

系统 SHALL 在每一条失败行内提供「重判」入口，使教师可对单份答卷调用既有的单卷重判端点（`POST /api/exams/{examId}/grading/submissions/{submissionId}/rejudge`），SHALL NOT 要求教师为处置个别失败卷而重跑整场判分。重判入口 SHALL 带轻量确认（说明将对这一份答卷重新判分）并防重复点击；`examId` 与 `submissionId` SHALL 取自生成契约的路径参数形状。重判结果 SHALL 按后端返回的同步结果行回填：该卷重判成功时该行从失败清单移除且失败计数相应减少，该卷重判仍失败时该行错误文案更新为后端本次返回的原文；请求失败时 SHALL 原样呈现后端 message，且失败清单与统计不被破坏。重判成功 SHALL 失效题级进度与学生行查询以刷新同屏数据。整场判分与成绩汇总流程 SHALL 不因该入口的加入而改变，前端 SHALL NOT 自动重试重判。

#### Scenario: 仅失败行呈现重判入口

GIVEN 整场判分返回 `total=3 success=2 failed=1`，失败清单含一份答卷
AND 成功卷只有统计值、没有明细行

WHEN 批改工作台渲染判分结果

THEN 失败行内出现「重判」入口
AND 成功卷不呈现任何重判入口
AND 失败清单行的原文案结构（答卷 ID / 学生 ID / 错误原因）保持不变

#### Scenario: 无失败时不出现重判入口

GIVEN 整场判分返回 `failed=0`（或失败清单为空）

WHEN 批改工作台渲染判分结果

THEN 页面上不存在「重判」入口

#### Scenario: 确认后按契约路径参数调用单卷重判

GIVEN 失败清单中答卷 `submissionId=101`，当前所选考试 `examId=7`

WHEN 教师点击该行的「重判」并在确认弹层中确认

THEN 前端调用生成 SDK 的 `rejudge`，路径参数为 `{ examId: 7, submissionId: 101 }`
AND 未确认时不发起任何请求
AND 在途期间该行入口被禁用，重复确认只发出一次请求

#### Scenario: 重判成功回填该行并刷新同屏数据

GIVEN 教师已确认对某失败卷发起重判

WHEN 后端返回的同步结果行不带错误原因

THEN 该卷从失败清单移除，失败计数减一、成功计数加一
AND 提示重判成功
AND 题级进度与学生行查询被失效重取（不靠本地推算）
AND 清单其余行不受影响

#### Scenario: 重判仍失败呈现后端原文且不破坏清单

GIVEN 教师已确认对某失败卷发起重判

WHEN 后端返回的同步结果行带有新的错误原因

THEN 该行仍留在失败清单中，错误文案更新为后端本次返回的原文
AND 失败计数不减、不提示成功
AND 提示以 `message.error` 呈现该原文（不本地编造文案）

#### Scenario: 重判请求失败原文呈现

GIVEN 教师已确认对某失败卷发起重判

WHEN 重判请求被拒绝（如越权、答卷不属于该考试或网络失败）

THEN 以 `message.error` 呈现后端返回的 message 原文
AND 失败清单与统计保持原状，行数不减少
AND 不自动重试该请求

#### Scenario: 整场判分与汇总流程零回归

GIVEN 页面已加入逐卷重判入口

WHEN 教师继续使用原有的「运行判分」并进入后续汇总成绩

THEN 整场判分的调用、loading 防重、结果统计与三段提示判定与加入入口前一致
AND 已批改/已发布考试仍不出现整场重判入口
AND 汇总成绩的前置拒绝提示与指引不变
