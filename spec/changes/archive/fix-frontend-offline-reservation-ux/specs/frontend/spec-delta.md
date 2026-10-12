# frontend spec-delta：学生考试列表离线判定响应性与预约操作门控 + 消息中心查询失败显性化（fix-frontend-offline-reservation-ux）

## ADDED Requirements

### Requirement: 学生考试列表离线判定响应性与预约操作门控

学生考试列表页（`pages/(dashboard)/student/exams/index.page.vue`）的离线判定 SHALL 对会话中网络状态变化保持响应（与作答页同源，监听 online/offline 事件），SHALL NOT 以无响应式依赖的 `navigator.onLine` 直查冻结取值；会话中途断网且刷新失败时 SHALL 激活既有离线降级（缓存行渲染 + 离线缓存显性标注）。离线（含离线降级态）时「预约」与「取消预约」入口 SHALL 禁用且不发起预约/取消请求，恢复在线后 SHALL 自动复原；「进入考试/继续作答」入口 SHALL NOT 因离线禁用（作答页离线重入是设计场景）。取消预约 SHALL 经知情确认（点明取消后需重新预约）方可发起，确认前 SHALL NOT 发起取消请求。

#### Scenario: 会话中途断网刷新激活离线降级

GIVEN 学生在线加载考试列表成功且本机存在本人列表缓存

WHEN 会话中途网络断开且刷新列表请求失败

THEN 列表页激活离线降级：呈现离线缓存标注与降级横幅、以缓存行渲染列表

AND 不呈现查询错误 Alert（降级优先于报错，缓存被使用而非旁路）

#### Scenario: 离线禁用预约与取消预约

GIVEN 列表呈现需预约考试的预约/取消预约入口

WHEN 会话处于离线状态（含离线降级态）

THEN 「预约」与「取消预约」按钮呈禁用态、不发起预约/取消请求

AND 网络恢复在线后两按钮自动恢复可用

#### Scenario: 进入作答入口不受离线门控影响

GIVEN 后端下发 canEnter 为 true 的考试且会话处于离线状态（含离线降级态）

THEN 「进入考试/继续作答」按钮保持可用，其 disabled 语义仍只认后端 canEnter

#### Scenario: 取消预约知情确认

GIVEN 学生在可取消窗口内点击「取消预约」

THEN 呈现确认气泡且文案点明取消后需重新预约，确认前不发起取消请求

AND 确认后走既有取消链路（成功与失败反馈零改动），放弃确认则不发起

### Requirement: 消息中心查询失败显性化

消息中心（`components/MessageCenter.vue`）的未读数与消息列表查询 SHALL 消费 `error`：失败 SHALL 以错误 Alert 显性呈现后端 message，SHALL NOT 伪装成「暂无消息」空态或未读数静默为 0；成功且无数据时 SHALL 保留既有业务空态。列表查询失败时 SHALL NOT 渲染列表区与空态；未读数查询失败时角标 SHALL NOT 伪造未读数（错误已显性呈现），消息列表成功时 SHALL 照常渲染。标已读失败 SHALL 有 message.error 反馈。组件 SHALL 维持既有非轮询刷新形态（挂载失效 + 手动刷新 + 标已读后失效），SHALL NOT 引入轮询。

#### Scenario: 列表查询失败显性呈现

GIVEN 学生打开消息中心

WHEN 消息列表查询失败

THEN 错误 Alert 呈现后端 message

AND 「暂无消息」空态与消息列表均不渲染（失败不得伪装成无消息）

#### Scenario: 未读数查询失败不静默

GIVEN 学生打开消息中心

WHEN 未读数查询失败且消息列表查询成功

THEN 错误 Alert 呈现后端 message、未读角标不出现（不伪造可用未读数）

AND 消息列表照常渲染（不因未读数失败被隐藏）

#### Scenario: 成功空数组保留业务空态

GIVEN 学生打开消息中心

WHEN 两查询成功且消息列表为空数组

THEN 「暂无消息」空态原样呈现且不出现错误 Alert

#### Scenario: 查询成功数据零回归

GIVEN 学生打开消息中心且两查询成功返回数据

THEN 消息列表照常渲染、未读角标呈现服务端未读数、既有刷新与标已读联动行为不变

#### Scenario: 标已读失败有反馈

GIVEN 学生点击未读消息的「标为已读」

WHEN 标已读调用失败

THEN 以 message.error 显性反馈失败原因，不静默
