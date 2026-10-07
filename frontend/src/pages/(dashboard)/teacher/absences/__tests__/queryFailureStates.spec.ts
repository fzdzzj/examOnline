/**
 * 缺考名单页查询失败三态用例（fix-frontend-query-failure-states 阶段 1，U-1）。
 *
 * 现状缺陷：absences 查询的 error 未消费，失败时表格落空态文案
 * 「该考试没有缺考记录（或考试尚未结束）」——把系统故障解释成业务正常，
 * 还附带误导性括号解释。守住：失败 → Alert 显性呈现且空态文案不出现；
 * 成功且无数据 → 空态文案原样保留（本卡不改业务空态文案）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

import TeacherAbsencesPage from '@/pages/(dashboard)/teacher/absences/index.page.vue';
import { ApiError } from '@/api/types';

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  absences: vi.fn(),
  page2: vi.fn(),
}));

import { absences, page2 } from '@/api/axios';

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

const mockedAbsences = vi.mocked(absences);
const mockedPage2 = vi.mocked(page2);

beforeEach(() => {
  mockedPage2.mockResolvedValue([] as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('teacher/absences 查询失败三态（U-1）', () => {
  it('查询失败：错误 Alert 显性呈现，空态文案「该考试没有缺考记录（或考试尚未结束）」不出现', async () => {
    mockedAbsences.mockRejectedValue(new ApiError(500, '服务器内部错误', undefined, 500));
    const wrapper = mount(TeacherAbsencesPage);
    await flushPromises();

    const alert = wrapper.find('[data-test="absences-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('服务器内部错误');
    // 失败不得伪装成业务空态（含误导性括号解释的那句）
    expect(wrapper.text()).not.toContain('该考试没有缺考记录');
  });

  it('成功且无数据：空态文案原样保留，不弹错误 Alert', async () => {
    mockedAbsences.mockResolvedValue([] as never);
    const wrapper = mount(TeacherAbsencesPage);
    await flushPromises();

    expect(wrapper.find('[data-test="absences-error"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('该考试没有缺考记录（或考试尚未结束）');
  });
});
