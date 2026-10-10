# frontend 规范增量（add-offline-exam-reentry）

## 变更概述

解禁一期红线 1 的「已领取考试离线重入」子集：把联网 `enter` 时刻由服务端签发的答题上下文（个人快照时间三字段 + 题目视图）持久化到本地 IndexedDB，断网后学生可从「刷新」与「列表重入」两条路径恢复已领取考试 的作答；SW 注册域从作答页扩大到学生考试域（列表 + 作答页）；考试列表断网时降级渲染本地缓存；超窗答案丢失风险显性告知。未领取考试的离线首入维持禁止。

---

## 红线约束（二期再裁决版）

1. **离线首入不做**：本地快照只作已领取考试的断网降级，永不优先于服务端；预备卷端点不做；
2. **绕过服务端交卷时间窗不做**：离线倒计时墙钟估算仅影响显示，超时裁决 100% 服务端；断网跨截止的答案丢失上限如实告知；
3. **离线反作弊不做**：离线重入期间在线心跳缺失、行为事件延迟补报，监考盲区如实登记；
4. **API network-only 禁缓存不变**：快照与列表缓存是应用层 IndexedDB 持久化，不经 SW、不是 HTTP 缓存。

---

## 修改的 Requirement（对 `spec/specs/frontend/spec.md` 基线）

### Requirement: Service Worker 考试离线壳与断网刷新重入【修订：注册域 Scenario】

> 原「路由级精确注册与卸载策略」Scenario 收窄为作答页；本变更将注册域扩大到学生考试域，其余 Scenario（纯函数模块化缓存决策 / API 请求严格 network-only / 导航请求断网回退缓存入口 / 激活阶段清理过期版本缓存）不变。

WHEN 学生在考试作答过程中遭遇网络中断并刷新页面,

系统 SHALL 通过 Service Worker 拦截导航请求并返回预缓存的入口 HTML，由客户端路由与既有 IndexedDB 草稿恢复链路无缝接管作答；Service Worker 逻辑 SHALL 全部模块化为可注入纯函数；API 请求 SHALL 严格 network-only 禁缓存；旧版本缓存 SHALL 在 activate 阶段被自动清理。

#### Scenario: 学生考试域注册与离开域卸载策略【修订】

GIVEN 学生访问在线考试系统

WHEN 挂载学生考试域页面（考试列表 `student/exams` 与作答页 `student/exams/[id]`）时注册 Service Worker

AND 通过路由导航离开学生考试域（目标路由不在 `/student/exams` 下，如返回首页、登出、教师端）时执行 `unregister()`

THEN 学生考试域内的页面导航（列表 → 作答页 → 返回列表）不注销 Service Worker

AND 登录页与其他非学生考试域页面不受 Service Worker 影响

#### Scenario:（以下四个 Scenario 维持基线原文不变）

- 纯函数模块化缓存决策（`shouldCache`：同源静态指纹资源可缓存，`/api/`、跨域、非 GET 不可缓存）
- API 请求严格 network-only（`/api/**` 直接放行，绝不缓存）
- 导航请求断网回退缓存入口（SW 回退缓存入口 HTML，`draftStorage.load` 接管恢复草稿答案）
- 激活阶段清理过期版本缓存（`staleCachesToDelete` 保留当前版本）

---

### Requirement: 断网显性 UI 与离线保护状态【修订：新增风险告知场景】

> 基线四条 Scenario 不变；本变更新增超窗风险告知场景（用户 2026-10-10 裁决：显性告知）。

WHEN 学生在作答过程中发生网络中断或草稿心跳保存失败,

系统 SHALL 在作答页面顶部显著呈现离线保护状态条，提示答案已保存在本机，恢复后将自动同步；文案 SHALL 严格遵守措辞纪律，禁用「离线考试 / 离线作答 / offline exam / offline answer」表述；网络恢复后状态条 SHALL 自动消失并触发既有草稿 flush；断网期间 SHALL 显性告知超窗风险——若超过考试截止时间仍未恢复网络，断网期间新保存的答案可能无法补交。

#### Scenario: 超窗风险显性告知【新增】

GIVEN 学生处于断网状态并正在作答

WHEN 离线保护状态条展示中

THEN 文案包含超窗风险告知（语义：若超过考试截止时间仍未恢复网络，断网期间新保存的答案可能无法补交，以系统收卷为准）

AND 该告知不使用任何禁用措辞（「离线考试 / 离线作答」）

---

### Requirement: 自动保存与断线恢复【修订：「不声称离线考试」场景措辞精确化】

> 基线 Scenario「不声称离线考试」的表述精确化，区分「已领取考试的断网重入」与「完整离线考试模式」。

#### Scenario: 不声称离线考试【修订】

GIVEN 断线期间本地留存答案与已领取考试的本地快照

WHEN 描述该能力

THEN 表述为断线不丢答案、恢复后同步，以及断网可重入已领取的考试继续作答

AND 不声称支持完整离线考试模式

AND 不声称未领取的考试可以离线进入

---

## 新增的 Requirement

### Requirement: 本地考试快照与作答页离线重入

WHEN 学生联网成功进入考试（服务端返回答题上下文）,

系统 SHALL 将答题上下文的脱敏快照（题目视图与服务端时间字段，不含草稿答案）持久化至本地 IndexedDB；当断网状态下 `enter` 请求失败且本地存在该考试的快照时，系统 SHALL 以本地快照降级渲染作答页并继续作答，倒计时 SHALL 以快照持久化时刻的墙钟差值校正估算；网络恢复后系统 SHALL 以服务端返回为准重新锚定，本地快照 SHALL NOT 优先于服务端数据。

#### Scenario: enter 成功写入本地快照

GIVEN 学生联网进入考试成功

WHEN 服务端返回 EnterExamResponse

THEN 脱敏快照（examId、submissionId、status、时间字段、题目视图、version，不含 answers/marked）持久化写入 IndexedDB 快照仓库

AND 同一考试重复 enter 成功时覆盖写入

#### Scenario: 断网重入降级渲染

GIVEN 学生曾联网进入考试 X 且本地存在其快照

AND 当前 navigator.onLine === false

WHEN enter 请求失败

THEN 作答页以本地快照渲染题目、导航与交卷面板

AND 页面显性标注题目来自本机缓存

AND 草稿播种走既有保守合并链路（本地快照无答案，与 draftStorage 本地草稿合并后本地那份生效）

#### Scenario: 降级的严格触发条件（不降级成假数据）

GIVEN enter 请求失败

WHEN navigator.onLine === true（在线但后端故障）或本地无该考试快照

THEN 维持既有错误态展示

AND 不以过期快照伪装成正常数据

#### Scenario: 离线倒计时墙钟校正

GIVEN 本地快照持久化时刻的墙钟为 capturedWallClock，快照内 remainingSeconds 为持久化时刻的服务端剩余

WHEN 断网降级渲染倒计时

THEN 喂给倒计时引擎的剩余秒数 = max(0, remainingSeconds − (当前墙钟 − capturedWallClock))

AND 该估算仅影响显示——归零锁定与超时收卷裁决仍在服务端

#### Scenario: 网络恢复后服务端快照接管

GIVEN 作答页当前以本地快照降级渲染

WHEN 网络恢复且 enter 重试成功

THEN 以服务端返回重新锚定倒计时并覆盖本地快照

AND 本地快照永不优先于服务端数据

#### Scenario: 交卷后清理本地快照

GIVEN 学生交卷成功（服务端确认）

WHEN 前端收到交卷成功状态

THEN 该考试的本地快照行被删除

AND 快照仓库不残留已封闭答卷的作答上下文

#### Scenario: 快照结构损坏容忍

GIVEN 本地快照仓库中某行结构损坏（旧格式或手改库）

WHEN 读取该行

THEN 按无记录处理，不抛错阻断作答

AND 作答页维持既有错误态

---

### Requirement: 考试列表离线降级

WHEN 学生断网访问考试列表,

系统 SHALL 将最近一次联网获取的考试列表缓存至本地 IndexedDB（带用户标识），断网且列表请求失败时 SHALL 以缓存数据降级渲染并显性标注离线缓存；缓存 SHALL NOT 在用户身份变更后跨账号复用；在线时 SHALL 始终以服务端列表为准。

#### Scenario: 列表成功写入缓存

GIVEN 学生联网打开考试列表

WHEN GET /api/exam-taking/exams 成功

THEN 响应列表与当前用户标识、墙钟时刻一并持久化写入本地缓存仓库

#### Scenario: 断网降级渲染缓存列表

GIVEN 本地存在当前用户的列表缓存

AND navigator.onLine === false

WHEN 列表请求失败

THEN 以缓存数据渲染考试列表

AND 页面显性标注「离线缓存」（data-test 可断言）

AND 进入按钮可用性按缓存中的 canEnter 展示（真相由作答页 enter/快照链路给出）

#### Scenario: 换号不串缓存

GIVEN 本地列表缓存的用户标识与当前登录用户不一致

WHEN 断网访问考试列表

THEN 不降级渲染该缓存

AND 维持既有错误态

#### Scenario: 在线始终以服务端为准

GIVEN 本地存在列表缓存

WHEN 联网状态下列表请求成功

THEN 渲染服务端返回并更新缓存

AND 缓存数据不参与在线渲染
