# grading spec-delta：主观题批改分页护栏补强（update-grading-subjective-paging）

> 本变更针对前案 `add-subjective-grading-pagination` 已合入基线的既有规范条款进行护栏用例补强与边界核验，**不新增任何 Requirement**。

## MODIFIED Requirements

### Requirement: 主观题批改行分页与筛选

> 前案出处：`spec/specs/grading/spec.md`「Requirement: 主观题批改行分页与筛选」（由 `add-subjective-grading-pagination` 合入）。本案在其既有 6 个 Scenario（分页取数、缺省全量、筛选下沉、冲突回填单行取数、非法参数与越权、批改提交协议不变）基础上，追加 4 个边界与端到端协同的补强 Scenario 并落地自动化用例。

#### Scenario: 越界页返回空行（补强）

GIVEN 一场考试某主观题已有若干交卷学生行  
WHEN 传入的 page 超过根据 size 计算的最大有效页码（例如总数 5 条、size=2、传入 page=4 或 page=999）  
THEN 响应信封内 rows 返回空数组 `[]`，且 total 与 graded 仍保持真实全量统计数值。

#### Scenario: 相邻页无缝无重叠稳定排序（补强）

GIVEN 一场考试某主观题有多名交卷学生（student_id 严格不同）  
WHEN 连续请求相邻分页（Page 1 与 Page 2，统一 size）  
THEN 各页内行均按 student_id 升序排列，且 Page 1 最后一项的 student_id 严格小于 Page 2 第一项的 student_id，相邻页行集合无重叠、无遗漏。

#### Scenario: 越界页前端空态渲染与全量统计保持（补强）

GIVEN 前端批改面板接收到越界页数据（rows: []，total > 0，graded > 0）  
WHEN 渲染批改面板  
THEN 表格组件正确渲染空数据状态（无假数据报错），且顶部题级元信息「已批 X/Y」仍忠实反映信封中 graded 与 total 真实全量统计。

#### Scenario: 筛选作用域与题级进度口径一致性（补强）

GIVEN 教师在批改面板中输入学生姓名筛选或勾选只看未批改  
WHEN 触发筛选状态变更并上抛事件  
THEN 筛选操作仅对当前页展示或查询条件生效，题级进度「已批 X/Y」恒定维持信封中的全局全量统计口径，绝不同屏产生计数冲突。
