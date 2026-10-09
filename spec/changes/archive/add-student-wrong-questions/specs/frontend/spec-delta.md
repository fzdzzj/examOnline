# frontend 规范增量（add-student-wrong-questions）

## 变更概述

新增学生端「我的错题本」独立页面（`/student/wrong-questions`）以及在「我的成绩与复核」（`/student/scores`）查分页中新增已发布考试的「逐题回顾」入口与全题回顾弹层。

---

## 新增 Requirements

### Requirement: 学生错题本界面

WHEN 学生访问「我的错题本」页面,

系统 SHALL 展示按考试分组的错题列表，每道错题展示题干、我的答案、正确答案、得分/满分、题目解析与判分依据；题目解析为空时 SHALL 显示友好占位「暂无解析」而非空白错乱；无错题时 SHALL 展示「暂无错题」正向空态；查询失败时 SHALL 沿三态收口口径显性呈现错误 Alert 并隐藏数据区；侧边栏在学生角色下提供导航入口并在 `NAVIGABLE_PATHS` 白名单中登记。

#### Scenario: 按考试分组渲染错题明细

GIVEN 错题接口返回已发布考试分组数据及错题明细

WHEN 页面加载完成

THEN 页面按考试卡片/分组呈现考试标题与考试时间

AND 组内展示题号、题型、题干、我的答案、正确答案、得分/满分

#### Scenario: 题目解析为空时优雅占位

GIVEN 某道错题的 `analysis` 为空或 null

WHEN 界面渲染该题目解析

THEN 界面显示灰色或弱化占位文案「暂无解析」

AND 不发生布局错乱或无意义空白

#### Scenario: 无错题正向空态

GIVEN 错题接口返回总数为 0 或错题组为空

WHEN 界面渲染

THEN 展示 Empty 组件且文案为正向的「暂无错题」

AND 不显示错误 Alert

#### Scenario: 查询失败三态收口

GIVEN 错题接口请求失败（如网络错误或 500）

WHEN 界面渲染

THEN 显性呈现 Alert 错误提示且文案为后端 message 原文

AND 错题数据区完全隐藏

#### Scenario: 侧边栏与白名单登记

GIVEN 学生已登录且具备 STUDENT 角色

WHEN 渲染侧边栏导航

THEN 「学生端」分组下包含「我的错题本」菜单项且 key 为 `/student/wrong-questions`

AND `NAVIGABLE_PATHS` 包含 `/student/wrong-questions`

---

### Requirement: 查分页逐题回顾入口与弹层

WHEN 学生在「我的成绩与复核」页面查看已发布考试的成绩,

系统 SHALL 在成绩卡片操作区展示「逐题回顾」入口；未发布（`not-published`）或复核中（`reviewing`）考试 SHALL 严格不出此入口；点击后打开单场逐题解析弹层，加载并呈现全题明细、学生答案、正确答案与解析；查询失败时显性提示错误。

#### Scenario: 已发布考试展示逐题回顾入口

GIVEN 当前选定考试的成绩状态为已发布（`view.kind === 'published'`）且为常规成绩口径

WHEN 查分页渲染

THEN 页面展示「逐题回顾」按钮

#### Scenario: 未发布或复核中考试不出入口

GIVEN 当前选定考试为成绩未发布（400「成绩待发布」）或处于复核中（`view.kind === 'reviewing'`）

WHEN 查分页渲染

THEN 页面不渲染「逐题回顾」按钮

#### Scenario: 单场逐题回顾弹层展示全题明细

GIVEN 学生点击「逐题回顾」按钮

WHEN 接口 `GET /api/scores/my/exams/{examId}/review` 成功返回

THEN 弹层展示该场考试的卷面题目列表

AND 每道题渲染题号、题型、题干、我的答案、正确答案、得分、满分与解析

AND 解析为空时同样展示「暂无解析」占位
