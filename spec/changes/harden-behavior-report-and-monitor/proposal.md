# 变更提案：harden-behavior-report-and-monitor

## 为什么（Why）

2026-10-16 三轮后端巡查 anti-cheat/taking 域发现（高危×1 + 中×3，主 Agent 亲验 + 子 Agent 直读）：

1. **高危 · 上报端点无防刷**：行为上报 `POST /api/exams/{examId}/behavior`（ExamTakingController L90）是全控制器唯一无 `@RateLimit` 的写端点（enter 2000/5000、submit 500/2000 均有）；`BehaviorReportRequest.eventData` 为 JsonNode **无体积校验**，落库时 `eventData.toString()` 直存 TEXT 64KB 列（ExamBehaviorLogService L42）——学生可脚本无限刷写行为日志表（每请求策略判定 + 单行 insert + 指标计数），存储与查询双重放大。
2. **中 · 指标基数爆炸**：`BusinessMetrics.countAntiCheatEvent`（L170）对客户端可控 eventType 直接 `computeIfAbsent` 建 Counter tag（DTO 仅 @Size(32) 不限取值；未注册类型走兜底策略照常落库照常打点）——任意 32 字符串可无限创建新 tag，Micrometer 指标基数无界增长（内存 + Prometheus）。
3. **中 · 时间线无上限全量载**：`BehaviorLogQueryService.timeline`（L90）单学生时间线 `selectList` 无分页无上限且逐行载 `event_data` TEXT——与刷写叠加后教师时间线查询膨胀（同文件 `page` 方法已有 size≤200 上限先例）。
4. **中 · 时间戳全信客户端**：`BehaviorEventCollectService.collect`（L63）occurredTime 原样落库，客户端可写任意过去/未来值污染时间线排序（event_time 是时间线与监考大屏异常排序的口径源）。

## 改什么（What）

1. **上报限流**：`reportBehavior` 补 `@RateLimit(qps = 200, capacity = 1000, key = "behavior-report")`——正常场景（千人大考、学生偶发切屏/失焦/粘贴事件，峰值远低于 200/s）全放行，恶意脚本被令牌桶整流；单次处理轻（判定 + 单行 insert + counter），参数量级介于 enter 与 submit 之间。注释按既有三端点风格论证。
2. **eventData 体积上限**：`BehaviorEventCollectService.collect` 入口（统一通道，controller 之外的调用方同样受益）校验序列化字节数 ≤ **4096**（常量 `MAX_EVENT_DATA_BYTES`；正常明细为离开时长/客户端时间等，远小于此），超限抛 BusinessException 400「事件明细过大」拒绝整笔——校验先于策略判定，「内部已消化全部异常」承诺仅覆盖判定与落库段，参数拒绝属调用方错误须显式上抛。
3. **时间戳服务端锚定**：`collect` 中 occurredTime 与服务端 now 偏差绝对值超过 **10 分钟**（常量 `MAX_CLOCK_SKEW`，容忍正常时钟漂移）时按服务端 now 落库；不拒绝（时间戳污染只影响排序，锚定即消除）。
4. **指标 tag 归并**：`countAntiCheatEvent` 以 `BehaviorEventTypes` 七个注册常量（SWITCH_SCREEN/WINDOW_BLUR/PAGE_REFRESH/SUBMIT_ANOMALY/DRAFT_CONFLICT/COPY_PASTE/FULLSCREEN_EXIT）构建注册集，未注册类型归并 tag `"unknown"`——落库语义不变（兜底策略照常落库），仅指标维度收敛为有界集合。
5. **时间线明细上限 + 统计口径保全**：`timeline` 拆两查——①统计查询窄投影（仅 severity 列，全量、不载 `event_data` TEXT）算 total 与 low/medium/highCount；②明细查询带行数上限（`LIMIT 1000`，event_time/id 升序，覆盖正常考试场景任何单人事件量），items 如实反映实际返回条数。total 与严重度分布 SHALL NOT 因明细截断失真。

## 影响面（Impact）

- 落点：`taking`（Controller 限流）、`anticheat`（collect 校验/锚定、timeline 拆查）、`monitoring`（metrics 归并）三包 + 对应测试；零 DDL（schema.sql 零 diff）、零契约变更（openapi paths 97 不变，timeline 响应形状不变）、零前端。
- 既有语义保全红线：未注册类型照常落库（兜底策略）；切屏只警告不强制交卷；timeline 教师可见性/权限校验不变；「行为事件采集」既有 Scenario 不回归。
- 门禁基线：仅 backendTests 预期增长；M 级双岗隔离。

## 任务阶段

- 阶段 0 & 1：立项三件套与在途总账登记。
- 阶段 2：五项实施 + 单测先红后绿 + 变异击红留证。
- 阶段 3：全量门禁实测、基线上调、规范合入、归档、`--no-ff` 合并入 main。
