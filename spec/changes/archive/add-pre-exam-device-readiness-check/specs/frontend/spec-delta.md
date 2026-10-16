# frontend spec-delta —— add-pre-exam-device-readiness-check

> 变更提案：spec/changes/add-pre-exam-device-readiness-check/proposal.md
> 基线：spec/specs/frontend/spec.md
> 形态：ADDED Scenario（一条，合入基线时并入学生考试组件相关 Requirement）

## ADDED Scenario：DeviceReadinessCheckModal 自检面板组件

- **WHEN**: 进入考试被 readinessGate 门控拦截（在线且本考试会话未完成自检）
- **THEN**: `DeviceReadinessCheckModal` SHALL 逐项呈现四类检查的执行进度与结果徽标（通过/警示/强警示），检查完成前「进入考试」按钮 SHALL 处于禁用态
- **AND**: 检查逻辑 SHALL 封装于 `hooks/useDeviceReadiness.ts`（RTT 采样丢弃首样本取中位数、userAgent 内核白名单识别、fullscreenEnabled 与 localStorage 特性检测），与 UI 分离可单测
- **AND**: 强警示项 SHALL 触发「仍要进入」二次确认交互；警示项 SHALL 仅呈现徽标不阻断；自检执行异常 SHALL 兜底按已完成放行
