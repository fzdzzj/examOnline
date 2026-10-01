/**
 * 组卷相关纯函数（便于 vitest 直测）。
 *
 * 分值语义（已从后端代码核实，src/main/java/com/exam/paper/service/PaperService.java）：
 * paper_questions.score 是「试卷内分值」，可覆盖题目默认分值且互不影响；
 * 后端返回的 PaperQuestionItemResponse 同时带 score（卷内生效分）与 defaultScore（题库默认分）。
 *
 * 精度：后端分值约束 0.5–999.9（1 位小数），求和走 ×10 整数域，规避 0.1+0.2 浮点漂移。
 */
import { typeLabelOf } from '@/utils/questionTypes';

export interface ScoreLike {
  /** 试卷内分值（覆盖分；抽题入卷/未覆盖时可能与默认分相同） */
  score?: number | null;
  /** 题目默认分值 */
  defaultScore?: number | null;
}

const SCALE = 10;

function toTenth(value: number): number {
  return Math.round(value * SCALE);
}

function fromTenth(tenth: number): number {
  return tenth / SCALE;
}

/** 单题生效分值：覆盖分缺省或非有限数时回退默认分，两者皆无按 0 计。 */
export function effectiveScore(item: ScoreLike): number {
  const value = item.score ?? item.defaultScore;
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

/** 各题分值求和（覆盖分优先），结果保留 1 位小数。 */
export function sumPaperScores(items: readonly ScoreLike[]): number {
  const tenth = items.reduce((acc, item) => acc + toTenth(effectiveScore(item)), 0);
  return fromTenth(tenth);
}

/** 把列表项从 from 位移动 delta 位；越界或 delta=0 时返回同序新数组（不改入参）。 */
export function moveItem<T>(list: readonly T[], from: number, delta: number): T[] {
  const target = from + delta;
  if (delta === 0 || from < 0 || from >= list.length || target < 0 || target >= list.length) {
    return [...list];
  }
  const next = [...list];
  const [moved] = next.splice(from, 1);
  next.splice(target, 0, moved);
  return next;
}

export interface DistributionRow {
  type: number;
  typeName: string;
  count: number;
  subtotal: number;
}

/** 按题型聚合的分值分布（试卷只读预览用）；题目已删（questionType 为空）归入「未知题型」。 */
export function scoreDistribution(
  items: ReadonlyArray<ScoreLike & { questionType?: number | null }>
): DistributionRow[] {
  const rows = new Map<number, DistributionRow>();
  for (const item of items) {
    const type = item.questionType ?? -1;
    let row = rows.get(type);
    if (!row) {
      row = { type, typeName: typeLabelOf(item.questionType), count: 0, subtotal: 0 };
      rows.set(type, row);
    }
    row.count += 1;
    row.subtotal = fromTenth(toTenth(row.subtotal) + toTenth(effectiveScore(item)));
  }
  return [...rows.values()];
}
