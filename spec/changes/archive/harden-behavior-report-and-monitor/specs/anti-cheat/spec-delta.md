# 规范增量：anti-cheat

## MODIFIED Requirement: 行为事件采集（追加 Scenario）

> 合入注记：由 harden-behavior-report-and-monitor 追加（2026-10-16）。背景：上报端点曾为全控制器唯一无 @RateLimit 的写端点且 eventData 无体积上限（可刷写 TEXT 64KB/行）、occurredTime 全信客户端（时间线排序可被污染）、客户端可控 eventType 直建指标 tag（基数无界）。修复：限流 + 明细字节上限 + 时间戳服务端锚定 + 指标 tag 归并；未注册类型照常落库的兜底语义不变。

### ADDED Scenario: 行为上报防刷与明细上限

- **WHEN**: 学生向行为上报端点提交事件（正常考试场景：偶发切屏/失焦/粘贴事件）
- **THEN**: 端点 SHALL 受令牌桶限流保护（qps 与容量以代码注释论证为准，正常千人大考峰值全放行，脚本刷写被整流）
- **AND**: 事件明细（eventData）序列化后 SHALL 不超过服务端上限（4096 字节），超限时整笔拒绝（400「事件明细过大」），不落库不打点
- **AND**: 事件发生时间与服务器时钟偏差绝对值超过阈值（10 分钟）时 SHALL 按服务器当前时间落库（不拒绝）
- **AND**: 指标计数 SHALL 按已注册事件类型（BehaviorEventTypes 常量集）分 tag，未注册类型 SHALL 归并至固定 tag "unknown"——落库与判定语义不受影响（兜底策略照常落库）

## MODIFIED Requirement: 行为日志时间线（追加 Scenario）

> 合入注记：由 harden-behavior-report-and-monitor 追加（2026-10-16）。背景：单学生时间线曾为无上限 selectList 且逐行载 event_data TEXT，与上报刷写叠加后查询膨胀（同文件分页查询已有 size≤200 上限先例）。修复：明细查询加行数上限，统计口径拆窄投影查询保全。

### ADDED Scenario: 时间线明细行数上限与统计口径保全

- **WHEN**: 教师查看单个学生的行为时间线
- **THEN**: 明细条目查询 SHALL 设行数上限（1000 条，event_time/id 升序，覆盖正常考试场景任何单人事件量），响应 items 如实反映实际返回条数
- **AND**: total 与严重度分布（low/medium/highCount）SHALL 由独立窄投影统计查询（仅 severity 列，不载 event_data）得出，SHALL NOT 因明细截断而失真
- **AND**: 时间线可见性与权限校验（仅教师与管理员）SHALL 保持不变

