/**
 * 考务创建页两个下拉的全量候选用例（fix-frontend-list-truncation-family，U-2 截断家族）。
 *
 * 断言口径：
 * - 试卷下拉与班级下拉都按「满页续拉」发到第 2 页，请求参数按 types.gen.ts 的 page/size 契约形状携带；
 * - 135 条候选全部成为该下拉的可选项（截断家族的原缺陷是第 101 条起静默不可见）；
 * - 词法护栏：两个下拉都经共享的 `fetchAllPages` 取数，页面里不再出现「page:1 + size:100」的单页取数
 *   （台账纪律：本家族并入同一次取数决策，不分别叠加实现）。
 *
 * 页面通过 mock 契约层（@/api/axios）与数据层（@tanstack/vue-query）挂载；useQuery mock
 * 真实调用页面 queryFn，并按 queryKey / enabled 的变化重跑（对齐真实框架语义）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { message } from 'ant-design-vue';

import * as api from '@/api/axios';
import ExamCreatePage from '@/pages/(dashboard)/teacher/exams/create.page.vue';
import createPageSource from '@/pages/(dashboard)/teacher/exams/create.page.vue?raw';

// ---- 各 mock 模块需要跨 beforeEach 共享的状态（vi.hoisted 提到 mock 之前） ----
const routerMock = vi.hoisted(() => ({ push: vi.fn(), back: vi.fn() }));
const routeMock = vi.hoisted(() => ({ query: {} as Record<string, unknown> }));
const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => routeMock }));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  create3: vi.fn(),
  page1: vi.fn(),
  page3: vi.fn(),
  detail2: vi.fn(),
  update2: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref, watch } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown; queryFn: () => unknown; enabled?: unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      const run = () => {
        Promise.resolve()
          .then(options.queryFn)
          .then((value: unknown) => {
            data.value = value;
          })
          .catch((reason: unknown) => {
            error.value = reason;
          });
      };
      watch(
        () => [unref(options.queryKey), unref(options.enabled ?? true)] as const,
        ([, enabled]) => {
          if (enabled) run();
        },
        { immediate: true }
      );
      return { data, error, isFetching: ref(false), refetch: vi.fn() };
    },
  };
});

/** 135 条候选：服务端按页切成「满页 100 + 尾页 35」——截断家族的触顶形状 */
const PAPER_PAGES = Array.from({ length: 2 }, (_, p) =>
  Array.from({ length: p === 0 ? 100 : 35 }, (_, i) => ({
    id: p * 100 + i + 1,
    title: `试卷${p * 100 + i + 1}`,
  }))
);
const CLASS_PAGES = Array.from({ length: 2 }, (_, p) =>
  Array.from({ length: p === 0 ? 100 : 35 }, (_, i) => ({
    id: p * 100 + i + 1,
    name: `班级${p * 100 + i + 1}`,
  }))
);

function paperQueries() {
  return vi.mocked(api.page1).mock.calls.map((call) => call[0]?.query);
}
function classQueries() {
  return vi.mocked(api.page3).mock.calls.map((call) => call[0]?.query);
}

function optionsOf(wrapper: ReturnType<typeof mount>, key: 'paperOptions' | 'classOptions') {
  return (wrapper.vm as unknown as Record<string, unknown[]>)[key];
}

beforeEach(() => {
  routerMock.push.mockClear();
  invalidateQueries.mockClear();
  vi.mocked(api.page1).mockReset();
  vi.mocked(api.page3).mockReset();
  vi.mocked(api.page1).mockImplementation(((arg: { query: { page: number } }) =>
    Promise.resolve(PAPER_PAGES[arg.query.page - 1] ?? [])) as never);
  vi.mocked(api.page3).mockImplementation(((arg: { query: { page: number } }) => {
    const list = CLASS_PAGES[arg.query.page - 1] ?? [];
    return Promise.resolve({ list, total: 135, page: arg.query.page, size: 100 });
  }) as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('考务创建页下拉取全量候选（create.page）', () => {
  it('试卷下拉满页后续拉第 2 页：发 2 次请求（page 1/2，size 100），135 条全部可选', async () => {
    const wrapper = mount(ExamCreatePage);
    await flushPromises();

    await vi.waitFor(() => expect(optionsOf(wrapper, 'paperOptions')).toHaveLength(135));
    expect(paperQueries()).toEqual([
      { page: 1, size: 100 },
      { page: 2, size: 100 },
    ]);
    expect(vi.mocked(api.page1)).toHaveBeenCalledTimes(2);
  });

  it('班级下拉满页后续拉第 2 页：发 2 次请求（page 1/2，size 100），135 条全部可选', async () => {
    const wrapper = mount(ExamCreatePage);
    await flushPromises();

    await vi.waitFor(() => expect(optionsOf(wrapper, 'classOptions')).toHaveLength(135));
    expect(classQueries()).toEqual([
      { page: 1, size: 100 },
      { page: 2, size: 100 },
    ]);
    expect(vi.mocked(api.page3)).toHaveBeenCalledTimes(2);
  });

  it('两个下拉都走共享累加器，页面不再出现单页取数形状', () => {
    expect(createPageSource).toContain('fetchAllPages');
    expect(createPageSource).not.toMatch(/query:\s*\{\s*page:\s*1,\s*size:\s*100\s*\}/);
  });
});
