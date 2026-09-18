/**
 * 题库列表筛选参数构造（纯函数，便于 vitest 直测）。
 * 出参形状直接取自 gen:api 契约 `PageData['query']`（GET /api/questions），
 * 不手写重复的参数模型（硬约定：接口定义与类型一律来自生成客户端）。
 */
import type { PageData } from '@/api/axios';

export type QuestionPageQuery = NonNullable<PageData['query']>;

export interface QuestionFilterInput {
  page?: number;
  size?: number;
  type?: number | null;
  difficulty?: number | null;
  tagId?: number | null;
  keyword?: string | null;
}

/**
 * 构造分页查询参数：
 * - 空值筛选不下发（后端按 null 跳过该条件）；
 * - keyword 去首尾空白，空白视作未筛选；
 * - page/size 夹取到后端合法区间（page≥1，1≤size≤100，后端对 size 做 Math.min(size, 100)）。
 */
export function buildQuestionPageQuery(input: QuestionFilterInput): QuestionPageQuery {
  const page = Math.max(1, Math.trunc(input.page ?? 1));
  const size = Math.min(100, Math.max(1, Math.trunc(input.size ?? 10)));
  const query: QuestionPageQuery = { page, size };
  if (input.type !== null && input.type !== undefined) {
    query.type = input.type;
  }
  if (input.difficulty !== null && input.difficulty !== undefined) {
    query.difficulty = input.difficulty;
  }
  if (input.tagId !== null && input.tagId !== undefined) {
    query.tagId = input.tagId;
  }
  const keyword = input.keyword?.trim();
  if (keyword) {
    query.keyword = keyword;
  }
  return query;
}
