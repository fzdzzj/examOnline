# frontend spec-delta：下拉与列表取数不截断（fix-frontend-list-truncation-family）

## ADDED Requirements

### Requirement: 下拉与列表取数不截断

教师端需要完整候选集的下拉与需要分页呈现的列表 SHALL NOT 把「一次请求的一页」当作全量：分页信封无 `total` 的候选下拉 SHALL 经统一累加器取数（满页续拉、空页或未满页即停、最多 5 页、单页失败保留已累积部分且不抛错）；列表页 SHALL 使用服务端分页（当前页进入 `queryKey`、翻页发起真实请求），SHALL NOT 一次取回后在客户端切片；列表查询失败 SHALL 以 Alert 显性呈现后端 message 且 SHALL NOT 伪装成空态；取数 SHALL 使用 `types.gen.ts` 导出的 SDK 函数与契约声明的分页参数，SHALL NOT 手写 URL。既有考试下拉的累积语义 SHALL 不因本 Requirement 的实现抽取而改变。

#### Scenario: 累加器逐页累积到到底

GIVEN 候选端点返回裸列表且无 total 信封

WHEN 第 1 页返回满页（size=100）、第 2 页返回 35 条

THEN 累加结果为 135 条且只发起 2 次请求

AND 首页即返回未满一页时只发起 1 次请求、不再续拉

#### Scenario: 累加器保护上限与单页失败保留

GIVEN 连续 5 页都返回满页

WHEN 累加到第 5 页

THEN 停止续拉（最多 5 页保护上限），返回已累积的 500 条

GIVEN 第 2 页请求失败

THEN 保留第 1 页已累积数据返回、不抛错、不阻断页面渲染

#### Scenario: 考务创建页试卷与班级下拉取全量候选

GIVEN 试卷与班级各 135 条（分两页返回）

WHEN 考试创建页加载两个下拉

THEN 试卷下拉与班级下拉都经累加器发起 2 次请求

AND 135 条全部为该下拉的可选项

#### Scenario: 转班目标下拉取全量班级

GIVEN 班级 135 条（分两页返回）且转班弹层已打开

WHEN 转班目标班级下拉加载

THEN 发起 2 次请求、目标选项覆盖全部班级（仅排除当前班级本身）

#### Scenario: 试卷列表翻页发起真实请求

GIVEN 试卷列表按服务端分页显示第 1 页 10 条（后端共 250 条）

WHEN 用户点第 2 页

THEN `queryKey` 随当前页变化并重新发起请求（`page: 2`）

AND 表格渲染的是第 2 页返回的行，源码不再存在一次取回后的本地切片

AND 分页器在满页时至少预留下一页（无 total 信封下做下界推断，不谎称精确总数）

#### Scenario: 列表查询失败不伪装空态

GIVEN 试卷列表查询失败

WHEN 页面渲染

THEN 错误 Alert 呈现后端 message，表格与其空态不出现

AND 成功且无数据时空态照常呈现
