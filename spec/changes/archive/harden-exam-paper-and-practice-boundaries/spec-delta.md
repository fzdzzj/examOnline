# spec-delta: harden-exam-paper-and-practice-boundaries

## 1. question-bank

### Requirement: 试卷克隆 (add-paper-template-clone)

#### Scenario: 试卷克隆产物标题超长安全截断 (ADDED)
- **WHEN**: 教师克隆本人一张试卷，其原标题长度使得追加「（副本）」后总长度超过 128 字符（`papers.title VARCHAR(128)`）
- **THEN**: 系统 SHALL 自动截断原标题，使得「截断后的原标题 + （副本）」的总长度严格等于 128 字符，绝不因字段长度溢出而抛出数据库异常或截断失败

---

## 2. exam-management

### Requirement: 考试创建

#### Scenario: 作答时长不能大于考试时间窗跨度 (ADDED)
- **WHEN**: 教师创建或更新考试时传入的 `durationMinutes` 大于由 `startTime` 与 `endTime` 计算的时间窗总分钟数
- **THEN**: 系统 SHALL 拒绝该操作，返回 HTTP 400，错误信息明确说明作答时长不能大于考试时间窗跨度

#### Scenario: 迟到容忍时长不能大于或等于作答时长 (ADDED)
- **WHEN**: 教师创建或更新考试时传入的 `allowLateMinutes` 大于或等于 `durationMinutes`
- **THEN**: 系统 SHALL 拒绝该操作，返回 HTTP 400，错误信息明确说明迟到容忍分钟数不能大于或等于个人作答时长

### Requirement: 防作弊配置语法校验

#### Scenario: 建考与改考防作弊 IP 白名单格式校验 (ADDED)
- **WHEN**: 教师创建或更新考试时提交的 `antiCheatConfig` 中包含 `ipWhitelist`
- **THEN**: 系统 SHALL 逐项校验白名单中的字符串是否符合合法 IP（单 IPv4/IPv6、合法通配符、有效 CIDR）
- **AND**: 若开启了 `enableIpWhitelist=true` 但 `ipWhitelist` 为空，系统 SHALL 返回 HTTP 400 提示白名单不能为空
- **AND**: 若存在任一项非法 IP，系统 SHALL 拒绝该请求，返回 HTTP 400 并明确指出非法项

### Requirement: 考试预约前置门禁

#### Scenario: 非预约制考试拒绝预约与取消预约 (ADDED)
- **WHEN**: 学生对 `reservationRequired != 1` 的普通考试调用预约端点 `POST /api/exam-reservations/{id}` 或取消端点 `DELETE /api/exam-reservations/{id}`
- **THEN**: 系统 SHALL 拒绝该操作，返回 HTTP 400，错误提示明确说明该考试非预约制考试

---

## 3. score-management

### Requirement: 错题重练与消灭计划 (add-wrong-question-practice)

#### Scenario: 原题被软删除后从考试快照平滑回退判分 (ADDED)
- **WHEN**: 学生提交重练题目作答（`POST /api/scores/my/wrong-questions/practice/submit`），且该题目在题库中已被教师逻辑软删除（`is_deleted=1`）
- **THEN**: 系统 SHALL 自动从该学生所属且包含该题的已发布考试快照（`ExamSnapshot`）中装配该题目完整的类型、选项与标准答案，并基于既有客观题判分策略执行同源判分
- **AND**: 判分结果正常记录 `practice_attempt` 明细、连对次数与消灭状态正常更新，响应返回该题的正确答案与解析，保证历史错题练习链路不中断
