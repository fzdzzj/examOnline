# frontend spec-delta：判分失败答卷手动给分（add-frontend-manual-score）

## ADDED Requirements

### Requirement: 判分失败答卷手动给分

WHEN 教师在批改工作台运行整场判分后得到失败清单,

系统 SHALL 在每一条失败行内提供「手动给分」入口，使教师可在判分引擎反复失败时直接裁定该答卷的客观题总分（调用既有的 `POST /api/exams/{examId}/grading/submissions/{submissionId}/manual-score`），SHALL NOT 要求教师为一份引擎无法判分的答卷反复重判或重跑整场判分。手动给分入口 SHALL 带轻量确认，确认文案 SHALL 明示该操作绕过判分引擎、教师裁定即终局、该卷不再由引擎重算；入口 SHALL 由教师录入客观题总分，数值输入精度 SHALL 对齐契约 `ManualScoreRequest` 的约束（非负、最多 1 位小数），入口 SHALL 防重复点击；`examId`、`submissionId` 与 `objectiveScore` SHALL 分别取自生成契约的路径参数与请求体形状。手动给分结果 SHALL 按 `ApiResponseVoid` 的既有语义回填：请求成功即该行从失败清单移除且失败计数减一、成功计数加一，并失效题级进度与学生行查询以刷新同屏数据；请求失败时 SHALL 原样呈现后端 message，且失败清单与统计不被破坏。整场判分、逐卷重判与成绩汇总流程 SHALL 不因该入口的加入而改变，前端 SHALL NOT 自动重试手动给分。

#### Scenario: 仅失败行呈现手动给分入口

GIVEN 整场判分返回 `total=3 success=2 failed=1`，失败清单含一份答卷
AND 成功卷只有统计值、没有明细行

WHEN 批改工作台渲染判分结果

THEN 失败行内同时出现「重判」与「手动给分」入口
AND 成功卷不呈现任何手动给分入口
AND 失败清单行的原文案结构（答卷 ID / 学生 ID / 错误原因）保持不变

#### Scenario: 无失败时不出现手动给分入口

GIVEN 整场判分返回 `failed=0`（或失败清单为空）

WHEN 批改工作台渲染判分结果

THEN 页面上不存在「手动给分」入口

#### Scenario: 确认文案明示绕过引擎且教师裁定即终局

GIVEN 失败行内呈现「手动给分」入口

WHEN 教师点击该入口

THEN 确认层文案明示手动给分将绕过判分引擎、教师裁定即终局、该卷不再重算
AND 在教师确认之前不发起任何请求

#### Scenario: 确认后按契约路径参数与请求体调用手动给分

GIVEN 失败清单中答卷 `submissionId=101`，当前所选考试 `examId=7`，教师录入客观题总分 `88.5`

WHEN 教师在该行确认手动给分

THEN 前端调用生成 SDK 的 `manualScore`，路径参数为 `{ examId: 7, submissionId: 101 }`、请求体为 `{ objectiveScore: 88.5 }`
AND 未录入分数时确认不发起请求
AND 在途期间入口被禁用，重复确认只发出一次请求

#### Scenario: 手动给分成功回填该行并刷新同屏数据

GIVEN 教师已确认对某失败卷手动给分

WHEN 后端返回成功

THEN 该卷从失败清单移除，失败计数减一、成功计数加一
AND 提示手动给分成功
AND 题级进度与学生行查询被失效重取（不靠本地推算）
AND 清单其余行不受影响

#### Scenario: 手动给分请求失败原文呈现

GIVEN 教师已确认对某失败卷手动给分

WHEN 请求被拒绝（如超过客观题满分、答卷尚未交卷、越权或网络失败）

THEN 以 `message.error` 呈现后端返回的 message 原文（不本地编造文案）
AND 失败清单与统计保持原状，行数不减少
AND 不自动重试该请求

#### Scenario: 整场判分与逐卷重判零回归

GIVEN 页面已加入手动给分入口

WHEN 教师继续使用原有的「运行判分」与失败行的「重判」

THEN 整场判分的调用、loading 防重、结果统计与三段提示判定与加入入口前一致
AND 逐卷重判的入口文案、路径参数、成功回填、仍失败换原文与请求失败原文呈现与加入入口前一致
