# 规范增量：frontend

## 新增需求：答题卡疑难标记 Mark for Review (ADDED)

学生作答界面 SHALL 提供逐题疑难标记（Mark for Review 🚩），标记独立于答案并双层持久化。

### Scenario: 标记切换与导航格三态呈现 (ADDED)
- **WHEN**: 学生在作答区对当前题点击「标记此题 🚩」
- **THEN**: 该题 SHALL 被加入标记集合，按钮切换为「取消标记」态，再次点击移除标记
- **AND**: 导航格 SHALL 以 🚩 角标与橙色样式呈现已标记题，「已答未标记 / 已答已标记 / 未答已标记」三态可视区分
- **AND**: 标记 SHALL 独立于答案存在——已标记不影响已答/未答判定与交卷请求的 answers 语义

### Scenario: 标记双层持久化与恢复 (ADDED)
- **WHEN**: 学生标记/取消标记后草稿保存触发
- **THEN**: 本地草稿记录（DraftRecord）SHALL 扩展携带标记集合随 answers 一并持久化，刷新/断线重进后标记 SHALL 恢复
- **AND**: 上送后端的草稿保存请求 SHALL 以真实标记集合填充既有 `marked` 字段（不再恒传空数组），零契约变更
- **AND**: 若后端拒绝非空 marked（实施侧集成用例核实），SHALL 回退为仅本地持久化并在 evidence 如实注记，不破坏草稿保存主链路
- **AND**: 多端草稿版本冲突时标记与答案 SHALL 遵循同一冲突策略，不静默覆盖

---

## 新增需求：交卷一键跳转首道未答题 (ADDED)

交卷确认弹窗 SHALL 在存在未答题时提供快捷定位入口。

### Scenario: 未答题时呈现跳转入口 (ADDED)
- **WHEN**: 交卷确认弹窗呈现且 unansweredCount > 0
- **THEN**: 弹窗 SHALL 在「确认交卷」旁提供「跳转首道未答题」按钮
- **AND**: `unansweredCount === 0` 时该按钮 SHALL 不出现

### Scenario: 跳转定位与既有交卷语义零回归 (ADDED)
- **WHEN**: 学生点击「跳转首道未答题」
- **THEN**: 弹窗 SHALL 关闭且当前题 SHALL 切换为 `navStatesOf` 状态数组中第一个未答题（复用同一份状态源，不另算口径）
- **AND**: 确认交卷的防重/loading/失败保留答案语义 SHALL 零改动
