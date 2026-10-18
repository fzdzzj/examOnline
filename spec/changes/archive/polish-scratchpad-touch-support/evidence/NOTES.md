# 证据注记（polish-scratchpad-touch-support）

实施笔：`767f9b8`（分支 `feature/polish-scratchpad-touch-support`，基于立案笔 `cfd8629`）。
除另有注明，本目录全部日志为该 revision 上的实测输出。

## 证据清单

| 文件 | 内容 |
| --- | --- |
| tdd-red-phase.txt | 先红阶段：测试先行落盘后、组件改造前，ScratchpadCanvas.spec.ts 单文件实测 9 红 / 9 绿（18 例），全部断言级（笔迹 0 笔 / touchAction undefined），无一例环境型 TypeError |
| mutation-m1-red.txt | 变异 m1（Pointer 事件解绑：模板删除五个 @pointer* 绑定）击红 8 例：新增 5 例 Pointer/Touch 触控 + 4 例既有交互用例（drawStroke 已切 Pointer 手势）；touch-action 用例不依赖事件接线故不红，归 m2 判别 |
| mutation-m2-red.txt | 变异 m2（移除画布 touch-action: none 声明）恰击红 1 例：防滚动用例（源级契约断言） |
| mutation-revert-green.txt | 两变异回退后（工作树 = HEAD）单文件复跑 18/18 全绿 |
| gate-frontend-lint.txt | `pnpm run lint:check` exit 0（0 错 0 警） |
| gate-frontend-typecheck.txt | `pnpm run type-check:check` exit 0 |
| gate-frontend-test-full.txt | `pnpm test -- --maxWorkers=4`：122 文件 939 例全绿（934 + 5） |
| gate-backend.txt | 仓库根 `mvnw.cmd clean test`：714 / 0 / 0 / 1，BUILD SUCCESS（与基线持平，零后端触碰） |
| evidence-sha256.txt | 上列文件 sha256 清单（bash 重定向产物含清单自指条目一行，与前案形态一致） |

## 过程审裁（三处实现口径的演化与理由）

1. **事件驱动方式：test-utils trigger → 真 PointerEvent 构造器 + dispatchEvent。**
   首版测试用 `trigger('pointerdown', { clientX })` 出现环境型红
   （`Cannot set property clientX of #<MouseEvent> which has only a getter`）：
   trigger 构造完事件后把 options 键逐个回赋，而 `clientX` 只读 getter 挂在
   `MouseEvent.prototype`（`PointerEvent.prototype` 无自有描述符），jsdom 下必抛。
   改用 `new PointerEvent(...)` + `dispatchEvent` 直派后重跑红相并覆盖留证
   （tdd-red-phase.txt 即换法后的断言级红相）——附带收益是驱动事件真正由
   PointerEvent 构造器原生初始化 pointerId/pointerType。
2. **touch-action 断言：DOM 级 → 组件源级契约。**
   jsdom 的 cssstyle 不建模 `touch-action`：cssText / setProperty / 属性直赋 /
   混排四通道实测均把该属性整体丢弃（连内联 style 属性都留不下），DOM 级断言在本
   环境不可能成立。退为经 `?raw` 引入组件源码、断言 `<canvas>` 标签块内声明了
   `touch-action\s*:\s*none`——移除即红（m2 已证判别力），真实浏览器里生效的正是
   这份声明。
3. **touch-action 落点：scoped CSS 类 → 画布内联样式。**
   原实现已有类内声明（jsdom 两处都无法观测）；内联化让「画布元素携带
   touch-action: none」成为单一声明点，与源级断言的 `<canvas>` 块匹配锚定一致，
   浏览器语义不变。

## 门禁执行口径

- 双端门禁串行执行（先 `mvnw.cmd clean test` 后前端三门禁），无并发负载。
- 前端全量 vitest、lint、typecheck 均在实施笔入库后的 HEAD（`767f9b8`）复跑留证；
  后端门禁同样在该 HEAD 上执行。
- 变异仅在实施笔入库后进行（工作树临时改动，`git checkout HEAD --` 回退），符合
  「变异须在实施笔入库后做」纪律。
- `gate-baseline.json` 仅单调上调 `frontendTests` 934 → 939（backendTests 714、
  openapiPaths 100 未动）；契约零改动。
