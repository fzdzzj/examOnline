# 规范增量：add-anti-cheat-restrictions

## 能力域：anti-cheat

### Requirement: 考试 IP 范围准入校验

WHEN 学生进入某场考试,
系统 SHALL 校验客户端真实 IP 是否在考试配置的 IP 白名单范围内；若未配置白名单则放行；若配置了白名单且学生 IP 不在其中，系统 SHALL 返回 403 明确拒绝。

#### Scenario: 白名单内 IP 允许进入考试

GIVEN 考试配置了 IP 白名单（如 CIDR `192.168.1.0/24` 或通配符 `192.168.1.*`）
WHEN 学生从符合该网段的客户端发起进入考试请求
THEN 准入校验通过，正常生成或回读答卷

#### Scenario: 白名单外 IP 拦截并返回 403

GIVEN 考试配置了 IP 白名单
WHEN 学生从非白名单范围的 IP 发起进入考试请求
THEN 系统返回 403 拒绝，响应包含明确原因「当前 IP ({ip}) 不在本次考试允许的网段范围内」
AND 不创建新答卷

#### Scenario: 未配置 IP 白名单全网放行

GIVEN 考试未配置 IP 白名单或配置为空列表
WHEN 学生发起进入考试请求
THEN 保持既有普通准入校验（班级/补考名单），IP 不作限制

---

### Requirement: 作答安全模式与事件扩展

WHEN 考试开启了防作弊安全模式,
作答端 SHALL 依据配置限制复制粘贴及监控全屏状态，违规操作 SHALL 触发行为事件旁路上报，但 SHALL NOT 强制交卷。

#### Scenario: 采集复制粘贴违规事件

GIVEN 考试开启了禁止复制粘贴选项（`forbidCopy: true`）
WHEN 学生在作答区域尝试复制、剪切或粘贴
THEN 前端拦截该操作并弹出友好提示
AND 旁路上报 `COPY_PASTE` 行为事件，记录事件类型与时间

#### Scenario: 采集全屏退出事件

GIVEN 考试开启了全屏作答模式（`fullScreen: true`）
WHEN 学生退出全屏窗口
THEN 前端提示全屏退出警告
AND 旁路上报 `FULLSCREEN_EXIT` 行为事件

---

## 能力域：exam-taking

### Requirement: 答题上下文携带防作弊配置

WHEN 学生进入考试或拉取答题数据,
响应体 SHALL 包含 `antiCheatConfig`，供作答端动态启用相应防作弊安全守卫。

#### Scenario: 响应携带防作弊配置

GIVEN 考试配置了防作弊规则
WHEN 学生调用 POST `/api/exam-taking/exams/{examId}/enter` 或 GET `/api/exam-taking/exams/{examId}/paper`
THEN 响应的 `EnterExamResponse` 携带 `antiCheatConfig` 字段
