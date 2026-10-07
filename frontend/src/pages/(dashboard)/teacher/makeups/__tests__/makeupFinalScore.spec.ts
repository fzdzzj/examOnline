/**
 * 教师侧补考最终成绩展示用例（add-makeup-final-score-frontend 阶段 4）。
 *
 * 三个断言面：
 * 1. 「后端尚未接线 / finalScore 零调用」的过期诚实边界 Alert 必须消失（前提已变：后端已接线）；
 * 2. 替代文案如实描述：合并规则在后端、历史成绩保留不覆盖；
 * 3. 页面只渲染后端 makeupFinalScore 的返回值——返回 null 时显示空态说明，
 *    不得出现任何前端推算 / 本地合并出来的数字。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

import TeacherMakeupsPage from '@/pages/(dashboard)/teacher/makeups/index.page.vue';

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
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

/** useQuery mock：真实调用页面 queryFn（后端返回值经 unwrap 原样进入页面） */
vi.mock('@tanstack/vue-query', async () => {
  const { ref } = await import('vue');
  return {
    useQuery: (options: { queryFn?: () => unknown }) => {
      const data = ref<unknown>(undefined);
      // fix-frontend-query-failure-states：页面解构 error 做三态分离，mock 须提供该字段
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

const mockedMakeupFinalScore = vi.mocked(makeupFinalScore);

beforeEach(() => {
  // hey-api SDK 函数的返回类型是客户端响应联合，测试桩统一按仓库惯例 as never 收窄
  vi.mocked(page2).mockResolvedValue([] as never);
  vi.mocked(makeupEligible).mockResolvedValue([
    { studentId: 21, studentName: '张三', reason: '缺考' },
  ] as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('teacher/makeups 补考最终成绩', () => {
  it('过期边界已撤：「后端尚未接线 / finalScore 零调用」表述不再出现', () => {
    const wrapper = mount(TeacherMakeupsPage);
    const text = wrapper.text();
    expect(text).not.toContain('尚未接线');
    expect(text).not.toContain('未接线');
    expect(text).not.toContain('零调用');
    expect(text).not.toContain('MakeupScoreService');
  });

  it('诚实边界改写：合并规则在后端、历史成绩保留不覆盖，前端不本地推算', () => {
    const wrapper = mount(TeacherMakeupsPage);
    const text = wrapper.text();
    expect(text).toContain('合并规则在后端');
    expect(text).toContain('历史成绩保留不覆盖');
    expect(text).toContain('不本地推算');
  });

  it('渲染后端返回值：makeupFinalScore 返回 82.5 则页面原样呈现 82.5', async () => {
    mockedMakeupFinalScore.mockResolvedValue({
      examId: 7,
      studentId: 21,
      finalScore: 82.5,
      reviewing: false,
    } as never);
    const wrapper = mount(TeacherMakeupsPage);
    await flushPromises();

    expect(mockedMakeupFinalScore).toHaveBeenCalled();
    expect(wrapper.text()).toContain('82.5');
  });

  it('不本地合并：后端返回 null（无已批改记录）时展示空态说明，不出现推算数字', async () => {
    mockedMakeupFinalScore.mockResolvedValue({
      examId: 7,
      studentId: 21,
      finalScore: null,
      reviewing: false,
    } as never);
    const wrapper = mount(TeacherMakeupsPage);
    await flushPromises();

    const text = wrapper.text();
    expect(text).toContain('后端返回为空');
    // mock 只返回了 null：页面上若出现具体分数即为本地点积出来的值
    expect(text).not.toContain('82.5');
    expect(text).not.toContain('100');
  });
});
