/**
 * 草稿板单测（add-exam-scratchpad-canvas 阶段 2 · 先红后绿）。
 *
 * 三个层次：
 * 1. **引擎纯逻辑**（utils/scratchpad）：笔画累积、撤销栈、一键清空、文档校验与
 *    sessionStorage 存取——变异 m2（清空/撤销破坏）的击红锚点在这里；
 * 2. **组件交互**（ScratchpadCanvas.vue）：画笔参数（2/4/8px × 4 色）、橡皮擦
 *    （destination-out）、撤销/清空后的重绘与存储同步、按 examId 隔离的
 *    sessionStorage 恢复——add-exam-scratchpad-canvas 案 m1（存储恢复移除）的击红
 *    锚点在恢复用例；
 * 3. **Pointer/Touch 触控**（polish-scratchpad-touch-support）：画布统一接线
 *    pointerdown/pointermove/pointerup/pointercancel（外加 pointerleave 兜底收笔），
 *    jsdom 27 原生支持 PointerEvent 构造器，测试直接以带 pointerId 的真 Pointer
 *    事件驱动——该案 m1（Pointer 事件解绑）的击红锚点在触控手写用例，
 *    m2（移除 touch-action）的击红锚点在防滚动用例；
 * * 4. jsdom 没有 2D 上下文：用可记录调用的假 ctx 桩替换
 *    `HTMLCanvasElement.prototype.getContext`，断言渲染参数（lineWidth / strokeStyle /
 *    globalCompositeOperation）而非像素。组件缓存同一 ctx，ops 跨重绘累积，
 *    所以「撤销后只画剩下的那笔」断言的是**最后一次**属性赋值。
 */
import { flushPromises, mount, type DOMWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';

import ScratchpadCanvas from '@/components/student/ScratchpadCanvas.vue';
import scratchpadCanvasSource from '@/components/student/ScratchpadCanvas.vue?raw';
import {
  type ScratchpadDocument,
  ScratchpadBoard,
  loadScratchpadDocument,
  parseScratchpadDocument,
  removeScratchpad,
  saveScratchpadDocument,
  scratchpadStorageKey,
  serializeScratchpadDocument,
} from '@/utils/scratchpad';

/** 记录属性赋值与方法调用的假 2D 上下文（jsdom 不实现 canvas 2d）。 */
interface FakeContext {
  ctx: CanvasRenderingContext2D;
  props: Record<string, unknown>;
  ops: Array<{ op: string; args: unknown[] }>;
}

const CONTEXT_METHODS = ['beginPath', 'moveTo', 'lineTo', 'stroke', 'clearRect', 'save', 'restore'];
const CONTEXT_PROPS = [
  'strokeStyle',
  'lineWidth',
  'lineCap',
  'lineJoin',
  'globalCompositeOperation',
];

function makeFakeContext(): FakeContext {
  const props: Record<string, unknown> = {};
  const ops: Array<{ op: string; args: unknown[] }> = [];
  const target: Record<string, unknown> = {};
  for (const op of CONTEXT_METHODS) {
    target[op] = vi.fn((...args: unknown[]) => {
      ops.push({ op, args });
    });
  }
  for (const prop of CONTEXT_PROPS) {
    Object.defineProperty(target, prop, {
      get: () => props[prop],
      set: (value: unknown) => {
        props[prop] = value;
        ops.push({ op: `set:${prop}`, args: [value] });
      },
      configurable: true,
    });
  }
  return { ctx: target as unknown as CanvasRenderingContext2D, props, ops };
}

let current: FakeContext | null = null;

beforeEach(() => {
  current = null;
  const spy = vi.spyOn(HTMLCanvasElement.prototype, 'getContext');
  spy.mockImplementation((() => {
    current = makeFakeContext();
    return current.ctx;
  }) as unknown as typeof HTMLCanvasElement.prototype.getContext as never);
  sessionStorage.clear();
});

function propValues(name: string): unknown[] {
  return current?.ops.filter((c) => c.op === `set:${name}`).map((c) => c.args[0]) ?? [];
}

function lastProp(name: string): unknown {
  const values = propValues(name);
  return values[values.length - 1];
}

async function mountPanel(examId = 7) {
  const wrapper = mount(ScratchpadCanvas, { props: { examId } });
  await flushPromises();
  await nextTick();
  return wrapper;
}

/**
 * 用真 PointerEvent 构造器派发指针事件。不走 test-utils 的 trigger：它构造完
 * PointerEvent 后还会把 options 键逐个回赋到事件对象上，而 clientX/clientY 的
 * 只读 getter 挂在 MouseEvent.prototype（PointerEvent.prototype 无自有描述符），
 * jsdom 下 trigger('pointerdown', { clientX }) 必然抛
 * 「Cannot set property clientX … only a getter」。dispatchEvent 直派既绕开该坑，
 * 也让驱动事件真正来自 PointerEvent 构造器（pointerId/pointerType 原生初始化）。
 */
async function firePointer(
  canvas: DOMWrapper<Element>,
  type: string,
  init: { clientX?: number; clientY?: number; pointerId?: number; pointerType?: string }
): Promise<void> {
  canvas.element.dispatchEvent(
    new PointerEvent(type, {
      bubbles: true,
      cancelable: true,
      pointerId: init.pointerId ?? 1,
      pointerType: init.pointerType ?? 'mouse',
      clientX: init.clientX ?? 0,
      clientY: init.clientY ?? 0,
    })
  );
  await nextTick();
}

/**
 * 在画布上模拟一笔（pointerdown → pointermove… → pointerup），点序列全部落进同一
 * 笔画。同一 pointerId 贯穿按下/滑动/抬起——组件按 pointerId 识别握笔的手指，
 * 收笔事件缺了 id 会被当作「别的手指」拒绝。
 */
async function drawStroke(
  wrapper: ReturnType<typeof mount>,
  points: Array<[number, number]>,
  pointerId = 1
): Promise<void> {
  const canvas = wrapper.find('[data-test="scratchpad-canvas"]');
  const [first, ...rest] = points;
  await firePointer(canvas, 'pointerdown', { clientX: first[0], clientY: first[1], pointerId });
  for (const [x, y] of rest) {
    await firePointer(canvas, 'pointermove', { clientX: x, clientY: y, pointerId });
  }
  await firePointer(canvas, 'pointerup', { pointerId });
}

function strokeCountText(wrapper: ReturnType<typeof mount>): string {
  return wrapper.find('[data-test="scratchpad-stroke-count"]').text();
}

describe('草稿板存储键与引擎纯逻辑', () => {
  it('存储键以 examId 隔离：exam_scratchpad_${examId}', () => {
    expect(scratchpadStorageKey(42)).toBe('exam_scratchpad_42');
  });

  it('begin/extend 累积笔画与点数，undo 逐笔弹出、空栈撤销返回 false', () => {
    const board = new ScratchpadBoard();
    expect(board.undo()).toBe(false);

    board.beginStroke('pen', '#222222', 4, 1, 1);
    board.extendStroke(2, 2);
    board.extendStroke(3, 3);
    board.endStroke();
    board.beginStroke('pen', '#ff4d4f', 8, 9, 9);
    board.endStroke();

    expect(board.strokeCount).toBe(2);
    expect(board.allStrokes()[0].points).toHaveLength(3);

    expect(board.undo()).toBe(true);
    expect(board.strokeCount).toBe(1);
    expect(board.undo()).toBe(true);
    expect(board.strokeCount).toBe(0);
    expect(board.undo()).toBe(false);
  });

  it('一键清空：笔画计数归零', () => {
    const board = new ScratchpadBoard();
    board.beginStroke('pen', '#222222', 2, 0, 0);
    board.endStroke();
    board.beginStroke('eraser', '#000000', 8, 5, 5);
    board.endStroke();
    expect(board.strokeCount).toBe(2);

    board.clear();
    expect(board.strokeCount).toBe(0);
    expect(board.isEmpty()).toBe(true);
  });

  it('restore 校验：合法文档恢复、垃圾数据 / 错误版本 / 非法笔画拒绝且不变更现场', () => {
    const board = new ScratchpadBoard();
    board.beginStroke('pen', '#222222', 4, 1, 1);
    board.endStroke();

    expect(board.restore({ version: 1, strokes: [] })).toBe(true);
    expect(board.strokeCount).toBe(0);

    // 非法输入一律拒绝，且不清空已有笔画
    board.beginStroke('pen', '#222222', 4, 1, 1);
    board.endStroke();
    expect(board.restore(null)).toBe(false);
    expect(board.restore({ version: 2, strokes: [] })).toBe(false);
    expect(board.restore({ version: 1 })).toBe(false);
    expect(board.restore('not-an-object')).toBe(false);
    expect(
      board.restore({
        version: 1,
        strokes: [{ mode: 'marker', color: '#fff', width: 4, points: [{ x: 1, y: 1 }] }],
      })
    ).toBe(false);
    expect(board.strokeCount).toBe(1);
  });

  it('parse 拒绝坏 JSON 返回 null；save/load 往返一致；remove 删除键', () => {
    expect(parseScratchpadDocument(null)).toBeNull();
    expect(parseScratchpadDocument('{{{')).toBeNull();

    const doc: ScratchpadDocument = {
      version: 1,
      strokes: [
        {
          mode: 'pen',
          color: '#1890ff',
          width: 2,
          points: [
            { x: 1, y: 2 },
            { x: 3, y: 4 },
          ],
        },
      ],
    };
    saveScratchpadDocument(sessionStorage, 42, doc);
    expect(sessionStorage.getItem(scratchpadStorageKey(42))).toBe(serializeScratchpadDocument(doc));
    expect(loadScratchpadDocument(sessionStorage, 42)).toEqual(doc);

    expect(loadScratchpadDocument(sessionStorage, 99)).toBeNull();
    removeScratchpad(sessionStorage, 42);
    expect(sessionStorage.getItem(scratchpadStorageKey(42))).toBeNull();
  });
});

describe('草稿板组件交互', () => {
  it('挂载即面板：渲染画布与模式/粗细/颜色/撤销/清空工具（显隐由页面 v-if 裁决）', async () => {
    const wrapper = await mountPanel(7);
    expect(wrapper.find('[data-test="scratchpad-panel"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="scratchpad-canvas"]').exists()).toBe(true);
    for (const key of [
      'scratchpad-mode-pen',
      'scratchpad-mode-eraser',
      'scratchpad-size-2',
      'scratchpad-size-4',
      'scratchpad-size-8',
      'scratchpad-color-red',
      'scratchpad-undo',
      'scratchpad-clear',
    ]) {
      expect(wrapper.find(`[data-test="${key}"]`).exists()).toBe(true);
    }
    expect(strokeCountText(wrapper)).toContain('0');
  });

  it('画笔参数切换：选 8px 红色后新笔画按该参数渲染并计入笔画', async () => {
    const wrapper = await mountPanel(7);
    await wrapper.find('[data-test="scratchpad-size-8"]').trigger('click');
    await wrapper.find('[data-test="scratchpad-color-red"]').trigger('click');
    await drawStroke(wrapper, [
      [1, 1],
      [2, 2],
      [3, 3],
    ]);

    expect(lastProp('lineWidth')).toBe(8);
    expect(lastProp('strokeStyle')).toBe('#ff4d4f');
    expect(strokeCountText(wrapper)).toContain('1');
    expect(wrapper.emitted('close')).toBeUndefined();
  });

  it('橡皮擦模式：笔迹以 destination-out 复合渲染', async () => {
    const wrapper = await mountPanel(7);
    await drawStroke(wrapper, [
      [1, 1],
      [2, 2],
    ]);
    await wrapper.find('[data-test="scratchpad-mode-eraser"]').trigger('click');
    await drawStroke(wrapper, [
      [5, 5],
      [6, 6],
    ]);

    expect(lastProp('globalCompositeOperation')).toBe('destination-out');
    expect(strokeCountText(wrapper)).toContain('2');
  });

  it('撤销：按笔画回退一步并同步存储，重绘只剩前一笔的参数', async () => {
    const wrapper = await mountPanel(7);
    // 第一笔：8px 红；第二笔：缺省黑——撤销后重绘应回到第一笔的参数
    await wrapper.find('[data-test="scratchpad-size-8"]').trigger('click');
    await wrapper.find('[data-test="scratchpad-color-red"]').trigger('click');
    await drawStroke(wrapper, [
      [1, 1],
      [2, 2],
    ]);
    await wrapper.find('[data-test="scratchpad-size-2"]').trigger('click');
    await drawStroke(wrapper, [
      [10, 10],
      [20, 20],
    ]);
    expect(strokeCountText(wrapper)).toContain('2');

    await wrapper.find('[data-test="scratchpad-undo"]').trigger('click');
    expect(strokeCountText(wrapper)).toContain('1');

    const restored = loadScratchpadDocument(sessionStorage, 7);
    expect(restored?.strokes).toHaveLength(1);
    expect(restored?.strokes[0].color).toBe('#ff4d4f');

    // 重绘只画剩下那一笔：最后一次渲染参数 = 8px 红
    expect(lastProp('lineWidth')).toBe(8);
    expect(lastProp('strokeStyle')).toBe('#ff4d4f');
  });

  it('一键清空：计数归零、画布 clearRect、存储同步清空（变异 m2 锚点）', async () => {
    const wrapper = await mountPanel(7);
    await drawStroke(wrapper, [
      [1, 1],
      [2, 2],
    ]);
    await drawStroke(wrapper, [
      [10, 10],
      [20, 20],
    ]);
    expect(strokeCountText(wrapper)).toContain('2');

    await wrapper.find('[data-test="scratchpad-clear"]').trigger('click');
    expect(strokeCountText(wrapper)).toContain('0');
    expect(current?.ops.some((c) => c.op === 'clearRect')).toBe(true);

    const cleared = loadScratchpadDocument(sessionStorage, 7);
    expect(cleared?.strokes).toHaveLength(0);
  });

  it('存储恢复（变异 m1 锚点）：挂载时把 sessionStorage 里按 examId 存的笔迹回填', async () => {
    const doc: ScratchpadDocument = {
      version: 1,
      strokes: [
        {
          mode: 'pen',
          color: '#ff4d4f',
          width: 8,
          points: [
            { x: 1, y: 1 },
            { x: 2, y: 2 },
          ],
        },
        { mode: 'pen', color: '#1890ff', width: 2, points: [{ x: 9, y: 9 }] },
      ],
    };
    saveScratchpadDocument(sessionStorage, 7, doc);

    const wrapper = await mountPanel(7);
    expect(strokeCountText(wrapper)).toContain('2');
    // 恢复后按原参数重绘
    expect(propValues('strokeStyle')).toContain('#ff4d4f');
    expect(propValues('lineWidth')).toContain(8);
  });

  it('examId 隔离：换考试只恢复新考试的草稿，不串场', async () => {
    saveScratchpadDocument(sessionStorage, 7, {
      version: 1,
      strokes: [{ mode: 'pen', color: '#222222', width: 4, points: [{ x: 1, y: 1 }] }],
    });
    const wrapper = await mountPanel(7);
    expect(strokeCountText(wrapper)).toContain('1');

    await wrapper.setProps({ examId: 99 });
    await flushPromises();
    await nextTick();
    expect(strokeCountText(wrapper)).toContain('0');

    // 新考试的空文档不应回写覆盖旧考试键
    expect(loadScratchpadDocument(sessionStorage, 7)?.strokes).toHaveLength(1);
  });

  it('收起按钮向页面 emit close（悬浮面板显隐由页面裁决）', async () => {
    const wrapper = await mountPanel(7);
    await wrapper.find('[data-test="scratchpad-minimize"]').trigger('click');
    expect(wrapper.emitted('close')).toHaveLength(1);
  });
});

describe('草稿板 Pointer/Touch 触控（polish-scratchpad-touch-support）', () => {
  it('触屏手写一笔：真 PointerEvent（pointerType=touch）驱动落笔/续点/收笔并持久化（变异 m1 锚点）', async () => {
    const wrapper = await mountPanel(7);
    const canvas = wrapper.find('[data-test="scratchpad-canvas"]');
    await firePointer(canvas, 'pointerdown', {
      clientX: 12,
      clientY: 30,
      pointerId: 3,
      pointerType: 'touch',
    });
    await firePointer(canvas, 'pointermove', {
      clientX: 40,
      clientY: 60,
      pointerId: 3,
      pointerType: 'touch',
    });
    await firePointer(canvas, 'pointermove', {
      clientX: 80,
      clientY: 90,
      pointerId: 3,
      pointerType: 'touch',
    });
    await firePointer(canvas, 'pointerup', { pointerId: 3, pointerType: 'touch' });

    expect(strokeCountText(wrapper)).toContain('1');
    const saved = loadScratchpadDocument(sessionStorage, 7);
    expect(saved?.strokes).toHaveLength(1);
    expect(saved?.strokes[0].points).toEqual([
      { x: 12, y: 30 },
      { x: 40, y: 60 },
      { x: 80, y: 90 },
    ]);
  });

  it('画布 touch-action: none：触屏手写时浏览器不得接管手势触发页面滚动（变异 m2 锚点）', () => {
    // jsdom 的 cssstyle 不建模 touch-action：cssText / setProperty / 直接赋值三条
    // 通道都会在 CSSOM 序列化时把该属性整体丢弃（连内联 style 属性都留不下），DOM
    // 级断言在本环境不可能成立。退而断言组件源码里画布元素确实声明了
    // touch-action: none——移除即红（m2 变异锚点）；真实浏览器里生效的正是这份声明。
    const canvasTag = scratchpadCanvasSource.match(/<canvas[\s\S]*?<\/canvas>/)?.[0] ?? '';
    expect(canvasTag).toMatch(/touch-action\s*:\s*none/);
  });

  it('pointercancel 收笔：手势被系统中断也落账持久化，同指后续滑动不再延长笔画', async () => {
    const wrapper = await mountPanel(7);
    const canvas = wrapper.find('[data-test="scratchpad-canvas"]');
    await firePointer(canvas, 'pointerdown', { clientX: 5, clientY: 5, pointerId: 7 });
    await firePointer(canvas, 'pointermove', { clientX: 6, clientY: 6, pointerId: 7 });
    await firePointer(canvas, 'pointercancel', { pointerId: 7 });

    expect(strokeCountText(wrapper)).toContain('1');
    expect(loadScratchpadDocument(sessionStorage, 7)?.strokes[0].points).toEqual([
      { x: 5, y: 5 },
      { x: 6, y: 6 },
    ]);

    // 取消后该指针的手势已结束：继续滑动不得往已收笔的笔画追加
    await firePointer(canvas, 'pointermove', { clientX: 99, clientY: 99, pointerId: 7 });
    expect(loadScratchpadDocument(sessionStorage, 7)?.strokes[0].points).toEqual([
      { x: 5, y: 5 },
      { x: 6, y: 6 },
    ]);
  });

  it('多指触控按 pointerId 区分：第二根手指按下/滑动不串笔，其抬起不收第一指的笔', async () => {
    const wrapper = await mountPanel(7);
    const canvas = wrapper.find('[data-test="scratchpad-canvas"]');
    // 食指落笔并滑动
    await firePointer(canvas, 'pointerdown', { clientX: 1, clientY: 1, pointerId: 10 });
    await firePointer(canvas, 'pointermove', { clientX: 2, clientY: 2, pointerId: 10 });
    // 第二根手指按下：不开启新笔、不接管现有笔画
    await firePointer(canvas, 'pointerdown', { clientX: 200, clientY: 200, pointerId: 11 });
    await firePointer(canvas, 'pointermove', { clientX: 210, clientY: 210, pointerId: 11 });
    // 第二指抬起：不收第一指的笔
    await firePointer(canvas, 'pointerup', { pointerId: 11 });
    // 食指继续滑动后收笔
    await firePointer(canvas, 'pointermove', { clientX: 3, clientY: 3, pointerId: 10 });
    await firePointer(canvas, 'pointerup', { pointerId: 10 });

    expect(strokeCountText(wrapper)).toContain('1');
    const saved = loadScratchpadDocument(sessionStorage, 7);
    expect(saved?.strokes).toHaveLength(1);
    expect(saved?.strokes[0].points).toEqual([
      { x: 1, y: 1 },
      { x: 2, y: 2 },
      { x: 3, y: 3 },
    ]);
  });

  it('pointerleave 收笔：手指/鼠标滑出画布即停笔并持久化', async () => {
    const wrapper = await mountPanel(7);
    const canvas = wrapper.find('[data-test="scratchpad-canvas"]');
    await firePointer(canvas, 'pointerdown', { clientX: 4, clientY: 4, pointerId: 2 });
    await firePointer(canvas, 'pointermove', { clientX: 8, clientY: 8, pointerId: 2 });
    await firePointer(canvas, 'pointerleave', { pointerId: 2 });

    expect(strokeCountText(wrapper)).toContain('1');
    expect(loadScratchpadDocument(sessionStorage, 7)?.strokes[0].points).toEqual([
      { x: 4, y: 4 },
      { x: 8, y: 8 },
    ]);
  });
});
