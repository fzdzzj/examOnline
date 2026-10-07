/**
 * 分页信封缺 total 时的「逐页累加」取数策略（通用形态）。
 *
 * 为什么抽这一份：教师端除考试下拉外，还有试卷下拉、班级下拉、转班目标下拉三处候选
 * 与试卷列表走的是同一形状的分页端点（只有 `page` / `size`，响应不带 total）。
 * 这四处历史上各写一次 `size: 100` 单页取数——候选超过 100 条时第 101 条起静默不可见
 * 且无任何提示（UX 台账 U-2「截断家族」）。语义与 `useTeacherExams.ts` 里已验收进
 * frontend 基线的那一份逐字一致，本文件是它的泛型形态；`fetchAllTeacherExams` 改为
 * 委托这里，两侧共用同一实现（委托等价性由既有的 `useTeacherExams.spec.ts` 看护）。
 *
 * 计费边界：默认 `pageSize=100`（后端 `size` 上限）、`maxPages=5`——单页 ≤100 时只发
 * 1 次请求，与旧逻辑请求数持平而容量翻倍；>100 时按需发 2–5 次拼全，
 * `maxPages` 兼作分页信封缺 total 时的防死循环边界。
 */

/** 单页 100 条：与后端分页参数上限对齐，避免超出被拒或截断。 */
export const FETCH_ALL_PAGE_SIZE = 100;
/** 最多累积 5 页：既是容量上限，也是分页信封缺 total 时的防死循环边界。 */
export const FETCH_ALL_MAX_PAGES = 5;

/** 分页取数函数：生产为 gen:api 生成的 SDK 函数 + `unwrap`，单测为 mock。 */
export type PageFetcher<T> = (page: number, pageSize: number) => Promise<T[] | undefined>;

export interface FetchAllPagesOptions {
  pageSize?: number;
  maxPages?: number;
}

/**
 * 逐页累加拉取完整候选：从 `page = 1` 起累加，返回空或未满一页即视为到底并提前结束。
 * 取数失败或返回 undefined 时安全兜底——保留已累积的页、不抛出，候选不阻断页面其余功能。
 */
export async function fetchAllPages<T>(
  fetchPage: PageFetcher<T>,
  options: FetchAllPagesOptions = {}
): Promise<T[]> {
  const pageSize = options.pageSize ?? FETCH_ALL_PAGE_SIZE;
  const maxPages = options.maxPages ?? FETCH_ALL_MAX_PAGES;
  const all: T[] = [];

  for (let page = 1; page <= maxPages; page += 1) {
    let batch: T[] | undefined;
    try {
      batch = await fetchPage(page, pageSize);
    } catch {
      break;
    }
    if (!batch || batch.length === 0) break;
    all.push(...batch);
    // 分页信封无 total：未满页说明已到底，提前结束，避免多发一次空请求
    if (batch.length < pageSize) break;
  }

  return all;
}
