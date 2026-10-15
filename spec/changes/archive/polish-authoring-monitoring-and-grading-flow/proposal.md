# 变更提案：polish-authoring-monitoring-and-grading-flow

## 为什么（Why）

教师侧三个高频工作流存在明显的操作摩擦，均为成熟在线考试产品的标配体验：

1. **题目快速复制缺失**：
   - 题库列表操作列目前仅有「编辑」与「删除」（`questions/index.page.vue` 操作列）；
   - 教师录题时常需要「同骨架改细节」的变体题（同选项结构换数字、同题型换题干），目前必须从空白表单逐字段重录；
   - 试卷克隆已有（`add-paper-template-clone`），但题目粒度的复制另存一直缺位。

2. **监考大屏筛选与刷新控制缺失**：
   - 监考面板（`ExamMonitorPanel.vue`）四个指标卡（已进入/在线/离线/异常）仅作展示，不可点击联动；
   - 大班监考时教师想快速定位「离线学生」或「异常学生」必须肉眼逐行扫描学生表；学生表无任何搜索框；
   - 轮询固定 10s（`constants/monitor.ts`），教师怀疑数据陈旧时无法手动立即刷新，也无「下次轮询倒计时」的透明度。

3. **主观题批改打分交互低效**：
   - 主观题批改面板（`SubjectiveGradingPanel.vue`）当前交互为「InputNumber 输分 → Input 输评语 → 鼠标点击提交」，每名学生需要一次鼠标往返；
   - 大班逐题批改（如 100 人 × 3 道简答）的键盘流水线（Enter 提交并自动跳下一位）、快捷给分（满分/及格/零分）与常用评语模板均缺位，而既有基建（服务端分页、姓名筛选、只看未批改、乐观锁冲突流）已完全就绪。

本案在**严格零 DDL、零后端改动、零契约变更**前提下，以纯前端交互增强打通三处降阻点。

---

## 改什么（What）

### 1. 题目快速复制另存（Copy Question As New）
- **落点**：`frontend/src/pages/(dashboard)/teacher/questions/index.page.vue` 操作列 + `frontend/src/components/question/QuestionForm.vue`；
- **交互**：题库列表每行新增「复制」操作；点击后 `QuestionForm` 以「复制另存」模式打开——全部字段（题型/题干/题干图片 URL/选项与正确答案/判断答案/参考答案/可接受答案/默认分值/难度/标签/解析）经既有 `draftFromResponse` 深拷贝回填，题干前缀「（副本）」；
- **提交口径**：复制模式提交 SHALL 走既有新建端点（`create1`，POST /api/questions），**不走** `update`；源题零影响；
- **实现形态**：`QuestionForm` 增加可选 prop（如 `copyFrom`），与既有 `question`（编辑模式）互斥判定；`isEdit` 判定逻辑保持「`question?.id !== undefined`」语义不变，复制模式不视为编辑；
- **已知边界（如实注记）**：复制打开时既有题干查重预检（`add-question-duplicate-detection`）会以 100% 相似度命中原题——这是「知情防呆不阻断」的既有设计行为，教师知情后仍可保存，不为此做特殊豁免。

### 2. 监考大屏多维筛选与手动倒计时刷新（Monitor Filtering & Refresh Control）
- **落点**：`frontend/src/components/exam/ExamMonitorPanel.vue` + `frontend/src/pages/(dashboard)/teacher/exams/[id].page.vue`（监考页签接线处）；
- **多维筛选（客户端本地过滤，零后端请求）**：
  - 学生表上方新增「姓名 / 学生 ID」搜索框，本地过滤 `overview.students`；
  - 顶部指标卡「在线」「离线」「异常」三卡可点击，作为 Filter Tag 联动过滤学生表（在线/离线对应 `status` 字段，异常对应 `abnormal` 字段），再次点击取消过滤；
  - 过滤为纯展示层行为，**不改变**「人数与进度全部来自 overview、前端不加总不推算」的既有不变式（`useExamMonitor.ts` 四条不变式之 3）。
- **手动刷新与倒计时**：
  - 工具栏新增「立即刷新」按钮，触发监考 useQuery 的 `refetch`，loading 态反馈；
  - 显示「下次刷新倒计时」，间隔 SHALL 引用 `constants/monitor.ts` 的 `MONITOR_POLLING_INTERVAL_MS`（不得在页面写字面量）；
  - 页签门控（`isMonitorConsumerTab`）与浏览器可见性门控（hidden 暂停轮询、恢复可见立即拉取）行为不变。

### 3. 主观题 Enter 流水线打分与快捷评语模板（Grading Keyboard Pipeline）
- **落点**：`frontend/src/components/postexam/SubjectiveGradingPanel.vue`；
- **Enter 流水线**：
  - 分数输入框按 Enter SHALL 触发当前行提交，**复用既有 `createGradingFlow` 提交流**（乐观锁 409/1012 冲突处理、行级错误归位语义不变），不得绕过 flow 直调端点；
  - 提交成功后自动聚焦下一行（按当前筛选与分页的行序）的分数输入框；
  - 提交失败或冲突时**不移动焦点**——教师必须处理当前行错误；
- **快捷给分 Tag**：打分区提供「满分」「及格」「零分」三个快捷 Tag（按本题满分 `question.score` 派生：满分 = score、及格 = 60% 四舍五入到 0.5 步长、零分 = 0），点击即填入分数输入框；
- **常用评语模板**：提供常用评语模板气泡（如「回答完整，要点齐全」「思路正确，表述欠完整」「未答到关键要点」），点击填入评语输入框，教师可继续编辑。

---

## 影响面（Impact）
- **数据库与后端**：**严格零 DDL**（schema.sql 与 docker/mysql/migrations 零 diff）、零后端改动（src/main、src/test、pom.xml 零 diff）；
- **契约与 SDK**：零新增端点（复制走既有 POST /api/questions、批改走既有 saveSubjectiveScore、监考走既有 monitor/overview），openapi paths 不变，SDK 不再生成；
- **门禁基线**：仅前端 vitest 用例数增长（收口笔上调 gate-baseline.json 的 frontendTests；backendTests 与 openapiPaths 不变）；
- **既有不变式保全**：监考「前端不推算」不变式、批改「冲突不静默覆盖」不变式、轮询「间隔取自 constants/monitor.ts」词法护栏均不得破坏；
- **分级**：M 级（多组件多特性面 + 批改流交互安全），双岗隔离（实施子 Agent 自测 + 独立复核 Agent），收口由主 Agent 自动化执行。

---

## 时间线与任务
- **阶段 0 & 1**：立项三件套编制与在途总账登记；
- **阶段 2**：实施三特性（题目复制另存、监考筛选与刷新、批改流水线）、前端单测先红后绿与变异击红留证；
- **阶段 3**：全量双端门禁实测、gate-baseline 上调（仅 frontendTests）、规范合入与收口归档、`--no-ff` 合并入 main。
