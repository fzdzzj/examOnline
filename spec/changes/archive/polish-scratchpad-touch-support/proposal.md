# Proposal: polish-scratchpad-touch-support（草稿板触屏手写与触控笔支持，S 级纯前端交互特性）

## 1. 为什么做（背景与动机）

在刚刚收口合入的 `add-exam-scratchpad-canvas` 特性中，考试中在线草稿板已为学生提供了 Canvas 手绘、粗细颜色切换、橡皮擦、撤销、清空与会话级持久化能力。
但在独立终审复核中，复核员提出了 P3 体验观察点：
- **痛点**：目前 `ScratchpadCanvas.vue` 画布仅绑定了 `mousedown` / `mousemove` / `mouseup` 鼠标事件。当学生使用 iPad、平板电脑或支持触控屏与手写笔（Apple Pencil / Surface Pen）的设备参加考试时，触摸屏幕不仅无法正常作画，还会触发移动端浏览器的默认滚动与页面拖拽，导致演算体验受阻；
- **目标**：为 Canvas 画布全面引入 Pointer Events（`pointerdown` / `pointermove` / `pointerup` / `pointercancel`）与 Touch 防滚动（`touch-action: none`），原生统一支持鼠标、手指触摸与各类触控笔，实现多端丝滑演算。

## 2. 改动范围与设计细节

### 2.1 零 DDL 与零后端侵入红线
- **纯前端实现**：`src/main`、`src/test`、`pom.xml`、`schema.sql`、`docker/mysql/migrations/` **0 字节改动**；
- **契约保持不变**：`openapi.yaml` 100 paths 严格保持不变；
- **门禁基线**：后端 714 / 0 / 0 / 1 保持不变，`.github/ci/gate-baseline.json` 仅单调上调 `frontendTests`。

### 2.2 核心前端组件改造
1. **画布指针与触控事件升级（`ScratchpadCanvas.vue`）**：
   - 画布绑定 `pointerdown`、`pointermove`、`pointerup`、`pointercancel` 事件；
   - 增加 CSS 样式 `touch-action: none`，防止触屏手写过程中浏览器触发默认手势缩放或滚动；
   - 坐标换算支持 `pointerId` 识别与压感（若有），笔迹绘制平滑流畅；
2. **测试与变异验证**：
   - 编写单元测试模拟 Pointer 与 Touch 手势输入，验证触控模式下的笔迹绘制、撤销与持久化；
   - 变异验证：破坏 Pointer 事件接线或移除 `touch-action` 样式限制，断言级击红留证。

## 3. 门禁与交付基线
- 后端门禁：`./mvnw.cmd clean test` 保持 714/0/0/1 全绿；
- 前端门禁：`pnpm run lint:check` 0 错，`pnpm run type-check:check` 0 错，`pnpm test` 全绿并单调上调 `frontendTests`（934 $\to$ 934 + N）；
- 契约：100 paths 保持不变。
