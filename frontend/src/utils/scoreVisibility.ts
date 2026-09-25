/**
 * 学生成绩可见性映射（纯函数，vitest 靶子）。
 *
 * ⚠️ 硬约定：可见性**完全由后端返回决定**，前端不得本地推断：
 * - 后端 myScore 对非 PUBLISHED 考试统一抛 400「成绩待发布」（不区分批改进度，防泄露）；
 * - 有进行中的复核申请时后端置 `reviewing: true` 并把三处分数置 null、rank 置 0——
 *   前端只看 `reviewing` 字段，**绝不**自行判断「是否处于复核中」。
 */

import type { MakeupFinalScoreResponse, MyScoreResponse } from '@/api/axios';
import { BizCode } from '@/api/errorMap';
import { ApiError } from '@/api/types';

/** 成绩卡片的渲染视图：三态 + 明细。 */
export type ScoreView =
  | { kind: 'not-published' }
  | { kind: 'reviewing' }
  | {
      kind: 'published';
      examTitle?: string;
      objectiveScore?: number;
      subjectiveScore?: number;
      totalScore?: number;
      rank?: number;
      partialGraded?: number;
    };

/**
 * 后端「成绩待发布」错误的判别：myScore 在考试未发布/已撤回时统一返回
 * 400 + message「成绩待发布」（ScoreService 固定文案）。
 * 识别到即渲染为「未发布」态，而不是当错误弹给用户。
 */
export function isNotPublishedError(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.code === BizCode.BAD_REQUEST &&
    error.message.includes('成绩待发布')
  );
}

/**
 * 把 myScore 响应映射为三态视图。
 * - `reviewing === true` → 复核中（分数已被后端置空，前端原样隐藏，不显示 0 分）；
 * - 其余按已发布渲染，分数字段直接透传（后端给的 null 保持 undefined，不补 0）。
 */
export function mapMyScoreToView(response: MyScoreResponse | undefined): ScoreView {
  if (!response) {
    return { kind: 'not-published' };
  }
  if (response.reviewing === true) {
    // 后端裁决复核进行中：分数字段已置空，这里绝不能从别处推断回填
    return { kind: 'reviewing' };
  }
  return {
    kind: 'published',
    examTitle: response.examTitle,
    objectiveScore: response.objectiveScore,
    subjectiveScore: response.subjectiveScore,
    totalScore: response.totalScore,
    rank: response.rank,
    partialGraded: response.partialGraded,
  };
}

/**
 * 把补考最终成绩（GET /api/scores/makeup-final，学生查本人）映射为**同一套**三态视图，
 * 口径与 myScore 完全同构（差异只在后端实现，前端不引入第三种状态机）：
 * - 未发布：后端 requirePublishedRoot 对非 PUBLISHED 家族根统一抛 400「成绩待发布」，
 *   与 myScore 同文案——页面继续用 isNotPublishedError 识别后归入 not-published；
 * - `reviewing === true` → 复核中：主考家族存在进行中复核时后端已把 finalScore 置 null
 *   （防「看了分数再申请」），前端只看 reviewing 字段，绝不回填分数；
 * - 其余按已发布渲染：finalScore 透传为 totalScore（后端给 null 保持 undefined，不补 0）；
 *   该响应没有排名 / 客观题 / 主观题字段，一律不编造。
 */
export function mapMakeupFinalScoreToView(
  response: MakeupFinalScoreResponse | undefined
): ScoreView {
  if (!response) {
    return { kind: 'not-published' };
  }
  if (response.reviewing === true) {
    return { kind: 'reviewing' };
  }
  return {
    kind: 'published',
    totalScore: response.finalScore,
  };
}
