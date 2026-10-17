# frontend 规范差异（spec-delta）

## ADDED Requirements

### Requirement: 学生在线考试草稿板组件

WHEN 学生处于考试作答主页面（`[id].page.vue`）,
前端系统 SHALL 提供轻量浮动草稿板组件（`ScratchpadCanvas.vue`），支持画笔、橡皮擦、撤销与清空，并在本地安全留存草稿。

#### Scenario: 浮动面板折叠与展开
GIVEN 学生处于未封闭的答题界面
WHEN 学生点击草稿板入口按钮
THEN 草稿画板悬浮展开，显示控制工具栏与 Canvas 画布
WHEN 学生点击收起或最小化
THEN 草稿面板收起，不遮挡题目作答区域

#### Scenario: 画笔与橡皮擦模式及参数切换
GIVEN 草稿板处于展开状态
WHEN 学生选择不同粗细（细 2px / 中 4px / 粗 8px）或不同颜色（黑、红、蓝、绿）
THEN 后续绘制线条采用对应参数渲染
WHEN 学生切换至橡皮擦模式
THEN 鼠标划过区域擦除现有笔迹

#### Scenario: 撤销与一键清空
GIVEN 草稿板上有绘制笔迹记录
WHEN 学生点击「撤销」
THEN 系统按笔画历史回退上一步绘制
WHEN 学生点击「清空」
THEN Canvas 画布内容被完整清空，并同步更新本地存储缓存

#### Scenario: 本地草稿恢复与自毁
GIVEN 浏览器存储存在键名为 `exam_scratchpad_${examId}` 的笔迹快照
WHEN 草稿板组件挂载或展开
THEN 自动将存储的图片数据回绘到 Canvas 画布中
WHEN 考试交卷成功或页面检测到答卷封闭
THEN 对应的 `exam_scratchpad_${examId}` 存储项被自动移除
