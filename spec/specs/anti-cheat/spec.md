# anti-cheat 规范

> 能力域：防作弊（阶段 7，W8）。
> 来源：`spec/changes/add-anti-cheat` 合入（行为事件采集、事件严重度分级、切屏处理策略、行为日志时间线、监考大屏）。

## Requirements

### Requirement: 行为事件采集

WHEN 学生考试中发生可疑行为,

系统 SHALL 以统一事件模型采集切屏、失焦、刷新等事件，新增事件类型 SHALL 不改变采集核心。

#### Scenario: 采集切屏事件

GIVEN 学生考试中切出页面

WHEN 系统检测到切屏

THEN 采集切屏事件落库

AND 记录事件类型与时间

---

#### Scenario: 新增事件不动核心

GIVEN 需要新增一种行为事件类型

WHEN 开发者添加事件实现

THEN 无需修改采集核心逻辑

---

### Requirement: 事件严重度分级

WHEN 行为事件被采集,

系统 SHALL 分配严重度（低/中/高），切屏次数达到阈值 SHALL 提升严重度。

#### Scenario: 切屏超过阈值

GIVEN 学生切屏次数达到配置阈值

WHEN 系统判定

THEN 事件严重度提升

AND 记录升级标记

---

### Requirement: 切屏处理策略

WHEN 学生切屏,

系统 SHALL 仅警告并记录，不强制交卷。

#### Scenario: 切屏警告不交卷

GIVEN 学生考试中切屏

WHEN 系统检测到切屏

THEN 弹出警告提示

AND 记录行为日志

AND 不强制交卷

---

### Requirement: 行为日志时间线

WHEN 教师查看行为日志,

系统 SHALL 提供按考试、学生、事件类型、严重度筛选的时间线，且 SHALL 仅教师与管理员可见。

#### Scenario: 教师查看时间线

GIVEN 教师进入某考试行为日志

WHEN 教师按学生筛选

THEN 系统按时间顺序展示该学生行为轨迹

---

#### Scenario: 学生不可见

GIVEN 学生尝试访问行为日志

WHEN 学生发起查询

THEN 系统返回 403 拒绝

---

### Requirement: 监考大屏

WHEN 教师进入监考大屏,

系统 SHALL 展示在线/离线/已交卷人数、答题进度，并 SHALL 高亮异常行为。

#### Scenario: 实时状态展示

GIVEN 考试进行中有学生在线/离线/已交卷

WHEN 教师打开监考大屏

THEN 系统展示各类人数与进度

AND 异常行为学生被高亮

---

#### Scenario: 查看异常详情

GIVEN 监考大屏高亮某学生异常

WHEN 教师点击该学生

THEN 系统展示该学生行为时间线
