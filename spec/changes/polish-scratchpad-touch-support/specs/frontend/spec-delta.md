# frontend 规范差异（spec-delta）

## MODIFIED Requirements

### Requirement: 学生在线考试草稿板组件

> 修改说明：在既有手绘支持的基础上，升级画布交互为 Pointer/Touch 统一指针事件，并增加防滚动样式，支持触摸屏与触控笔手写演算。

#### Scenario: 画布多端触控与指针事件支持
- **GIVEN**: 学生在触摸屏、平板电脑或桌面端打开草稿画板
- **WHEN**: 学生使用触控笔、手指触摸或鼠标在 Canvas 上划过
- **THEN**: 系统 SHALL 响应指针事件并连续记录笔迹坐标点
- **AND**: 画布区域 SHALL 设置 `touch-action: none` 防止手势触发页面默认滚动
- **AND**: 指针离开或取消（`pointercancel`）时正确收笔并同步持久化
