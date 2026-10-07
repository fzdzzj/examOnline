/**
 * 转班目标班级下拉的全量候选用例（fix-frontend-list-truncation-family，U-2 截断家族）。
 *
 * 断言口径：
 * - 弹层打开后才发起候选查询（既有 enabled 门控零改动）；
 * - 目标班级按「满页续拉」发到第 2 页，请求参数按 types.gen.ts 的 page/size 契约形状携带；
 * - 135 个班级全部成为可选目标（原缺陷：第 101 个班级起在转班下拉里静默不存在）；
 * - 词法护栏：该下拉经共享的 `fetchAllPages` 取数，页面不再出现「page:1 + size:100」单页形状。
 *
 * 页面通过 mock 契约层（@/api/axios）与数据层（@tanstack/vue-query）挂载；
 * `createClassRoster` 是纯逻辑 + 依赖注入（deps 全部落在上述 mock 上），因此不 mock 它，
 * 让挂载保持真实链路。pageClasses 由同一端点服务班级表与转班候选，
 * mock 按 size 分发（候选查询 size=100，表格查询 size=10），互不串味。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { message } from 'ant-design-vue';

import * as api from '@/api/axios';
import ClassesPage from '@/pages/(dashboard)/teacher/classes/index.page.vue';
import classesPageSource from '@/pages/(dashboard)/teacher/classes/index.page.vue?raw';

// ---- 各 mock 模块需要跨 beforeEach 共享的状态（vi.hoisted 提到 mock 之前） ----
const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  create4: vi.fn(),
  delete3: vi.fn(),
  joinStudent: vi.fn(),
  listStudents: vi.fn(),
  page3: vi.fn(),
  removeStudent: vi.fn(),
  transfer: vi.fn(),
  update3: vi.fn(),
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

/** 转班候选：135 个班级按页切成「满页 100 + 尾页 35」；班级表格另用 size=10 */
const TARGET_PAGES = Array.from({ length: 2 }, (_, p) =>
  Array.from({ length: p === 0 ? 100 : 35 }, (_, i) => ({
    id: p * 100 + i + 1,
    name: `班级${p * 100 + i + 1}`,
  }))
);
const TABLE_ROWS = [{ id: 7, name: '一班' }];

function targetQueries() {
  return vi
    .mocked(api.page3)
    .mock.calls.map((call) => call[0])
    .filter((arg) => arg?.query?.size === 100)
    .map((arg) => arg?.query);
}

function targetOptions(wrapper: ReturnType<typeof mount>) {
  return (wrapper.vm as unknown as { transferTargetOptions: unknown[] }).transferTargetOptions;
}

beforeEach(() => {
  invalidateQueries.mockClear();
  vi.mocked(api.page3).mockReset();
  vi.mocked(api.listStudents).mockReset();
  vi.mocked(api.page3).mockImplementation(((arg: { query: { page: number; size: number } }) => {
    const { page, size } = arg.query;
    if (size === 100) {
      return Promise.resolve({
        list: TARGET_PAGES[page - 1] ?? [],
        total: 135,
        page,
        size,
      });
    }
    return Promise.resolve({ list: TABLE_ROWS, total: TABLE_ROWS.length, page, size });
  }) as never);
  vi.mocked(api.listStudents).mockResolvedValue([] as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('转班目标下拉取全量班级（classes/index.page）', () => {
  it('弹层未打开时不取候选：班级表格照常按 size=10 取数，无 size=100 请求', async () => {
    mount(ClassesPage);
    await flushPromises();

    expect(targetQueries()).toEqual([]);
    expect(vi.mocked(api.page3)).toHaveBeenCalled();
  });

  it('弹层打开后满页续拉：发 2 次 size=100 请求（page 1/2），135 个班级全部可选', async () => {
    const wrapper = mount(ClassesPage);
    await flushPromises();

    (wrapper.vm as unknown as { transferModalOpen: boolean }).transferModalOpen = true;
    await flushPromises();

    await vi.waitFor(() => expect(targetOptions(wrapper)).toHaveLength(135));
    expect(targetQueries()).toEqual([
      { page: 1, size: 100 },
      { page: 2, size: 100 },
    ]);
  });

  it('候选下拉走共享累加器，页面不再出现单页取数形状', () => {
    expect(classesPageSource).toContain('fetchAllPages');
    expect(classesPageSource).not.toMatch(/query:\s*\{\s*page:\s*1,\s*size:\s*100\s*\}/);
  });
});
