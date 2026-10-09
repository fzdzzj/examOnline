# frontend 规范增量（add-offline-exam-shell）

## 变更概述

在既有断线保护（IndexedDB 本地草稿 + 30s 自动保存 + 网络恢复 flush + 交卷重试配合）之上，补齐「断网刷新重入不断考 + 断网显性 UI + 切屏事件离线兜底」，为学生作答提供离线外壳兜底保障。

---

## 红线约束

1. **离线进入考试不做**：初次进入考试必须联网从服务端获取个人试卷快照（`POST /api/exam-taking/exams/{examId}/enter`），服务端时间戳与幂等快照是合规底线；
2. **绕过服务端交卷时间窗不做**：倒计时归零由服务端时间裁决，超时收卷由后端定时扫描（`ExamSweepService`）兜底，前端不本地延时交卷；
3. **离线反作弊不做（如实定位监考盲区）**：人脸识别、设备指纹、全屏锁定等复杂反作弊不在一期范围；断网期间仅记录切屏/失焦并暂存本地队列，网络恢复后补报，如实定位监考盲区；
4. **API 一律 network-only 禁缓存**：所有 `/api/**` 请求禁止 SW 缓存，避免数据陈旧与并发脏读；SW 仅作为静态壳缓存。

---

## 新增 / 修改 Requirements

### Requirement: 断网显性 UI 与离线保护状态

WHEN 学生在作答过程中发生网络中断或草稿心跳保存失败,

系统 SHALL 在作答页面顶部显著呈现离线保护状态条，提示答案已保存在本机，恢复后将自动同步；文案 SHALL 严格遵守措辞纪律，禁用「离线考试 / 离线作答 / offline exam / offline answer」表述；网络恢复后状态条 SHALL 自动消失并触发既有草稿 flush。

#### Scenario: navigator.onLine 断开触发离线状态条

GIVEN 学生正在作答

WHEN 浏览器触发 offline 事件或 `navigator.onLine === false`

THEN 作答页面顶部显性展示离线保护状态条

AND 文案明确包含「离线保护中，答案已本地保存」或「答案已保存在本机」

#### Scenario: 草稿同步失败双检测触发

GIVEN 浏览器 `navigator.onLine === true` 但局域网断网或网关不可达

WHEN 30s 定时保存或草稿同步失败（状态变为 `unsynced`）

THEN 作答页面顶部显性展示离线保护状态条

#### Scenario: 措辞纪律护栏

GIVEN 作答页在任何离线或未同步状态下

WHEN 检查界面全部渲染文案

THEN 严格不包含「离线考试」与「离线作答」字样

#### Scenario: 网络恢复后状态条消失并触发草稿同步

GIVEN 作答页当前处于离线状态条展示中

WHEN 网络恢复（`online` 事件触发且保存成功）

THEN 离线状态条自动消失

AND 自动触发既有草稿 flush 同步

---

### Requirement: Service Worker 考试离线壳与断网刷新重入

WHEN 学生在考试作答过程中遭遇网络中断并刷新页面,

系统 SHALL 通过 Service Worker 拦截导航请求并返回预缓存的入口 HTML，由客户端路由与既有 IndexedDB 草稿恢复链路无缝接管作答；Service Worker 逻辑 SHALL 全部模块化为可注入纯函数；API 请求 SHALL 严格 network-only 禁缓存；旧版本缓存 SHALL 在 activate 阶段被自动清理。

#### Scenario: 纯函数模块化缓存决策

GIVEN Service Worker 核心辅助模块

WHEN 对网络请求执行缓存决策 `shouldCache(url, request)`

THEN 同源静态指纹资源（JS/CSS/SVG/字体）判定为可缓存

AND 任何以 `/api/` 开头的请求判定为不可缓存

AND 跨域请求与非 GET 请求判定为不可缓存

#### Scenario: API 请求严格 network-only

GIVEN Service Worker 处于激活拦截状态

WHEN 页面发起 `/api/**` 的任何请求

THEN SW 直接放行走网络，绝不返回缓存，绝不写入 SW 缓存

#### Scenario: 导航请求断网回退缓存入口

GIVEN 离线状态下学生刷新作答页面

WHEN 浏览器发起导航请求（mode === 'navigate' 或 accept 包含 text/html）

THEN SW 回退返回缓存的入口 HTML

AND 页面加载后由既有 IndexedDB `draftStorage.load` 接管恢复草稿答案

#### Scenario: 激活阶段清理过期版本缓存

GIVEN 纯函数 `staleCachesToDelete(activeVersion, existingCaches)`

WHEN 传入当前激活版本与已有缓存列表

THEN 返回所有非当前版本的旧缓存名称列表

AND 当前活跃版本的缓存名称被精确保留

#### Scenario: 路由级精确注册与卸载策略

GIVEN 学生访问在线考试系统

WHEN 仅在挂载考试作答页（`student/exams/[id]`）时注册 Service Worker

AND 当通过路由导航离开作答页（如返回列表）时执行 `unregister()`

---

### Requirement: 切屏事件离线兜底与队列补报

WHEN 学生在断网期间发生切屏或失焦行为,

系统 SHALL 将未成功上报的切屏事件持久化暂存至本地 IndexedDB 队列，在网络恢复或草稿 flush 时一并补报；补报过程 SHALL 执行去重护栏，相同离开时长 `durationMs` 不重复上报，确保「一次离开归并为一条，不得翻倍」红线不被破坏。

#### Scenario: 断网切屏上报失败写入本地队列

GIVEN 学生处于断网状态并发生切屏或失焦离开并回归

WHEN 尝试上报切屏行为失败

THEN 事件记录（包含事件类型与离开时长 durationMs）持久化写入本地 IndexedDB 队列

AND 不打断作答

#### Scenario: 网络恢复与草稿 flush 同时机补报

GIVEN 本地 IndexedDB 队列中存在未上报切屏事件

WHEN 浏览器触发 online 事件或调用 `flush()`

THEN 队列中的切屏事件被依次补发上报

AND 上报成功后从本地队列中移除

#### Scenario: 同 durationMs 去重护栏

GIVEN 已经上报成功的切屏事件或正在队列中的切屏事件

WHEN 重复触发相同 durationMs 的上报尝试

THEN 被去重护栏拦截，不向服务端重复发送

AND 保证切屏次数不翻倍
