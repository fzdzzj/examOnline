/**
 * 通用多页累加器用例（fix-frontend-list-truncation-family，UX 台账 U-2）。
 *
 * 六场景形态逐一对齐 `useTeacherExams.spec.ts`：空页 / 未满页 / 恰满页+空页 / 多页累积 /
 * 达上限 / 失败保留。语义必须与已合入 frontend 基线的考试下拉累加器**逐字一致**——
 * 本卡把它抽成一份通用实现、`useTeacherExams` 改为一行委托，那份既有用例继续充当
 * 委托等价性护栏，这里只验被抽出来的这一份。
 */
import { describe, expect, it, vi } from 'vitest';

import {
  FETCH_ALL_MAX_PAGES,
  FETCH_ALL_PAGE_SIZE,
  fetchAllPages,
  type PageFetcher,
} from '@/hooks/fetchAllPages';

interface Probe {
  id: number;
}

function makeRows(count: number, offset = 0): Probe[] {
  return Array.from({ length: count }, (_, i) => ({ id: offset + i + 1 }));
}

/** 按页号分发的 mock 取数：pages[0] 对应 page=1；越界返回空数组。 */
function pagedFetcher(pages: Probe[][]): PageFetcher<Probe> {
  return vi.fn(async (page: number) => pages[page - 1] ?? []);
}

describe('fetchAllPages 分页累加', () => {
  it('空列表：单次返回空即终止，结果为 []，只发 1 次请求', async () => {
    const fetchPage = pagedFetcher([[]]);

    await expect(fetchAllPages(fetchPage)).resolves.toEqual([]);
    expect(fetchPage).toHaveBeenCalledTimes(1);
    expect(fetchPage).toHaveBeenCalledWith(1, FETCH_ALL_PAGE_SIZE);
  });

  it('单页未满（40 < 100）：单次返回即终止，结果为 40 条，只发 1 次请求', async () => {
    const fetchPage = pagedFetcher([makeRows(40)]);

    const result = await fetchAllPages(fetchPage);
    expect(result).toHaveLength(40);
    expect(fetchPage).toHaveBeenCalledTimes(1);
  });

  it('恰好满页后次页为空（100 + 0）：次页为空时终止，结果 100 条，发 2 次请求', async () => {
    const fetchPage = pagedFetcher([makeRows(100), []]);

    const result = await fetchAllPages(fetchPage);
    expect(result).toHaveLength(100);
    expect(fetchPage).toHaveBeenCalledTimes(2);
  });

  it('多页累积（100 + 35）：两页拼全为 135 条，发 2 次请求', async () => {
    const fetchPage = pagedFetcher([makeRows(100), makeRows(35, 100)]);

    const result = await fetchAllPages(fetchPage);
    expect(result).toHaveLength(135);
    expect(result[0]?.id).toBe(1);
    expect(result[134]?.id).toBe(135);
    expect(fetchPage).toHaveBeenCalledTimes(2);
  });

  it('持续满页时在 maxPages 处强制终止，不陷入死循环', async () => {
    const fetchPage = pagedFetcher([
      makeRows(100),
      makeRows(100),
      makeRows(100),
      makeRows(100),
      makeRows(100),
      makeRows(100),
    ]);

    const result = await fetchAllPages(fetchPage);
    expect(result).toHaveLength(FETCH_ALL_PAGE_SIZE * FETCH_ALL_MAX_PAGES);
    expect(fetchPage).toHaveBeenCalledTimes(FETCH_ALL_MAX_PAGES);
  });

  it('maxPages 可覆盖：自定义 3 页上限时只发 3 次请求', async () => {
    const fetchPage = pagedFetcher([makeRows(100), makeRows(100), makeRows(100), makeRows(100)]);

    const result = await fetchAllPages(fetchPage, { maxPages: 3 });
    expect(result).toHaveLength(300);
    expect(fetchPage).toHaveBeenCalledTimes(3);
  });

  it('pageSize 可覆盖：满页判定随自定义页大小改变', async () => {
    // 自定义 pageSize=40：首页 40 条即「满页」→ 续拉第 2 页，尾页 20 条未满即停
    const fetchPage = pagedFetcher([makeRows(40), makeRows(20, 40)]);
    const result = await fetchAllPages(fetchPage, { pageSize: 40 });
    expect(result).toHaveLength(60);
    expect(fetchPage).toHaveBeenCalledTimes(2);
    expect(fetchPage).toHaveBeenNthCalledWith(2, 2, 40);

    // 同样 40 条在默认 pageSize=100 下属未满页 → 单次即停，不多发一次空请求
    const underDefault = pagedFetcher([makeRows(40)]);
    await expect(fetchAllPages(underDefault)).resolves.toHaveLength(40);
    expect(underDefault).toHaveBeenCalledTimes(1);
  });

  it('取数抛错时安全兜底：保留已累积的页，不抛出异常', async () => {
    const throwing = vi.fn(async (page: number) => {
      if (page === 1) return makeRows(100);
      throw new Error('网络中断');
    });

    await expect(fetchAllPages(throwing)).resolves.toHaveLength(100);
    expect(throwing).toHaveBeenCalledTimes(2);
  });

  it('首页取数即抛错时安全兜底，返回空数组', async () => {
    const throwing = vi.fn().mockRejectedValue(new Error('网络中断'));

    await expect(fetchAllPages(throwing)).resolves.toEqual([]);
    expect(throwing).toHaveBeenCalledTimes(1);
  });

  it('取数返回 undefined 时安全兜底，返回空数组', async () => {
    const fetchPage = vi.fn().mockResolvedValue(undefined);

    await expect(fetchAllPages(fetchPage)).resolves.toEqual([]);
    expect(fetchPage).toHaveBeenCalledTimes(1);
  });
});
