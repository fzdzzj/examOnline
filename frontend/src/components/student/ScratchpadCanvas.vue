<template>
  <div class="scratchpad-panel" data-test="scratchpad-panel">
    <div class="scratchpad-header">
      <span class="scratchpad-title">📝 草稿板</span>
      <span class="scratchpad-meta" data-test="scratchpad-stroke-count">
        笔迹 {{ strokeCount }} 笔
      </span>
      <Button size="small" data-test="scratchpad-minimize" @click="emit('close')">收起</Button>
    </div>

    <div class="scratchpad-toolbar">
      <Button
        size="small"
        :type="mode === 'pen' ? 'primary' : 'default'"
        data-test="scratchpad-mode-pen"
        @click="mode = 'pen'"
      >
        画笔
      </Button>
      <Button
        size="small"
        :type="mode === 'eraser' ? 'primary' : 'default'"
        data-test="scratchpad-mode-eraser"
        @click="mode = 'eraser'"
      >
        橡皮
      </Button>

      <span class="scratchpad-sep" />
      <Button
        v-for="size in SCRATCHPAD_BRUSH_SIZES"
        :key="size"
        size="small"
        :type="brushWidth === size ? 'primary' : 'default'"
        :data-test="`scratchpad-size-${size}`"
        @click="brushWidth = size"
      >
        {{ size }}px
      </Button>

      <span class="scratchpad-sep" />
      <Button
        v-for="colorOption in SCRATCHPAD_COLORS"
        :key="colorOption.name"
        size="small"
        :type="color === colorOption.value ? 'primary' : 'default'"
        :data-test="`scratchpad-color-${colorOption.name}`"
        :title="`颜色：${COLOR_LABELS[colorOption.name]}`"
        @click="color = colorOption.value"
      >
        {{ COLOR_LABELS[colorOption.name] }}
      </Button>

      <span class="scratchpad-sep" />
      <Button
        size="small"
        data-test="scratchpad-undo"
        :disabled="strokeCount === 0"
        @click="onUndo"
      >
        撤销
      </Button>
      <Button
        size="small"
        danger
        data-test="scratchpad-clear"
        :disabled="strokeCount === 0"
        @click="onClear"
      >
        清空
      </Button>
    </div>

    <canvas
      ref="canvasRef"
      class="scratchpad-canvas"
      width="480"
      height="320"
      data-test="scratchpad-canvas"
      @mousedown="onPointerDown"
      @mousemove="onPointerMove"
      @mouseup="onPointerUp"
      @mouseleave="onPointerUp"
    ></canvas>
  </div>
</template>

<script setup lang="ts">
/**
 * 考试草稿板（add-exam-scratchpad-canvas）：Canvas 自由手写 + 橡皮擦 + 逐步撤销 +
 * 一键清空，笔迹按 `exam_scratchpad_${examId}` 存 sessionStorage——切题、折叠、刷新
 * 均可恢复（挂载/换考试时回填），交卷后的自毁清理由作答页 watch 裁决（removeScratchpad）。
 *
 * 边界说明：
 * - 显隐不在组件内：页面用 v-if 裁决「何时可以出现」，本组件挂载即面板；
 * - 草稿是体验层辅助，任何存储失败（隐私模式 / 配额满）都静默旁路，绝不打断作答；
 * - 笔画账本与序列化校验全在 utils/scratchpad（纯逻辑可测），本组件只做事件接线与渲染；
 * - jsdom 等无 2D 上下文的环境：getContext 失败时跳过渲染，账本与存储照常工作。
 */
import { Button } from 'ant-design-vue';
import { nextTick, ref, watch } from 'vue';

import {
  renderScratchpadStrokes,
  loadScratchpadDocument,
  saveScratchpadDocument,
  SCRATCHPAD_BRUSH_SIZES,
  SCRATCHPAD_COLORS,
  type ScratchpadMode,
  type ScratchpadPoint,
  type ScratchpadStorage,
  ScratchpadBoard,
} from '@/utils/scratchpad';

const props = defineProps<{ examId: number }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const COLOR_LABELS = { black: '黑', red: '红', blue: '蓝', green: '绿' } as const;

const mode = ref<ScratchpadMode>('pen');
const brushWidth = ref<number>(4);
const color = ref<string>(SCRATCHPAD_COLORS[0].value);
const strokeCount = ref(0);
const canvasRef = ref<HTMLCanvasElement | null>(null);

/** 笔画账本：渲染与持久化的唯一事实源。 */
const board = new ScratchpadBoard();
/** 正在书写中的标记：mousedown 置真，mouseup / mouseleave 收笔。 */
const drawing = ref(false);

function getScratchpadStorage(): ScratchpadStorage | null {
  try {
    if (typeof sessionStorage === 'undefined') return null;
    return sessionStorage;
  } catch {
    return null;
  }
}

/** 2D 上下文按 canvas 元素缓存：v-if 重建画布后自动换新。 */
let cachedContext: { el: HTMLCanvasElement; ctx: CanvasRenderingContext2D } | null = null;
function acquireContext(): CanvasRenderingContext2D | null {
  const el = canvasRef.value;
  if (!el) return null;
  if (cachedContext?.el === el) return cachedContext.ctx;
  try {
    const ctx = el.getContext('2d');
    cachedContext = ctx ? { el, ctx } : null;
    return ctx;
  } catch {
    cachedContext = null;
    return null;
  }
}

function redraw(): void {
  const ctx = acquireContext();
  if (!ctx) return;
  ctx.clearRect(0, 0, canvasRef.value?.width ?? 0, canvasRef.value?.height ?? 0);
  renderScratchpadStrokes(ctx, board.allStrokes());
}

function persist(): void {
  saveScratchpadDocument(getScratchpadStorage(), props.examId, board.toDocument());
}

/** 指针坐标 → 画布坐标：按 CSS 尺寸与内部分辨率的比值换算（jsdom 下 rect 为 0 时按 1:1）。 */
function canvasPoint(event: MouseEvent): ScratchpadPoint {
  const el = canvasRef.value;
  if (!el) return { x: 0, y: 0 };
  const rect = el.getBoundingClientRect();
  const scaleX = rect.width ? el.width / rect.width : 1;
  const scaleY = rect.height ? el.height / rect.height : 1;
  return {
    x: Math.round((event.clientX - rect.left) * scaleX),
    y: Math.round((event.clientY - rect.top) * scaleY),
  };
}

function onPointerDown(event: MouseEvent): void {
  const point = canvasPoint(event);
  board.beginStroke(mode.value, color.value, brushWidth.value, point.x, point.y);
  drawing.value = true;
  strokeCount.value = board.strokeCount;
  redraw();
}

function onPointerMove(event: MouseEvent): void {
  if (!drawing.value) return;
  const point = canvasPoint(event);
  board.extendStroke(point.x, point.y);
  redraw();
}

function onPointerUp(): void {
  if (!drawing.value) return;
  drawing.value = false;
  persist();
}

function onUndo(): void {
  if (!board.undo()) return;
  strokeCount.value = board.strokeCount;
  redraw();
  persist();
}

function onClear(): void {
  if (board.isEmpty()) return;
  board.clear();
  drawing.value = false;
  strokeCount.value = board.strokeCount;
  redraw();
  persist();
}

/**
 * 挂载 / 换考试：从 sessionStorage 回填草稿并重绘。新考试无存档时账本归位为
 * 空白（不回写存储——绝不能把上一场考试的笔迹带进新考试的键里）。
 */
watch(
  () => props.examId,
  async () => {
    const doc = loadScratchpadDocument(getScratchpadStorage(), props.examId);
    if (doc) {
      board.restore(doc);
    } else {
      board.clear();
    }
    strokeCount.value = board.strokeCount;
    await nextTick();
    redraw();
  },
  { immediate: true }
);
</script>

<style scoped>
.scratchpad-panel {
  position: fixed;
  right: 24px;
  bottom: 88px;
  z-index: 1000;
  width: 520px;
  max-width: calc(100vw - 32px);
  padding: 12px;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.15);
}

.scratchpad-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.scratchpad-title {
  font-weight: 600;
}

.scratchpad-meta {
  margin-right: auto;
  color: #8c8c8c;
  font-size: 12px;
}

.scratchpad-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-bottom: 8px;
}

.scratchpad-sep {
  width: 1px;
  height: 16px;
  margin: 0 4px;
  background: #d9d9d9;
}

.scratchpad-canvas {
  display: block;
  width: 100%;
  background: #ffffff;
  border: 1px dashed #bfbfbf;
  border-radius: 4px;
  cursor: crosshair;
  touch-action: none;
}
</style>
