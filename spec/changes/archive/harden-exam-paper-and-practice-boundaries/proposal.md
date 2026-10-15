# Proposal: 核心业务合理性、边界溢出防御与快照韧性治理 (harden-exam-paper-and-practice-boundaries)

## 1. 为什么（Why）

本项目经过多次迭代，核心能力（题库、组卷、考试、防作弊、成绩分析、错题练习）已全面建成。但在全仓边界巡查中，发现了 5 处关于业务合理性、边界溢出与快照数据韧性的关键缺陷：

1. **试卷克隆与模板复制标题溢出风险（边界溢出）**：`PaperService.duplicate` 直接拼接 `+ "（副本）"`，若原标题接近 128 字符上限（`papers.title` 为 `VARCHAR(128)`），拼接后突破 128 字，导致数据库截断或抛出 SQL 异常；
2. **建考时间窗、时长与迟到合理性缺失（业务合理性）**：`ExamService.validateWindowAndDuration` 仅校验 `end > start` 与 `duration > 0`。若 `durationMinutes` 大于时间窗总分钟数，学生永远无法用完整张试卷的时长；若 `allowLateMinutes` 大于等于时长或时间窗，迟到学生进场即结束或无可作答时间；
3. **建考防作弊 IP 白名单语法格式前置校验缺失（防呆与全场防误伤）**：教师建考若配置了脏格式 IP（如拼写错误 `192.168.1.300` 或非法 CIDR），后端保存时未校验。开考后白名单内无任何有效 IP 项，导致全场学生进考时被 `assertIpAllowed` 统一 403 拒之门外；
4. **非预约制考试预约接口越权与防呆缺失（状态边界）**：`ExamReservationService.requireReservable` 未校验 `reservation_required == 1`，普通免预约考试可被任意调用预约接口写入脏数据；
5. **错题重练原题软删除后 404 崩溃（快照隔离与韧性）**：错题本从不可变的考试快照加载错题，但重练提交端点 `submitPractice` 直查可变题库 `questionMapper.selectById`。若原题在考后被教师软删除，学生点击重练判分即报 404 崩溃，破坏了快照隔离的一致性。

本提案针对上述 5 项痛点进行集中加固与闭环。

---

## 2. 改什么（What）

### 2.1 试卷克隆与模板标题 128 字符安全截断 (`PaperService`)
- 在 `duplicate` 时，引入标题最大长度限制 `TITLE_MAX = 128`；
- 若 `source.getTitle() + "（副本）"` 超过 128 字符，自动截断原标题使其拼接后严格为 128 字符，绝不引发 SQL 字段溢出异常。

### 2.2 建考与改考时间窗、时长与迟到合理性强校验 (`ExamService`)
- `validateWindowAndDuration` 增强：
  - 计算时间窗总跨度：`windowMinutes = Duration.between(start, end).toMinutes()`；
  - 作答时长不能大于时间窗跨度：若 `durationMinutes > windowMinutes`，抛出 400，文案形如 `作答时长({duration}分钟)不能大于考试时间窗跨度({window}分钟)`；
  - 迟到容忍不能大于或等于个人作答时长：若 `allowLateMinutes != null && allowLateMinutes >= durationMinutes`，抛出 400，文案形如 `迟到容忍分钟数({allowLate}分钟)不能大于或等于个人作答时长({duration}分钟)`。

### 2.3 建考与改考防作弊 IP 白名单格式校验 (`ExamService`)
- 在创建与更新考试处理 `antiCheatConfig` 时：
  - 若 `enableIpWhitelist` 为 true，校验 `ipWhitelist` 数组非空；
  - 无论 `enableIpWhitelist` 是否开启，若包含 `ipWhitelist`，复用 `IpMatcher.parse` 校验列表中每一项 IP 格式（支持单 IPv4/IPv6、合法通配符、有效 CIDR）。若存在非法项，立即抛出 400 并明确指示哪一项非法（如 `IP 白名单包含非法项: {item}`）。

### 2.4 非预约制考试预约接口前置门禁 (`ExamReservationService`)
- 在 `requireReservable` 与 `cancel` 中增加判定：
  - 若 `exam.getReservationRequired() == null || exam.getReservationRequired() != 1`，抛出 400，文案 `该考试非预约制考试，无需预约`（取消端点文案对齐为 `该考试非预约制考试，无需取消预约`）。

### 2.5 错题重练原题软删除平滑回退考试快照判分 (`StudentPracticeService`)
- 在 `submitPractice` 中：
  - 先尝试查题库 `questionMapper.selectById(questionId)`；
  - 若查无结果（原题已在题库中被逻辑删除），自动从该学生所属且包含该题的已发布考试快照（`ExamSnapshot`）中加载快照级 `GradingQuestion`；
  - 复用既有 `GradingStrategyRegistry` 进行同源判分，返回快照中的正确答案与解析，保证学生的历史错题重练、连对进度与消灭计划不受题库物理变动影响；
  - 仅当题库查不到且所有考试快照中均无此题时才抛 404。

---

## 3. 影响能力域与规范

- `question-bank`：试卷克隆与另存为模板的标题长度防御规范；
- `exam-management`：考试创建/更新的合理性校验规范、防作弊配置入库语法校验、预约制接口合法性门禁；
- `score-management`：错题重练的原题软删除快照降级判分规范。

---

## 4. 零 DDL 与红线声明

- **零 DDL**：未新增、修改任何表结构，未改动 `schema.sql`，不添加任何 `docker/mysql/migrations/` 脚本；
- **零新端点 / 契约稳定**：`openapi.yaml` 既有 89 个路径与 schema 保持稳定；
- **防作弊只告警不强制交卷**：严格维持系统既有红线；
- **双端门禁只增不减**：门禁基线保持锁定且新增覆盖用例。

---

## 5. 变异验证计划

- **m1（试卷克隆防溢出）**：将标题截断逻辑移除，针对 128 字符标题克隆，确认触发变异红灯；
- **m2（作答时长超过时间窗校验）**：抽掉 `durationMinutes > windowMinutes` 校验，确认边界测试变红；
- **m3（建考 IP 格式校验）**：抽掉建考时的 `IpMatcher.parse` 格式校验，确认非法 IP 建考用例变红；
- **m4（非预约制考试预约门禁）**：抽掉 `reservation_required == 1` 校验，确认非预约考试预约用例变红；
- **m5（错题重练软删降级）**：抽掉快照回退逻辑（原样直报 404），确认原题软删后的重练用例变红。
