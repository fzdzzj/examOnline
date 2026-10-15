# spec-delta: exam-management (harden-exam-paper-and-practice-boundaries)

## MODIFIED Requirements

### Requirement: 考试创建与修改时间窗校验

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
