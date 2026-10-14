# spec-delta：add-paper-template-clone

涉及域：question-bank、frontend。

## 域：question-bank

### Requirement: 试卷克隆

WHEN 教师克隆本人任意非删除试卷,

系统 SHALL 提供 `POST /api/papers/{id}/clone`（paper:manage + owner 归属）：复制试卷元信息与全部题目关联（题目/顺序/分值覆盖）为**新草稿卷**（status=草稿、snapshotId 空，无论源卷是否已锁定），标题加「（副本）」后缀，新卷 owner=操作者；对模板卷调用本端点即为「从模板一键建卷」。源卷及其快照 SHALL 零影响；非 owner SHALL 403。

#### Scenario: 克隆全量复制与草稿产物

GIVEN 本人一张含若干题目（含分值覆盖与排序）的试卷

WHEN 调用 clone

THEN 新卷存在且为草稿态（未锁定、无快照）、标题带（副本）后缀、题目集合/顺序/分值覆盖与源卷逐项一致、owner 为操作者

#### Scenario: 克隆锁定卷得可编辑草稿

GIVEN 本人一张已锁定（已生成快照）的试卷

WHEN 调用 clone

THEN 产物为可编辑草稿卷，源卷保持锁定态且其快照内容零变化

#### Scenario: 非归属拒绝

GIVEN 教师 B 对教师 A 的试卷调用 clone 或 save-as-template

THEN 返回 403，不产生任何复制

---

### Requirement: 另存为模板

WHEN 教师将本人非删除试卷另存为模板,

系统 SHALL 提供 `POST /api/papers/{id}/save-as-template`（paper:manage + owner）：复制该卷为新模板卷（is_template=1、草稿态语义、副本后缀同 clone），原卷及其状态 SHALL 保留不变；对模板卷再调用本端点 SHALL 被拒（无意义嵌套）。

#### Scenario: 另存为模板原卷保留

GIVEN 本人一张试卷（任意状态）

WHEN 调用 save-as-template

THEN 产生一张 is_template=1 的模板卷，原卷状态与内容零变化

#### Scenario: 模板不可再另存为模板

GIVEN 一张模板卷

WHEN 对其调用 save-as-template

THEN 返回可观察错误，不产生新模板

---

### Requirement: 模板守卫

WHEN 模板卷进入考试选卷或快照路径,

系统 SHALL 拒绝：模板卷 SHALL NOT 被选为考试卷（ExamService 建考试校验追加模板拒绝，不产生 exam 行）、SHALL NOT 生成试卷快照（PaperSnapshotService 追加拒绝）；两处守卫为追加校验，既有行为对非模板卷 SHALL 零漂移（既有试卷/考试/快照用例全绿护栏）。

#### Scenario: 模板不可被选为考试卷

GIVEN 一张模板卷

WHEN 教师以其为 paperId 创建考试

THEN 返回可观察错误且不产生考试

#### Scenario: 模板不可生成快照

GIVEN 一张模板卷

WHEN 请求生成快照

THEN 返回可观察错误，papers.status 与 snapshotId 零变化

#### Scenario: 非模板卷行为零漂移

GIVEN 本案合入前的既有试卷/考试/快照用例数据

WHEN 执行既有用例

THEN 与既有基线一致（is_template 默认 0 使非模板卷全链路行为不变）

---

### Requirement: 试卷列表模板视图

WHEN 教师查询试卷列表,

既有分页端点 SHALL 新增 isTemplate 过滤参数（默认 false）：默认视图 SHALL 不含模板卷，isTemplate=true SHALL 仅返回模板卷（模板库视图）；不传参的既有调用方行为 SHALL 零变化；模板可被编辑与删除（沿用试卷既有编辑/删除语义与 owner 校验）。

#### Scenario: 默认视图零漂移与模板视图

GIVEN 库内存在普通试卷与模板卷各若干

WHEN 不带参数查询分页

THEN 仅返回普通试卷，与既有基线一致

AND isTemplate=true 时仅返回模板卷

## 域：frontend

### Requirement: 试卷模板与克隆界面

WHEN 教师在试卷管理页操作模板与克隆,

系统 SHALL 在试卷列表操作列提供「克隆」「另存为模板」按钮；SHALL 提供「模板库」Tab（模板列表：使用〔从模板建卷〕/编辑/删除操作）；克隆与另存成功后 SHALL 有可观察反馈；零新依赖对齐既有风格。

#### Scenario: 克隆与另存为模板入口

GIVEN 试卷列表存在本人试卷

WHEN 点击克隆或另存为模板

THEN 产生新卷/新模板且列表可见（模板在模板库 Tab 可见）

#### Scenario: 模板库使用入口

GIVEN 模板库 Tab 存在至少一张模板

WHEN 点击使用

THEN 以该模板为源创建新草稿卷并跳转/提示，模板本身不动
