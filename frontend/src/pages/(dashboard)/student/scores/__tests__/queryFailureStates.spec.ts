/**
 * 学生成绩页查询失败三态用例（fix-frontend-query-failure-states 阶段 1，U-1）。
 *
 * 现状缺陷：myScore / 补考最终成绩两查询的 error 未消费——请求失败时 data 为 undefined，
 * mapMyScoreToView(undefined) 落 not-published，网络失败/500 与「成绩待发布」渲染为同一张
 * 未发布卡片，学生无法区分「没分」和「坏了」。本组用例守住三态分离
 * （形态先例：StudentExamList「空态与错误态分开」）：
 * - 非「成绩待发布」失败 → 错误 Alert 显性呈现，未发布卡片不出现；
 * - 待发布 400 → 仍走 not-published 业务态（回归保护，判别不放宽）；
 * - 词法护栏：页面源码必须在调用 mapMyScoreToView 前判别 error 与 isNotPublishedError。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { message } from 'ant-design-vue';

import StudentScoresPage from '@/pages/(dashboard)/student/scores/index.page.vue';
import scoresPageSource from '@/pages/(dashboard)/student/scores/index.page.vue?raw';
import { ApiError } from '@/api/types';

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: vi.fn() },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  apply: vi.fn(),
  myExams: vi.fn(),
  myMakeupFinalScore: vi.fn(),
  myScore: vi.fn(),
}));

import { myMakeupFinalScore, myScore } from '@/api/axios';

/**
 * useQuery mock：真实调用页面传入的 queryFn（isNotPublishedError 归一分支照走），
 * rejection 落进 error ref——模板的错误消费正是从 error ref 接线，必须让 queryFn
 * 抛出的非待发布错误真实到达那里（真链路口径同 makeupFinalScore.spec）。
 */
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

const mockedMakeupFinal = vi.mocked(myMakeupFinalScore);
const mockedMyScore = vi.mocked(myScore);

beforeEach(() => {
  mockedMyScore.mockResolvedValue({ examId: 3, totalScore: 61, reviewing: false } as never);
  // 补考口径默认挂起：本组用例只裁决常规口径，除非用例显式改 mock
  mockedMakeupFinal.mockReturnValue(new Promise(() => {}) as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('student/scores 查询失败三态（U-1）', () => {
  it('非「成绩待发布」失败：错误 Alert 显性呈现，未发布卡片不出现', async () => {
    mockedMyScore.mockRejectedValue(new ApiError(500, '网络连接失败，请稍后重试', undefined, 500));
    const wrapper = mount(StudentScoresPage);
    await flushPromises();

    const alert = wrapper.find('[data-test="scores-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('网络连接失败，请稍后重试');
    // 失败不得伪装成业务态：未发布卡片不出现
    expect(wrapper.text()).not.toContain('成绩未发布');
  });

  it('待发布 400：仍渲染未发布业务态、不弹错误 Alert（回归保护，判别不放宽）', async () => {
    mockedMyScore.mockRejectedValue(new ApiError(400, '成绩待发布', undefined, 400));
    const wrapper = mount(StudentScoresPage);
    await flushPromises();

    expect(wrapper.find('[data-test="scores-error"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('成绩未发布');
  });

  it('词法护栏：源码必须在调用 mapMyScoreToView 前判别 error 与 isNotPublishedError', () => {
    const viewCallIdx = scoresPageSource.lastIndexOf('mapMyScoreToView');
    expect(viewCallIdx).toBeGreaterThan(-1);
    // 两处查询都要解构 error（myScore 与补考最终成绩），错误 Alert 必须在模板接线
    expect(scoresPageSource).toContain('error: scoreError');
    expect(scoresPageSource).toContain('error: makeupError');
    expect(scoresPageSource).toContain('data-test="scores-error"');
    // error 判别块（scoreError.value / makeupError.value 参与 isNotPublishedError 判别）
    // 必须先于 mapMyScoreToView 的调用点——失败态先裁决，再谈业务三态映射
    const scoreGuardIdx = scoresPageSource.indexOf('scoreError.value');
    const makeupGuardIdx = scoresPageSource.indexOf('makeupError.value');
    const discriminateIdx = scoresPageSource.indexOf('isNotPublishedError(caught)');
    expect(scoreGuardIdx).toBeGreaterThan(-1);
    expect(makeupGuardIdx).toBeGreaterThan(-1);
    expect(discriminateIdx).toBeGreaterThan(-1);
    expect(Math.max(scoreGuardIdx, makeupGuardIdx, discriminateIdx)).toBeLessThan(viewCallIdx);
  });
});
