/**
 * 补考管理页两处查询失败三态用例（fix-frontend-query-failure-states 阶段 1，U-1）。
 *
 * 现状缺陷：候选人查询（makeupEligible）与最终成绩查询（makeupFinalScore）的 error
 * 均未消费——失败分别落「请先选择主考考试并点击『查询补考候选人』」与提示文案，
 * 用户明明点了查询，失败却被引导「请先点查询」。守住：失败 → Alert 显性呈现且
 * 对应空态/提示文案不出现；成功且无数据 → 文案原样保留（本卡不改业务空态文案）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

import TeacherMakeupsPage from '@/pages/(dashboard)/teacher/makeups/index.page.vue';
import { ApiError } from '@/api/types';

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  createMakeup: vi.fn(),
  makeupEligible: vi.fn(),
  makeupFinalScore: vi.fn(),
  page2: vi.fn(),
}));

import { makeupEligible, makeupFinalScore, page2 } from '@/api/axios';

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

const mockedEligible = vi.mocked(makeupEligible);
const mockedFinal = vi.mocked(makeupFinalScore);
const mockedPage2 = vi.mocked(page2);

beforeEach(() => {
  mockedPage2.mockResolvedValue([] as never);
  // 最终成绩查询默认挂起：本组用例只裁决候选人口径，除非用例显式改 mock
  mockedFinal.mockReturnValue(new Promise(() => {}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('teacher/makeups 查询失败三态（U-1）', () => {
  it('候选人查询失败：错误 Alert 显性呈现，「请先选择主考考试并点击查询」不出现', async () => {
    mockedEligible.mockRejectedValue(new ApiError(500, '服务器内部错误', undefined, 500));
    const wrapper = mount(TeacherMakeupsPage);
    await flushPromises();

    const alert = wrapper.find('[data-test="eligible-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('服务器内部错误');
    // 失败不得伪装成「还没点查询」的引导文案
    expect(wrapper.text()).not.toContain('请先选择主考考试并点击');
  });

  it('候选人成功且无数据：空态文案原样保留，不弹错误 Alert', async () => {
    mockedEligible.mockResolvedValue([] as never);
    const wrapper = mount(TeacherMakeupsPage);
    await flushPromises();

    expect(wrapper.find('[data-test="eligible-error"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('请先选择主考考试并点击「查询补考候选人」');
  });

  it('最终成绩查询失败：错误 Alert 显性呈现，成功空值提示文案不出现', async () => {
    mockedEligible.mockResolvedValue([] as never);
    mockedFinal.mockRejectedValue(new ApiError(500, '服务器内部错误', undefined, 500));
    const wrapper = mount(TeacherMakeupsPage);
    await flushPromises();

    const alert = wrapper.find('[data-test="final-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('服务器内部错误');
    expect(wrapper.text()).not.toContain('选择学生后查询');
  });

  it('最终成绩成功且无数据：提示文案原样保留，不弹错误 Alert', async () => {
    mockedEligible.mockResolvedValue([] as never);
    const wrapper = mount(TeacherMakeupsPage);
    await flushPromises();

    expect(wrapper.find('[data-test="final-error"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('选择学生后查询');
  });
});
