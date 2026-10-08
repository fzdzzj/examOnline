# frontend spec-delta：教师端考试列表服务端筛选联动（add-exam-list-filtering）

## ADDED Requirements

### Requirement: 教师端考试列表服务端筛选联动

教师端考试列表页面（`frontend/src/pages/(dashboard)/teacher/exams/index.page.vue`）SHALL 将标题与状态筛选条件作为参数传递至后端 `pageExams`（`GET /api/exams`），并 SHALL 将查询参数纳入 `queryKey`。搜索标题输入 SHALL 具备防抖机制，筛选条件变更 SHALL 自动重置到第 1 页，且表格 SHALL 呈现服务端筛选后的完整分页数据，不再由前端进行客户端分页内切片过滤。

#### Scenario: 标题与状态参数透传服务端
GIVEN 教师在考试列表页输入搜索标题并选择状态
WHEN 发起数据拉取
THEN 调用 `pageExams` 时携带 `query: { page, size, title, status }`
AND 请求返回的数据直接用于表格呈现

#### Scenario: 标题输入防抖避免逐键请求
GIVEN 教师在搜索框连续快速输入字符
WHEN 触发 input 事件
THEN 在防抖窗口期（300ms）内不发起新请求，停止输入达到防抖时间后才触发带新标题的查询请求

#### Scenario: 筛选条件变更自动重置第 1 页
GIVEN 当前处于第 2 页
WHEN 教师修改标题搜索词或更改状态下拉
THEN 当前页码自动重置为 1，并以第 1 页参数发起新请求

#### Scenario: 占位提示文案移除当前页限定
GIVEN 考试列表页面加载完成
WHEN 观察搜索输入框
THEN placeholder 为「搜索考试标题」，不包含「（当前页）」字样
