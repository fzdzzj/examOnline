import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { message } from 'ant-design-vue';

import * as api from '@/api/axios';
import ExamsIndexPage from '@/pages/(dashboard)/teacher/exams/index.page.vue';

const routerMock = vi.hoisted(() => ({ push: vi.fn(), back: vi.fn() }));
const routeMock = vi.hoisted(() => ({ query: {} as Record<string, unknown> }));
const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('vue-router', () => ({
  useRouter: () => routerMock,
  useRoute: () => routeMock,
}));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  page2: vi.fn(),
  publish1: vi.fn(),
  forceEnd: vi.fn(),
  delete2: vi.fn(),
}));

/** useQuery mock：支持 queryKey 响应式重新触发（真链路口径） */
vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref, watch } = await import('vue');
  return {
    useQuery: (options: { queryKey?: unknown; queryFn?: () => unknown; enabled?: unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      const isFetching = ref(false);

      const runQuery = async () => {
        const enabled = options.enabled === undefined || unref(options.enabled) !== false;
        if (enabled && options.queryFn) {
          isFetching.value = true;
          try {
            data.value = await options.queryFn();
          } catch (e) {
            error.value = e;
          } finally {
            isFetching.value = false;
          }
        }
      };

      if (options.queryKey) {
        watch(
          () => unref(options.queryKey),
          () => {
            void runQuery();
          },
          { deep: true }
        );
      }

      void runQuery();
      return { data, error, isFetching, refetch: vi.fn(runQuery) };
    },
  };
});

const MOCK_EXAMS = [
  {
    id: 101,
    title: '期中物理测试',
    paperId: 1,
    classId: 1,
    status: 0,
    published: false,
    startTime: '2026-10-01T09:00:00',
    endTime: '2026-10-01T11:00:00',
    durationMinutes: 90,
  },
  {
    id: 102,
    title: '期末物理测试',
    paperId: 1,
    classId: 1,
    status: 1,
    published: true,
    startTime: '2026-10-02T09:00:00',
    endTime: '2026-10-02T11:00:00',
    durationMinutes: 90,
  },
];

describe('教师考试列表服务端筛选联动（U-3）', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(api.page2).mockResolvedValue(MOCK_EXAMS as never);
    vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
    vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
    vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('占位符断言：搜索框 placeholder 移除「（当前页）」，文案为「搜索考试标题」', async () => {
    const wrapper = mount(ExamsIndexPage);
    await flushPromises();

    const input = wrapper.find('input[placeholder*="搜索考试标题"]');
    expect(input.exists()).toBe(true);
    expect(input.attributes('placeholder')).toBe('搜索考试标题');
    expect(input.attributes('placeholder')).not.toContain('当前页');
  });

  it('防抖检索与参数透传：标题输入在 300ms 内不发请求，300ms 后以新标题发起请求', async () => {
    const wrapper = mount(ExamsIndexPage);
    await flushPromises();

    expect(api.page2).toHaveBeenCalledTimes(1);
    expect(api.page2).toHaveBeenLastCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({ page: 1, size: 10 }),
      })
    );

    const input = wrapper.find('input');
    await input.setValue('物理');

    // 100ms 和 250ms 时均未触发防抖
    vi.advanceTimersByTime(100);
    await flushPromises();
    expect(api.page2).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(150);
    await flushPromises();
    expect(api.page2).toHaveBeenCalledTimes(1);

    // 满 300ms 触发
    vi.advanceTimersByTime(50);
    await flushPromises();
    expect(api.page2).toHaveBeenCalledTimes(2);
    expect(api.page2).toHaveBeenLastCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({
          page: 1,
          size: 10,
          title: '物理',
        }),
      })
    );
  });

  it('状态筛选参数透传：更改状态下拉框即刻触发带参请求', async () => {
    const wrapper = mount(ExamsIndexPage);
    await flushPromises();

    expect(api.page2).toHaveBeenCalledTimes(1);

    // Select 组件触发更改
    const select = wrapper.findComponent({ name: 'ASelect' });
    expect(select.exists()).toBe(true);
    await select.vm.$emit('update:value', 0);
    await flushPromises();

    expect(api.page2).toHaveBeenCalledTimes(2);
    expect(api.page2).toHaveBeenLastCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({
          page: 1,
          size: 10,
          status: 0,
        }),
      })
    );
  });

  it('切页后筛选变更重置为第 1 页：在第 2 页变更搜索词自动重置为 page 1 发起请求', async () => {
    const wrapper = mount(ExamsIndexPage);
    await flushPromises();

    // 触发翻页到第 2 页
    const table = wrapper.findComponent({ name: 'ATable' });
    expect(table.exists()).toBe(true);
    await table.vm.$emit('change', { current: 2, pageSize: 10 });
    await flushPromises();

    expect(api.page2).toHaveBeenLastCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({ page: 2 }),
      })
    );

    // 搜索词变更
    const input = wrapper.find('input');
    await input.setValue('期末');
    vi.advanceTimersByTime(300);
    await flushPromises();

    // 必须重置为第 1 页
    expect(api.page2).toHaveBeenLastCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({
          page: 1,
          size: 10,
          title: '期末',
        }),
      })
    );
  });
});
