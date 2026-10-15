/**
 * 标签列表查询失败三态（harden-data-sanitization-and-ui-states M-03，U-1 口径在 tags 页的补全）。
 *
 * 现状缺陷：标签列表 `useQuery` 只解构 { data, isFetching }，未消费 error；
 * 查询失败时 data=undefined → rows=[]，落 antd 内置空态，与「确实没有标签」不可区分。
 *
 * 守住：
 * - 查询失败 → 错误 Alert（data-test="tags-error"）显性呈现后端 message，
 *   且主数据 Table 不渲染（失败不得伪装成空表）；
 * - 成功且空数组 → 既有空态照常呈现、不弹错误 Alert；
 * - 成功且返回数据 → Table 渲染标签行零回归。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

import TagsPage from '@/pages/(dashboard)/teacher/tags/index.page.vue';

// ---- 各 mock 模块需要跨 beforeEach 共享的状态（vi.hoisted 提到 mock 之前） ----
const queryStore = vi.hoisted(() => ({
  data: {} as Record<string, unknown>,
  error: {} as Record<string, unknown>,
}));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: vi.fn() },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'tags-query-failure' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  create: vi.fn(),
  delete4: vi.fn(),
  list: vi.fn(),
}));

/** useQuery mock：按 queryKey 首元素从 queryStore 取 data/error（查询失败经 error 槽注入）。 */
vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown }) => {
      const key = unref(options.queryKey) as unknown[];
      return {
        data: ref(queryStore.data[String(key[0])]),
        error: ref(queryStore.error[String(key[0])] ?? null),
        isFetching: ref(false),
        refetch: vi.fn(),
      };
    },
  };
});

function mountPage(overrides?: {
  data?: Record<string, unknown>;
  error?: Record<string, unknown>;
}) {
  queryStore.data = { tags: [], ...(overrides?.data ?? {}) };
  queryStore.error = { ...(overrides?.error ?? {}) };
  return mount(TagsPage);
}

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('标签列表查询失败三态（M-03）', () => {
  it('查询失败：错误 Alert 显性呈现后端 message，主数据 Table 不渲染', async () => {
    const wrapper = mountPage({ error: { tags: new Error('标签列表加载失败') } });
    await flushPromises();

    const alert = wrapper.find('[data-test="tags-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('标签列表加载失败');
    // 失败不得伪装成「没有标签」：主表格与 antd 空态均不出现
    expect(wrapper.findComponent({ name: 'ATable' }).exists()).toBe(false);
    expect(wrapper.text()).not.toContain('暂无数据');
  });

  it('成功且空数组：既有空态照常呈现，不弹错误 Alert（业务空态与查询失败分离）', async () => {
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.find('[data-test="tags-error"]').exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'ATable' }).exists()).toBe(true);
  });

  it('成功且返回数据：Table 渲染标签行且不弹错误 Alert（零回归）', async () => {
    const wrapper = mountPage({
      data: { tags: [{ id: 1, name: '数学', type: 'SUBJECT' }] },
    });
    await flushPromises();

    expect(wrapper.find('[data-test="tags-error"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('数学');
  });
});
