/**
 * 教师端考试下拉取数策略的用例（合并前端候选 2 & 候选 3）。
 *
 * 两段判据：
 * ① 纯逻辑——分页累加在「空列表 / 未满页 / 恰好满页 + 空页 / 多页累积 / 达上限 / 取数失败」
 *    六种边界下都不丢页、不多发空请求、不死循环；
 * ② 词法护栏——直接读页面源码，断言旧 bug 标记（`examPage.value * PAGE_SIZE`、`const PAGE_SIZE = 50`）
 *    已被移除且页面确实改走 `createTeacherExamsQueryOptions`。行为单测拦不住「页面悄悄回退到旧分页」，
 *    只有对源码的断言能拦住（先例：`useExamMonitor.spec.ts` 的页面接线词法护栏、后端
 *    `PublisherConfirmScopeGuardTest`）。
 */
import { describe, expect, it, vi } from 'vitest';

import type { ExamResponse } from '@/api/axios';
import {
  TEACHER_EXAMS_MAX_PAGES,
  TEACHER_EXAMS_PAGE_SIZE,
  createTeacherExamsQueryOptions,
  fetchAllTeacherExams,
  type TeacherExamsPageFetcher,
} from '@/hooks/useTeacherExams';
import scoresPageSource from '@/pages/(dashboard)/teacher/scores/index.page.vue?raw';
import gradingPageSource from '@/pages/(dashboard)/teacher/grading/index.page.vue?raw';

function makeExams(count: number, offset = 0): ExamResponse[] {
  return Array.from({ length: count }, (_, i) => ({
    id: offset + i + 1,
    title: `考试${offset + i + 1}`,
  }));
}

/** 按页号分发的 mock 取数：pages[0] 对应 page=1；越界返回空数组。 */
function pagedFetcher(pages: ExamResponse[][]): TeacherExamsPageFetcher {
  return vi.fn(async (page: number) => pages[page - 1] ?? []);
}

describe('fetchAllTeacherExams 分页累加', () => {
  it('空列表：单次返回空即终止，结果为 []，只发 1 次请求', async () => {
    const fetchPage = pagedFetcher([[]]);

    await expect(fetchAllTeacherExams(fetchPage)).resolves.toEqual([]);
    expect(fetchPage).toHaveBeenCalledTimes(1);
    expect(fetchPage).toHaveBeenCalledWith(1, TEACHER_EXAMS_PAGE_SIZE);
  });

  it('单页未满（40 < 100）：单次返回即终止，结果为 40 条，只发 1 次请求', async () => {
    const fetchPage = pagedFetcher([makeExams(40)]);

    const result = await fetchAllTeacherExams(fetchPage);
    expect(result).toHaveLength(40);
    expect(fetchPage).toHaveBeenCalledTimes(1);
  });

  it('恰好满页后次页为空（100 + 0）：次页为空时终止，结果 100 条，发 2 次请求', async () => {
    const fetchPage = pagedFetcher([makeExams(100), []]);

    const result = await fetchAllTeacherExams(fetchPage);
    expect(result).toHaveLength(100);
    expect(fetchPage).toHaveBeenCalledTimes(2);
  });

  it('多页累积（100 + 35）：两页拼全为 135 条，发 2 次请求', async () => {
    const fetchPage = pagedFetcher([makeExams(100), makeExams(35, 100)]);

    const result = await fetchAllTeacherExams(fetchPage);
    expect(result).toHaveLength(135);
    expect(result[0]?.id).toBe(1);
    expect(result[134]?.id).toBe(135);
    expect(fetchPage).toHaveBeenCalledTimes(2);
  });

  it('持续满页时在 maxPages 处强制终止，不陷入死循环', async () => {
    const fetchPage = pagedFetcher([
      makeExams(100),
      makeExams(100),
      makeExams(100),
      makeExams(100),
      makeExams(100),
    ]);

    const result = await fetchAllTeacherExams(fetchPage);
    expect(result).toHaveLength(TEACHER_EXAMS_PAGE_SIZE * TEACHER_EXAMS_MAX_PAGES);
    expect(fetchPage).toHaveBeenCalledTimes(TEACHER_EXAMS_MAX_PAGES);
  });

  it('maxPages 可覆盖：自定义 3 页上限时只发 3 次请求', async () => {
    const fetchPage = pagedFetcher([
      makeExams(100),
      makeExams(100),
      makeExams(100),
      makeExams(100),
    ]);

    const result = await fetchAllTeacherExams(fetchPage, { maxPages: 3 });
    expect(result).toHaveLength(300);
    expect(fetchPage).toHaveBeenCalledTimes(3);
  });

  it('pageSize 可覆盖：未满自定义页大小即终止', async () => {
    const fetchPage = pagedFetcher([makeExams(100), makeExams(35, 100)]);

    const result = await fetchAllTeacherExams(fetchPage, { pageSize: 100 });
    expect(result).toHaveLength(135);

    const fetchDefault = pagedFetcher([makeExams(40)]);
    await expect(fetchAllTeacherExams(fetchDefault, { pageSize: 40 })).resolves.toHaveLength(40);
  });

  it('取数抛错时安全兜底：保留已累积的页，不抛出异常', async () => {
    const throwing = vi.fn(async (page: number) => {
      if (page === 1) return makeExams(100);
      throw new Error('网络中断');
    });

    await expect(fetchAllTeacherExams(throwing)).resolves.toHaveLength(100);
    expect(throwing).toHaveBeenCalledTimes(2);
  });

  it('首页取数即抛错时安全兜底，返回空数组', async () => {
    const throwing = vi.fn().mockRejectedValue(new Error('网络中断'));

    await expect(fetchAllTeacherExams(throwing)).resolves.toEqual([]);
    expect(throwing).toHaveBeenCalledTimes(1);
  });

  it('取数返回 undefined 时安全兜底，返回空数组', async () => {
    const fetchPage = vi.fn().mockResolvedValue(undefined);

    await expect(fetchAllTeacherExams(fetchPage)).resolves.toEqual([]);
    expect(fetchPage).toHaveBeenCalledTimes(1);
  });
});

describe('createTeacherExamsQueryOptions', () => {
  it('queryKey 首元素固定为 exams，保留既有测试 mock 口径的向后兼容', () => {
    const options = createTeacherExamsQueryOptions(pagedFetcher([[]]));
    expect(options.queryKey).toEqual(['exams', 'teacher-dropdown']);
    expect(options.queryKey[0]).toBe('exams');
  });

  it('staleTime 为 30s：跨页签 / 跨功能复用同一份缓存', () => {
    expect(createTeacherExamsQueryOptions(pagedFetcher([[]])).staleTime).toBe(30_000);
  });

  it('queryFn 走统一的 fetchAllTeacherExams 累加逻辑', async () => {
    const fetchPage = pagedFetcher([makeExams(100), makeExams(35, 100)]);
    const options = createTeacherExamsQueryOptions(fetchPage);

    await expect(options.queryFn()).resolves.toHaveLength(135);
  });
});

describe('页面接线词法护栏', () => {
  const pages = [
    { name: 'teacher/scores', source: scoresPageSource },
    { name: 'teacher/grading', source: gradingPageSource },
  ];

  it.each(pages)('$name 不再包含旧分页 bug 标记 examPage.value * PAGE_SIZE', ({ source }) => {
    expect(source).not.toContain('examPage.value * PAGE_SIZE');
  });

  it.each(pages)('$name 不再包含 const PAGE_SIZE = 50', ({ source }) => {
    expect(source).not.toContain('const PAGE_SIZE = 50');
  });

  it.each(pages)('$name 改为消费 createTeacherExamsQueryOptions', ({ source }) => {
    expect(source).toContain('createTeacherExamsQueryOptions');
  });
});
