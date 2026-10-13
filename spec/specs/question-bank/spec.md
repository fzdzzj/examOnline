# question-bank 规范

> 能力域：题库与组卷（阶段 3，W2-W3）。
> 来源：`spec/changes/add-question-bank` 合入（题目管理、答案归一化、标签体系、手动组卷、标签随机抽题、抽题锁定与试卷快照）；
> `spec/changes/archive/add-paper-batch-add-questions` 合入（批量加题入卷，2026-10-02）。

## Requirements

### Requirement: 题目管理

WHEN 教师操作题目,
系统 SHALL 支持单选、多选、判断、简答四类题型的创建、查询、更新与软删除。

#### Scenario: 创建四种题型题目

GIVEN 教师已登录且具备题库操作权限
WHEN 教师创建单选题、多选题、判断题或简答题
THEN 系统创建对应题目
AND 记录题目类型、内容、选项、正确答案与分值

#### Scenario: 软删除题目

GIVEN 存在一道已被引用或未被引用的题目
WHEN 教师删除该题目
THEN 系统标记 is_deleted 而非物理删除
AND 题目不参与后续组卷

#### Scenario: 非所有者越权操作被阻止

GIVEN 教师 A 试图修改教师 B 的题目
AND 教师 A 不具备管理员权限
WHEN 教师 A 发起修改请求
THEN 系统返回 403 Forbidden
AND 不执行任何变更

#### Scenario: 创建单空填空题

GIVEN 教师已登录且具备题库操作权限
WHEN 教师创建 type=5 单空填空题，题干含 `____` 空位占位，可接受答案列表非空（≥1 项）、各项不含 `|`、总长 ≤512
THEN 系统创建该填空题
AND 响应回显归一化后的答案串

#### Scenario: 填空题答案校验拒绝

GIVEN 教师提交的填空题答案不合法
WHEN 可接受答案列表为空、或按 `|` 拆分后无有效项、或归一化后答案串超长
THEN 服务端返回 400 并带明确 message，不落库
AND 前端录入边界拒绝单项含 `|`（`questionTypeConfig` 预校验）；服务端线格式为 `|`-joined 单串、`|` 为分隔符本身故不可判别单项含 `|`，服务端侧为空列表 / 拆分后无有效项 / 超长 400

#### Scenario: 填空题编辑与软删除

GIVEN 一道已存在的填空题
WHEN 教师编辑或删除该题
THEN 编辑与软删除语义与既有题型一致，编辑可增删可接受答案项

#### Scenario: 题库列表按 type=5 筛选

GIVEN 题库中存在填空题
WHEN 教师按题型 5 筛选题库列表
THEN 既有筛选链路对 5 透明生效，返回填空题

#### Scenario: 上传题干图片

GIVEN 教师具备 question:manage 权限
WHEN 教师通过 POST /api/question-images 上传题干图片（单文件）
THEN 服务端校验扩展名白名单（png/jpg/jpeg/gif/webp）+ 魔数校验防伪装 + 单文件 ≤5MB
AND 校验通过后服务端生成 UUID 文件名存储，不采纳客户端文件名
AND 存储成功返回 `{url}`（相对 URL `/images/{uuid}.{ext}`）

#### Scenario: 上传校验拒绝

GIVEN 教师上传题干图片但文件不合法或超限
WHEN 非白名单扩展名 / 魔数不匹配 / 超大文件
THEN 均返回 4xx 并带明确 message，不落盘
AND 无 question:manage 权限返回 403

#### Scenario: 题干图片下载

GIVEN 任意请求方访问 GET /images/{filename}
WHEN 该资源存在且文件名通过白名单正则
THEN 返回图片内容并按扩展名回 Content-Type
AND 文件名白名单正则防路径穿越，查无文件返回 404
AND 公开只读（随机文件名即凭据，不可枚举）
AND 既有快照消费方（判分/导出/监考）对快照新增 imageUrl 字段零回归

#### Scenario: 建题带图

GIVEN 教师创建或编辑题目
WHEN 请求携带可选的 imageUrl
THEN imageUrl 以 `/images/` 开头或为空（≤512），任意外链 URL 返回 400
AND 与题型正交，五种题型均可选配
AND 响应回显 imageUrl

#### Scenario: 组卷快照携带图片

GIVEN 试卷快照/考试快照/个人快照中的题目对象
WHEN 序列化
THEN 携带 imageUrl（无图题目为 null），既有字段映射行零改动
AND 既有无图题目全链路（组卷/发布/拉卷/判分/回顾）逐字零回归

---

### Requirement: 批量删除题目

系统 SHALL 提供批量删除题目端点（`POST /api/questions/batch-delete`），接受题目 ID 列表，SHALL 以逐题结果信封语义执行：逐题校验（存在性、归属、考试引用锁定）收集失败项及原因，合规子集单事务软删除；任一题校验失败 SHALL NOT 阻止其余合规题目的删除（部分成功），且结果 SHALL 逐题如实区分成功与失败，不得将部分成功伪装成全部成功。

#### Scenario: 部分成功

GIVEN 批量列表混有合规题目、不存在或已软删题目、非归属教师题目、被进行中考试引用题目
WHEN 教师调用批量删除
THEN 合规子集被软删除（is_deleted 标记，历史试卷/快照引用不受影响），其余逐题返回失败原因（文案与单题删除同类语义），响应含 succeeded 与 failed 列表

#### Scenario: 全部成功

GIVEN 教师以本人全部合规题目调用批量删除
WHEN 请求执行
THEN 全部题目软删除，failed 为空列表，题库列表刷新后不再展示

#### Scenario: 全部失败零删除

GIVEN 批量列表全部题目均校验失败
WHEN 教师调用批量删除
THEN 零题目被删除，succeeded 为空数组，逐题失败原因返回（不报整体错误）

#### Scenario: 请求校验拒绝

GIVEN 列表为空、超过单次上限（100）或含重复题目 ID
WHEN 请求到达服务端
THEN 返回 400（空/超限/「请求内存在重复题目」），零删除

#### Scenario: 单题删除端点语义不变

GIVEN 既有单题删除端点 DELETE /api/questions/{id}
WHEN 批量端点上线后
THEN 单题端点校验链（owner + 考试锁定 + 逻辑删除）与失败文案原样保留，行为零改动

---

### Requirement: 答案归一化

WHEN 题目答案被存储,
系统 SHALL 以统一格式存储客观题答案；判断题 SHALL 使用 T/F，多选 SHALL 使用有序选项。

#### Scenario: 判断题答案归一化

GIVEN 教师录入判断题答案（如"正确"、"对"、"A"）
WHEN 系统存储
THEN 答案统一归一化为 T 或 F

#### Scenario: 多选题答案归一化

GIVEN 教师录入多选题答案
WHEN 系统存储
THEN 答案以有序选项列表存储
AND 消除顺序歧义

#### Scenario: 填空答案归一化

GIVEN 教师录入填空题的可接受答案列表（多个可接受答案以 `|` 分隔）
WHEN 系统存储
THEN 归一化 trim 首尾空白后按 `|` 拆分、各项再 trim、空项剔除，并以 `|` 重组为归一化串
AND 学生侧答案仅 trim 不做其他变换

#### Scenario: 既有四题型归一化零回归

GIVEN 单选、多选、判断、简答四类题型的答案
WHEN 系统存储
THEN 四类题型的归一化行为逐字不变

---

### Requirement: 标签体系

WHEN 教师管理标签,
系统 SHALL 提供学科、难度、题型、自定义四类扁平标签，且题目 SHALL 可多选关联多个标签。

#### Scenario: 题目多标签关联

GIVEN 存在学科与难度标签
WHEN 教师为主题目关联标签
THEN 题目可同时关联多个标签
AND 支持按标签筛选题目

---

### Requirement: 手动组卷

WHEN 教师手动组卷,
系统 SHALL 支持将题目加入试卷、调整题号顺序，并 SHALL 允许在试卷内覆盖题目的默认分值。

#### Scenario: 组卷与分值覆盖

GIVEN 一道默认 5 分的题目
WHEN 教师将其加入试卷并设置本题分值 10 分
THEN 试卷内该题分值为 10
AND 题目默认分值仍为 5（互不影响）

#### Scenario: 总分校验

GIVEN 试卷各题分值之和与试卷总分不一致
WHEN 教师保存试卷
THEN 系统校验失败并提示调整

---

### Requirement: 批量加题入卷

系统 SHALL 提供批量加题入卷端点（`POST /api/papers/{id}/questions/batch`），接受题目 ID 列表（每项可携带可选分值覆盖），SHALL 以整批单事务全有全无语义执行：任一题目失败则整批回滚，不得出现半批入卷。

#### Scenario: 批量入卷成功

GIVEN 教师本人的一张草稿试卷已含若干题目
WHEN 教师以题目 ID 列表调用批量入卷（部分项携带分值覆盖、部分项缺省）
THEN 全部题目按提交顺序追加到试卷末尾、题号连续接续，缺省分值项使用题目默认分、显式分值项在试卷内覆盖默认分，返回更新后的试卷详情

#### Scenario: 请求内重复拒绝

GIVEN 教师以含重复题目 ID 的列表调用批量入卷
WHEN 请求到达服务端
THEN 返回 400「请求内存在重复题目」，试卷零变更

#### Scenario: 卷内已有题目整体回滚

GIVEN 批量列表中混入一题已在试卷中的题目
WHEN 教师调用批量入卷
THEN 返回与单题加题相同的「该题目已在试卷中」业务错误，整批回滚，试卷题目数与内容零变更

#### Scenario: 含不存在或已软删题目整体回滚

GIVEN 批量列表中混入一题不存在或已软删的题目
WHEN 教师调用批量入卷
THEN 返回 404「题目不存在或已删除」，整批回滚

#### Scenario: 锁定试卷拒绝批量入卷

GIVEN 试卷已生成快照（锁定）或被进行中考试绑定
WHEN 教师调用批量入卷
THEN 按既有锁定口径拒绝（与单题加题同文案），零变更

#### Scenario: 越权与边界约束与单题一致

GIVEN 非归属教师（非 ADMIN）或列表为空或超过单次上限
WHEN 调用批量入卷
THEN 越权按既有 403/404 口径拒绝；空列表或超上限返回 400，且单次批量至多 100 项

---

### Requirement: 标签随机抽题

WHEN 教师按规则随机抽题,
系统 SHALL 按标签/难度/题型比例抽取指定数量题目，并在题库容量不足时 SHALL 提示教师调整。

#### Scenario: 按规则抽题成功

GIVEN 题库中存在满足标签/难度/题型条件的题目
WHEN 教师设置抽取条件与数量
THEN 系统返回满足条件的随机题目

#### Scenario: 题库容量不足

GIVEN 满足条件的题目数量少于抽取数量
WHEN 教师发起抽题
THEN 系统提示教师调整条件或数量

---

### Requirement: 抽题锁定与试卷快照

WHEN 抽题结果确定,
系统 SHALL 生成试卷快照（含题目/答案/分值/顺序），之后读取 SHALL 以快照为准，刷新 SHALL 不改变题目与顺序。

#### Scenario: 快照锁定

GIVEN 一次抽题已完成并生成快照
WHEN 教师或学生刷新页面
THEN 读取到与首次一致的题目与顺序
AND 不重新抽题

#### Scenario: 题目变更不影响快照

GIVEN 试卷快照已生成
AND 之后某题目被修改或软删除
WHEN 读取快照
THEN 快照内容保持不变
AND 历史组卷结果不受影响

---

> 合入注记（2026-10-02，`add-paper-batch-add-questions` 归档）：上方「批量加题入卷」Requirement 为本卡新增——
> 立项依据是后端能力缺口（手动组卷仅有单题入卷端点，前端组卷页 `onPick` 逐题串行 N 次请求且循环无
> try/catch，中途失败即中断、此前已入卷题目保持已入卷且无部分成功提示，半批静默）。实现为
> `POST /api/papers/{id}/questions/batch`（`PaperService.addQuestions` 整批 `@Transactional(rollbackFor
> = Exception.class)` 循环复用 `addQuestionInternal`，与 `commitRandomDraw` 同构；请求内重复 questionId
> 前置显式校验 400；单项失败语义与单题端点同文案），前端 `onPick` 改单次批量调用 + try/catch。
> 单题端点、`addQuestionInternal` 内部逻辑、`updateMeta` 总分校验口径零改动；逐题查重的查询数优化显式
> 不做、另立卡。证据：`spec/changes/archive/add-paper-batch-add-questions/evidence/`。

---

> 合入注记（2026-10-02，`add-question-batch-delete` 归档）：上方「批量删除题目」Requirement 为本卡新增——
> 立项依据是后端能力缺口（题库仅有单题软删端点 `DELETE /api/questions/{id}`，前端题库页 `onBatchDelete`
> 逐题串行 N 次请求）。实现为 `POST /api/questions/batch-delete`（`QuestionService.batchDelete` 纯新增
> `@Transactional(rollbackFor = Exception.class)` 方法：请求内重复前置显式校验 400「请求内存在重复题目」
> 与批量入卷同口径；`selectBatchIds` 一次取回逐题校验收集 failed——逻辑删除过滤使不存在/已软删同为
> 404「题目不存在」、403/400 直接复用 `OwnershipGuard.assertOwner` 与 `ExamPaperLockService.assertQuestionEditable`
> 抛出的既有文案与单删路径逐字一致；合规子集逐题 `deleteById` 软删），逐题结果信封
> `{succeeded, failed:[{id, reason}]}`（全部失败 succeeded 空数组仍 200，部分成功不伪装成全部成功）；
> 前端 `onBatchDelete` 改单次批量调用 + 信封解析（全成「已删除 N 道题目」/部分失败「删除完成：成功 N 题，
> 失败 M 题」+ 失败项逐条 reason，成功后刷新列表与选中态）。单题端点与 `softDelete` 既有方法体、
> `schema.sql` 零改动；批量校验的查询数优化显式不做（与批量入卷同裁决，另立卡）。证据：
> `spec/changes/archive/add-question-batch-delete/evidence/`。
