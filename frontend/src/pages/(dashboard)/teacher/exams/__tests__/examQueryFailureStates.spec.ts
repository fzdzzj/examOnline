/**
 * 考试列表查询/搜索失败三态用例（fix-frontend-exam-page-error-states，U-6）。
 *
 * 现状缺陷：列表 `useQuery` 只解构 { data, isFetching, refetch }，未消费 error；
 * 查询失败时 data=undefined → rows=[]，落 antd 内置空态，与「确实没有考试」不可区分
 * （教师依赖该页做编辑/发布/删除决策）。`add-exam-list-filtering` 改造后失败窗口更大。
 *
 * 守住（U-1 三态分离口径在该页的补全，形态对齐 papers/index 的 papers-error）：
 * - 查询失败 → 错误 Alert 显性呈现后端 message 且 Table 隐藏、业务空态「暂无数据」不出现；
 * - 搜索/筛选联动的再查询失败同口径（Alert 随失败再显）；
 * - 成功且空数组 → 既有业务空态文案原样保留、不弹错误 Alert；
 * - 成功且返回数据 → Table 渲染数据行零回归（分页/操作列行为不变）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { message } from 'ant-design-vue';

import * as api from '@/api/axios';
import { ApiError } from '@/api/types';
import ExamsIndexPage from '@/pages/(dashboard)/teacher/exams/index.page.vue';

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
  page2: vi.fn(),
  publish1: vi.fn(),
  forceEnd: vi.fn(),
  delete2: vi.fn(),
}));

/** useQuery mock：queryKey 变化时重新触发（真链路口径，rejection 落进 error ref） */
vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref, watch } = await import('vue');
  return {
    useQuery: (options: { queryKey?: unknown; queryFn?: () => unknown; enabled?: unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      const isFetching = ref(false);
      const run = () => {
        Promise.resolve()
          .then(options.queryFn as () => unknown)
          .then((value: unknown) => {
            data.value = value;
          })
          .catch((reason: unknown) => {
            error.value = reason;
          });
      };
      if (options.queryKey) {
        watch(
          () => unref(options.queryKey),
          () => run(),
          { deep: true }
        );
      }
      run();
      return { data, error, isFetching, refetch: vi.fn(run) };
    },
  };
});

const MOCK_EXAM = {
  id: 1,
  title: '期中物理测试',
  paperId: 1,
  classId: 1,
  status: 0,
  published: false,
  startTime: '2026-10-01T09:00:00',
  endTime: '2026-10-01T11:00:00',
  durationMinutes: 90,
};

function bodyRows(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('tbody tr').filter((tr) => tr.text().trim().length > 0);
}

beforeEach(() => {
  routerMock.push.mockClear();
  invalidateQueries.mockClear();
  vi.mocked(api.page2).mockResolvedValue([MOCK_EXAM] as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe('考试列表查询失败三态（U-6）', () => {
  it('列表查询失败：错误 Alert 显性呈现后端 message，Table 隐藏且「暂无数据」空态不出现', async () => {
    vi.mocked(api.page2).mockRejectedValue(new ApiError(500, '考试列表加载失败', undefined, 500));
    const wrapper = mount(ExamsIndexPage);
    await flushPromises();
    await flushPromises();

    const alert = wrapper.find('[data-test="exams-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('考试列表加载失败');
    // Table 隐藏：失败不得伪装成「无考试」
    expect(wrapper.findComponent({ name: 'ATable' }).exists()).toBe(false);
    expect(wrapper.text()).not.toContain('暂无数据');
  });

  it('搜索/筛选联动失败同口径：再查询失败时错误 Alert 随失败显性呈现', async () => {
    // 首屏成功 → 筛选变更触发再查询失败 → Alert 出现
    vi.mocked(api.page2).mockImplementation(((arg: {
      query: { title?: string; status?: number };
    }) => {
      if (arg.query.status === 2 || arg.query.title !== undefined) {
        return Promise.reject(new ApiError(500, '筛选失败', undefined, 500));
      }
      return Promise.resolve([MOCK_EXAM] as never);
    }) as never);
    const wrapper = mount(ExamsIndexPage);
    await flushPromises();
    await flushPromises();
    expect(wrapper.find('[data-test="exams-error"]').exists()).toBe(false);

    // 变更状态下拉触发再查询
    const select = wrapper.findComponent({ name: 'ASelect' });
    await select.vm.$emit('update:value', 2);
    await flushPromises();

    const alert = wrapper.find('[data-test="exams-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('筛选失败');
  });

  it('成功且空数组：既有空态文案原样保留，不弹错误 Alert', async () => {
    vi.mocked(api.page2).mockResolvedValue([] as never);
    const wrapper = mount(ExamsIndexPage);
    await flushPromises();
    await flushPromises();

    expect(wrapper.find('[data-test="exams-error"]').exists()).toBe(false);
    // 成功且空数组 → 既有空态（ant-empty 占位符，locale 无关）原样呈现，不误入错误态
    expect(wrapper.find('.ant-empty').exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'ATable' }).exists()).toBe(true);
  });

  it('成功且返回数据：Table 渲染数据行且不弹错误 Alert（零回归）', async () => {
    const wrapper = mount(ExamsIndexPage);
    await flushPromises();
    await flushPromises();

    expect(wrapper.find('[data-test="exams-error"]').exists()).toBe(false);
    expect(bodyRows(wrapper)).toHaveLength(1);
    expect(wrapper.text()).toContain('期中物理测试');
  });
});
