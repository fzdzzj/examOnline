/**
 * 复核页考试下拉候选拉取失败占位（harden-review-flow-and-resource-limits L-01）：
 *
 * useTeacherExams 严格形态把取数失败透出到 useQuery.error，本页消费 error 渲染
 * data-test="exams-error" 的错误 Alert——考试列表拉取失败不再静默显示为空下拉。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

import TeacherReviewsPage from '@/pages/(dashboard)/teacher/reviews/index.page.vue';

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
  handle: vi.fn(),
}));

import { listByExam, page2 } from '@/api/axios';

/** useQuery mock：真实调用 queryFn，rejection 落进 error ref（错误接线点）。 */
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

beforeEach(() => {
  vi.mocked(listByExam).mockResolvedValue([] as never);
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('考试下拉失败占位（L-01）', () => {
  it('考试列表拉取失败：exams-error Alert 显性呈现后端原因，下拉不静默为空', async () => {
    vi.mocked(page2).mockRejectedValue(new Error('考试列表加载失败'));
    const wrapper = mount(TeacherReviewsPage);
    await flushPromises();

    const alert = wrapper.find('[data-test="exams-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('考试列表加载失败');
  });

  it('考试列表拉取成功：无 exams-error Alert，下拉候选正常渲染', async () => {
    vi.mocked(page2).mockResolvedValue([{ id: 1, title: '期中考试' }] as never);
    const wrapper = mount(TeacherReviewsPage);
    await flushPromises();

    expect(wrapper.find('[data-test="exams-error"]').exists()).toBe(false);
    // Select 选项在收起状态下不落 DOM 文本，经 vm 的 examOptions 断言候选已装配
    const examOptions = (wrapper.vm as unknown as { examOptions: { label: string }[] }).examOptions;
    expect(examOptions.map((o) => o.label)).toContain('#1 期中考试');
  });
});
