/**
 * 教师端「考试下拉」取数与翻页策略的唯一收口点。
 *
 * 为什么单独成文件：教师端多个考后页面（成绩 / 批改 / 复核 / 缺考 / 补考）都需要
 * 「列出教师自己的考试」这一份候选。历史上各页各自写分页，策略相反且各有缺陷：
 * - 成绩页只取第一页（教师考试 > 50 时更早的考试无法选中）；
 * - 批改页用 `watch(exams)` 满页即再拉一页，但覆盖式写入会丢掉已累积的前页。
 * 本模块把考试下拉的取数策略收敛为一处，页面只消费 `createTeacherExamsQueryOptions`
 * 的返回值；逐页累加的语义本体在 `fetchAllPages.ts`（试卷 / 班级下拉与转班目标共用那一份）。
 *
 * 计费边界：分页信封无 total，无法预知总页数。默认 `pageSize=100`（后端 `size` 上限）、
 * `maxPages=5`（单人至多 500 场考试）——教师考试 ≤ 100 场时只发 1 次请求，与旧逻辑
 * 请求数持平而容量翻倍；> 100 场时按需发 2–5 次拼全，且 `maxPages` 兼作死循环保护。
 */
import type { ExamResponse } from '@/api/axios';

import { fetchAllPages } from './fetchAllPages';

/** 单页 100 条：与后端分页参数上限对齐，避免超出被拒或截断。 */
export const TEACHER_EXAMS_PAGE_SIZE = 100;
/** 最多累积 5 页（500 场考试）：既是容量上限，也是分页信封缺 total 时的防死循环边界。 */
export const TEACHER_EXAMS_MAX_PAGES = 5;

/** 分页取数函数：生产为 gen:api `page2` + `unwrap`，单测为 mock。 */
export type TeacherExamsPageFetcher = (
  page: number,
  pageSize: number
) => Promise<ExamResponse[] | undefined>;

export interface TeacherExamsFetchOptions {
  pageSize?: number;
  maxPages?: number;
}

export interface TeacherExamsQueryOptions {
  queryKey: readonly ['exams', 'teacher-dropdown'];
  queryFn: () => Promise<ExamResponse[]>;
  /** 30s 内跨页签 / 跨功能共享同一份缓存，避免 scores/grading/reviews 重复发网络请求 */
  staleTime: number;
}

/**
 * 逐页累加拉取完整候选：委托 `fetchAllPages`（语义见该文件），页大小与页数上限
 * 仍取本模块自身的 `TEACHER_EXAMS_*` 默认值。签名与行为对既有调用点零变化。
 */
export async function fetchAllTeacherExams(
  fetchPage: TeacherExamsPageFetcher,
  options: TeacherExamsFetchOptions = {}
): Promise<ExamResponse[]> {
  return fetchAllPages<ExamResponse>(fetchPage, {
    pageSize: options.pageSize ?? TEACHER_EXAMS_PAGE_SIZE,
    maxPages: options.maxPages ?? TEACHER_EXAMS_MAX_PAGES,
  });
}

/**
 * Vue Query 选项封装：`queryKey` 首元素固定为 `'exams'`，与既有页面/测试的缓存口径一致。
 */
export function createTeacherExamsQueryOptions(
  fetchPage: TeacherExamsPageFetcher,
  options: TeacherExamsFetchOptions = {}
): TeacherExamsQueryOptions {
  return {
    queryKey: ['exams', 'teacher-dropdown'] as const,
    queryFn: () => fetchAllTeacherExams(fetchPage, options),
    staleTime: 30_000,
  };
}
