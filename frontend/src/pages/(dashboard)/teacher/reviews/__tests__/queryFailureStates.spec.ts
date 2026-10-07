/**
 * 教师复核页查询失败三态用例（fix-frontend-query-failure-states 阶段 1，U-1）。
 *
 * 现状缺陷：listByExam 查询的 error 未消费，失败时表格落空态文案「该考试暂无复核申请」
 * ——系统故障被解释成业务正常。守住：失败 → Alert 显性呈现且空态文案不出现；
 * 成功且无数据 → 空态文案原样保留（本卡不改业务空态文案）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

import TeacherReviewsPage from '@/pages/(dashboard)/teacher/reviews/index.page.vue';
import { ApiError } from '@/api/types';

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: vi.fn() },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  listByExam: vi.fn(),
  page2: vi.fn(),
}));

import { listByExam, page2 } from '@/api/axios';

/** useQuery mock：真实调用 queryFn，rejection 落进 error ref（模板错误消费的接线点）。 */
vi.mock('@tanstack/vue-query', async () => {
  const { ref } = await import('vue');
  return {
    useQuery: (options: { queryFn?: () => unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      if (options.queryFn) {
        Promise.resolve()
          .then(options.queryFn)
          .then((value: unknown) => {
            data.value = value;
          })
          .catch((reason: unknown) => {
            error.value = reason;
          });
      }
      return { data, error, isFetching: ref(false), refetch: vi.fn() };
    },
  };
});

const mockedListByExam = vi.mocked(listByExam);
const mockedPage2 = vi.mocked(page2);

beforeEach(() => {
  mockedPage2.mockResolvedValue([] as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('teacher/reviews 查询失败三态（U-1）', () => {
  it('查询失败：错误 Alert 显性呈现，空态文案「该考试暂无复核申请」不出现', async () => {
    mockedListByExam.mockRejectedValue(new ApiError(500, '服务器内部错误', undefined, 500));
    const wrapper = mount(TeacherReviewsPage);
    await flushPromises();

    const alert = wrapper.find('[data-test="reviews-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('服务器内部错误');
    // 失败不得伪装成业务空态
    expect(wrapper.text()).not.toContain('该考试暂无复核申请');
  });

  it('成功且无数据：空态文案原样保留，不弹错误 Alert', async () => {
    mockedListByExam.mockResolvedValue([] as never);
    const wrapper = mount(TeacherReviewsPage);
    await flushPromises();

    expect(wrapper.find('[data-test="reviews-error"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('该考试暂无复核申请');
  });
});
