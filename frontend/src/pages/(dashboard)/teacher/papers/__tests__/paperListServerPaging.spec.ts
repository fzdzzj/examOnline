/**
 * 试卷列表服务端分页用例（fix-frontend-list-truncation-family，U-2 截断家族第 4 点）。
 *
 * 断言口径：
 * - 首屏按服务端分页只取第 1 页（size=10），而不是「一次取回 100 条再本地切片」；
 * - 翻页发起**真实请求**（queryKey 随当前页变化 → 重新取数），渲染的是第 2 页返回的行；
 * - 分页器在无 total 信封下按「满页则至少还有下一页」做下界推断——第 2 页必须可达
 *   （否则「翻页真实请求」这条断言永远不会被触发；考试页的字面 total 写法正是这个死角）；
 * - 词法护栏：源码不再出现 FETCH_SIZE 与本地 slice；
 * - 查询失败以错误 Alert 显性呈现后端 message，不落「暂无数据」空态（U-1 三态分离口径）。
 *
 * 页面通过 mock 契约层（@/api/axios）与数据层（@tanstack/vue-query）挂载，
 * mock 端按页对 250 条做服务端切片，以还原「后端分页」的真实形状。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { Table, message } from 'ant-design-vue';

import * as api from '@/api/axios';
import { ApiError } from '@/api/types';
import PapersIndexPage from '@/pages/(dashboard)/teacher/papers/index.page.vue';
import papersPageSource from '@/pages/(dashboard)/teacher/papers/index.page.vue?raw';

// ---- 各 mock 模块需要跨 beforeEach 共享的状态（vi.hoisted 提到 mock 之前） ----
const routerMock = vi.hoisted(() => ({ push: vi.fn(), back: vi.fn() }));
const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => ({ query: {} }) }));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  page1: vi.fn(),
  delete1: vi.fn(),
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

/** 后端共 250 份试卷：mock 端按 page/size 做服务端切片 */
const ALL_PAPERS = Array.from({ length: 250 }, (_, i) => ({
  id: i + 1,
  title: `试卷${i + 1}`,
  questionCount: 5,
  totalScore: 100,
  status: 0,
  createdTime: '2026-10-01T09:00:00',
}));

function servedPages() {
  return vi.mocked(api.page1).mock.calls.map((call) => call[0]?.query);
}

function titleCells(wrapper: ReturnType<typeof mount>) {
  return wrapper
    .findAll('tbody tr')
    .filter((tr) => tr.text().trim().length > 0)
    .map((tr) => tr.findAll('td')[0]?.text() ?? '');
}

beforeEach(() => {
  routerMock.push.mockClear();
  invalidateQueries.mockClear();
  vi.mocked(api.page1).mockReset();
  vi.mocked(api.page1).mockImplementation(((arg: { query: { page: number; size: number } }) => {
    const { page, size } = arg.query;
    const start = (page - 1) * size;
    return Promise.resolve(ALL_PAPERS.slice(start, start + size));
  }) as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('试卷列表服务端分页（papers/index.page）', () => {
  it('首屏按服务端取第 1 页 10 条，且分页器让第 2 页可达', async () => {
    const wrapper = mount(PapersIndexPage);
    await flushPromises();
    await flushPromises();

    expect(servedPages()).toEqual([{ page: 1, size: 10 }]);
    await vi.waitFor(() => expect(titleCells(wrapper)).toHaveLength(10));
    expect(titleCells(wrapper)[0]).toBe('试卷1');
    expect(titleCells(wrapper)[9]).toBe('试卷10');
    // 下界推断生效：满页时下一页存在（字面 total=当页条数 会让这一页永远点不到）
    expect(wrapper.find('.ant-pagination-item-2').exists()).toBe(true);
  });

  it('翻第 2 页发起真实请求并渲染第 2 页返回的行', async () => {
    const wrapper = mount(PapersIndexPage);
    await flushPromises();
    await flushPromises();
    await vi.waitFor(() => expect(titleCells(wrapper)).toHaveLength(10));

    wrapper.findComponent(Table).vm.$emit('change', { current: 2, pageSize: 10 });
    await flushPromises();

    await vi.waitFor(() => expect(servedPages()).toHaveLength(2));
    expect(servedPages()[1]).toEqual({ page: 2, size: 10 });
    await vi.waitFor(() => expect(titleCells(wrapper)[0]).toBe('试卷11'));
    expect(titleCells(wrapper)).toHaveLength(10);
    expect(titleCells(wrapper)[9]).toBe('试卷20');
  });

  it('源码不再一次取回后本地切片', () => {
    expect(papersPageSource).not.toContain('FETCH_SIZE');
    expect(papersPageSource).not.toContain('.slice(');
  });

  it('查询失败：错误 Alert 显性呈现后端 message，空态不出现', async () => {
    vi.mocked(api.page1).mockRejectedValue(new ApiError(500, '服务器内部错误', undefined, 500));
    const wrapper = mount(PapersIndexPage);
    await flushPromises();
    await flushPromises();

    const alert = wrapper.find('[data-test="papers-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('服务器内部错误');
    expect(wrapper.text()).not.toContain('暂无数据');
  });
});
