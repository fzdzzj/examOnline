/**
 * 草稿板纯逻辑（add-exam-scratchpad-canvas）：笔画数据模型、撤销栈、一键清空、
 * 序列化校验与 sessionStorage 存取端口。全部不触碰 DOM——组件只做事件接线与渲染。
 *
 * 设计口径（对齐 proposal）：
 * - 键规则 `exam_scratchpad_${examId}`，按考试隔离；
 * - 文档版本 1：`{ version: 1, strokes: [...] }`，恢复前逐笔画校验，坏数据整份拒绝
 *   （不清空现场）——草稿是体验层辅助，任何脏数据都不允许打断作答；
 * - 存储端口收窄为 getItem/setItem/removeItem 三个方法，与页面 getReadinessStorage
 *   同款 try/catch 守卫（隐私模式 / 配额满静默旁路，绝不抛错打断作答）。
 */

export interface ScratchpadPoint {
  x: number;
  y: number;
}

export type ScratchpadMode = 'pen' | 'eraser';

export interface ScratchpadStroke {
  mode: ScratchpadMode;
  color: string;
  width: number;
  points: ScratchpadPoint[];
}

export interface ScratchpadDocument {
  version: 1;
  strokes: ScratchpadStroke[];
}

export const SCRATCHPAD_DOCUMENT_VERSION = 1;
export const SCRATCHPAD_STORAGE_PREFIX = 'exam_scratchpad_';

/** 画笔粗细三档：细 2px / 中 4px / 粗 8px（proposal §2.2.2）。 */
export const SCRATCHPAD_BRUSH_SIZES = [2, 4, 8] as const;

/** 画笔四色：黑、红、蓝、绿（proposal §2.2.2 的对比色组）。 */
export const SCRATCHPAD_COLORS = [
  { name: 'black', value: '#222222' },
  { name: 'red', value: '#ff4d4f' },
  { name: 'blue', value: '#1890ff' },
  { name: 'green', value: '#52c41a' },
] as const;

export function scratchpadStorageKey(examId: number): string {
  return `${SCRATCHPAD_STORAGE_PREFIX}${examId}`;
}

/**
 * 笔画账本。beginStroke 即入账（撤销栈与画布渲染同源），extendStroke 只往最后一笔
 * 追加点；undo / clear 是仅有的两个出口。组件持有的就是这份账本，渲染与序列化都从它推导。
 */
export class ScratchpadBoard {
  private strokes: ScratchpadStroke[] = [];

  get strokeCount(): number {
    return this.strokes.length;
  }

  isEmpty(): boolean {
    return this.strokes.length === 0;
  }

  allStrokes(): readonly ScratchpadStroke[] {
    return this.strokes;
  }

  beginStroke(mode: ScratchpadMode, color: string, width: number, x: number, y: number): void {
    this.strokes.push({ mode, color, width, points: [{ x, y }] });
  }

  extendStroke(x: number, y: number): void {
    const last = this.strokes[this.strokes.length - 1];
    if (!last) return;
    last.points.push({ x, y });
  }

  /** 与 begin/extend 对称的收笔口：留作将来加 redo 栈时的钩子，当前无独立状态。 */
  endStroke(): void {
    /* 收笔无额外状态：笔画在 beginStroke 即已入账 */
  }

  /** 弹出最后一笔；空栈返回 false（无害撤销）。 */
  undo(): boolean {
    return this.strokes.pop() !== undefined;
  }

  /** 一键清空：账本归零（渲染清屏与存储同步由组件在同一动作里完成）。 */
  clear(): void {
    this.strokes = [];
  }

  toDocument(): ScratchpadDocument {
    return {
      version: SCRATCHPAD_DOCUMENT_VERSION,
      strokes: this.strokes.map((stroke) => ({
        mode: stroke.mode,
        color: stroke.color,
        width: stroke.width,
        points: stroke.points.map((point) => ({ x: point.x, y: point.y })),
      })),
    };
  }

  /** 整份替换账本；非法文档拒绝并保持现场不变。 */
  restore(doc: unknown): boolean {
    const normalized = normalizeScratchpadDocument(doc);
    if (!normalized) return false;
    this.strokes = normalized.strokes;
    return true;
  }
}

function isPoint(value: unknown): value is ScratchpadPoint {
  if (typeof value !== 'object' || value === null) return false;
  const point = value as Record<string, unknown>;
  return (
    typeof point.x === 'number' &&
    Number.isFinite(point.x) &&
    typeof point.y === 'number' &&
    Number.isFinite(point.y)
  );
}

function isStroke(value: unknown): value is ScratchpadStroke {
  if (typeof value !== 'object' || value === null) return false;
  const stroke = value as Record<string, unknown>;
  if (stroke.mode !== 'pen' && stroke.mode !== 'eraser') return false;
  if (typeof stroke.color !== 'string' || stroke.color.length === 0) return false;
  if (typeof stroke.width !== 'number' || !Number.isFinite(stroke.width) || stroke.width <= 0) {
    return false;
  }
  if (!Array.isArray(stroke.points) || !stroke.points.every(isPoint)) return false;
  return true;
}

/** 外来数据逐项校验；不合法的整份拒绝（返回 null），不做部分恢复。 */
export function normalizeScratchpadDocument(raw: unknown): ScratchpadDocument | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const candidate = raw as Record<string, unknown>;
  if (candidate.version !== SCRATCHPAD_DOCUMENT_VERSION) return null;
  if (!Array.isArray(candidate.strokes)) return null;
  if (!candidate.strokes.every(isStroke)) return null;
  return { version: SCRATCHPAD_DOCUMENT_VERSION, strokes: candidate.strokes as ScratchpadStroke[] };
}

export function serializeScratchpadDocument(doc: ScratchpadDocument): string {
  return JSON.stringify(doc);
}

export function parseScratchpadDocument(
  json: string | null | undefined
): ScratchpadDocument | null {
  if (!json) return null;
  try {
    return normalizeScratchpadDocument(JSON.parse(json));
  } catch {
    return null;
  }
}

/** 存储端口：只收窄到草稿用到的三个方法（页面与组件各自决定从哪个 Storage 拿）。 */
export type ScratchpadStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

export function loadScratchpadDocument(
  storage: ScratchpadStorage | null,
  examId: number
): ScratchpadDocument | null {
  if (!storage) return null;
  try {
    return parseScratchpadDocument(storage.getItem(scratchpadStorageKey(examId)));
  } catch {
    return null;
  }
}

export function saveScratchpadDocument(
  storage: ScratchpadStorage | null,
  examId: number,
  doc: ScratchpadDocument
): void {
  if (!storage) return;
  try {
    storage.setItem(scratchpadStorageKey(examId), serializeScratchpadDocument(doc));
  } catch {
    /* 配额满 / 隐私模式：草稿留存尽力而为，绝不打断作答 */
  }
}

/** 交卷成功或后端封闭后的自毁入口（页面 watch 调用）。 */
export function removeScratchpad(storage: ScratchpadStorage | null, examId: number): void {
  if (!storage) return;
  try {
    storage.removeItem(scratchpadStorageKey(examId));
  } catch {
    /* 同上：清理尽力而为 */
  }
}

/**
 * 把账本渲染到 2D 上下文：每笔画独立 save/restore，橡皮擦用 destination-out
 * 复合模式擦除底层笔迹。单点笔画补一个 0.01px 的线段，保证圆帽落点在主流
 * 浏览器都可见。上下文不可用（jsdom / 极端环境）由调用方守卫。
 */
export function renderScratchpadStrokes(
  ctx: CanvasRenderingContext2D,
  strokes: readonly ScratchpadStroke[]
): void {
  for (const stroke of strokes) {
    const first = stroke.points[0];
    if (!first) continue;
    ctx.save();
    if (stroke.mode === 'eraser') {
      ctx.globalCompositeOperation = 'destination-out';
      ctx.strokeStyle = '#000000';
    } else {
      ctx.strokeStyle = stroke.color;
    }
    ctx.lineWidth = stroke.width;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(first.x, first.y);
    if (stroke.points.length === 1) {
      ctx.lineTo(first.x + 0.01, first.y);
    } else {
      for (let i = 1; i < stroke.points.length; i += 1) {
        ctx.lineTo(stroke.points[i].x, stroke.points[i].y);
      }
    }
    ctx.stroke();
    ctx.restore();
  }
}
