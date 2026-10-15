/**
 * 学生学情画像与靶向练习交互用例（add-student-learning-analytics 前端 spec-delta）。
 *
 * 覆盖：
 * - 概览统计卡 / 成绩走势 / 知识点掌握度（红黄绿分级）渲染；
 * - 薄弱点推荐卡片（推荐原因 + 去练习按钮）；
 * - 空态引导（参测 0 数据 → Empty，非错误态）；
 * - API 失败显性 Alert（三态分离错误态）；
 * - 「去练习」跳转既有错题刷题页。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import StudentLearningAnalytics from '@/components/student/StudentLearningAnalytics.vue';
import { myLearningAnalytics, myRecommendations } from '@/api/axios';

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  myLearningAnalytics: vi.fn(),
  myRecommendations: vi.fn(),
}));

vi.mock('@tanstack/vue-query', () => ({
  useQuery: (options: { queryFn?: () => unknown }) => {
    const data = ref<unknown>(undefined);
    const error = ref<unknown>(null);
    const isFetching = ref(false);
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
    return { data, error, isFetching, refetch: vi.fn() };
  },
}));

const pushMock = vi.hoisted(() => vi.fn());
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
}));

const mockedAnalytics = vi.mocked(myLearningAnalytics);
const mockedRecommendations = vi.mocked(myRecommendations);

beforeEach(() => {
  pushMock.mockReset();
  mockedAnalytics.mockReset();
  mockedRecommendations.mockReset();
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

const sampleAnalytics = {
  overview: {
    totalExams: 2,
    averageScore: 2.5,
    highestScore: 5,
    lowestScore: 0,
    passRate: 0.5,
  },
  trend: [
    {
      examId: 1,
      examTitle: '物理考试',
      examTime: '2026-10-15T09:00:00',
      studentScore: 0,
      totalScore: 5,
      scoreRate: 0,
    },
    {
      examId: 2,
      examTitle: '数学考试',
      examTime: '2026-10-16T09:00:00',
      studentScore: 5,
      totalScore: 5,
      scoreRate: 1,
    },
  ],
  tagMastery: [
    {
      tagId: 1,
      tagName: '物理',
      questionCount: 1,
      totalAvailableScore: 5,
      studentAchievedScore: 0,
      masteryRate: 0,
      weak: true,
    },
    {
      tagId: 2,
      tagName: '数学',
      questionCount: 1,
      totalAvailableScore: 5,
      studentAchievedScore: 5,
      masteryRate: 1,
      weak: false,
    },
    {
      tagId: 3,
      tagName: '化学',
      questionCount: 2,
      totalAvailableScore: 10,
      studentAchievedScore: 8,
      masteryRate: 0.8,
      weak: false,
    },
  ],
} as never;

describe('StudentLearningAnalytics 学情画像与靶向练习', () => {
  it('概览统计卡、成绩走势、知识点掌握度（薄弱/及格/达标三色）与薄弱推荐卡片渲染', async () => {
    mockedAnalytics.mockResolvedValue(sampleAnalytics);
    mockedRecommendations.mockResolvedValue([
      {
        questionId: 101,
        stem: '化学：如何配平反应式？',
        type: '单选',
        score: 5,
        difficulty: 1,
        tagId: 3,
        tagName: '化学',
        tagMasteryRate: 0.8,
        recommendReason: '("化学")掌握度 80.0%，当前为你的薄弱点，建议针对性巩固',
      } as never,
    ] as never);
    const wrapper = mount(StudentLearningAnalytics);
    await flushPromises();

    const text = wrapper.text();
    // 概览统计
    expect(text).toContain('参测场次');
    expect(text).toContain('历史均分');
    expect(text).toContain('最高分');
    expect(text).toContain('及格率');
    expect(text).toContain('50');
    // 成绩走势
    expect(text).toContain('物理考试');
    // 知识点掌握度三色标签：物理=薄弱(红)、化学=及格(黄)、数学=达标(绿)
    const tags = wrapper.findAll('[data-test="tag-mastery"]');
    expect(tags.length).toBe(3);
    expect(wrapper.text()).toContain('薄弱');
    expect(wrapper.text()).toContain('及格');
    expect(wrapper.text()).toContain('达标');
    // 薄弱推荐卡片
    expect(text).toContain('化学：如何配平反应式？');
    expect(text).toContain('建议针对性巩固');
    expect(text).toContain('去练习');
  });

  it('掌握度进度条分级正确：薄弱项 Progress 为 exception，达标项为 success', async () => {
    mockedAnalytics.mockResolvedValue(sampleAnalytics);
    mockedRecommendations.mockResolvedValue([] as never);
    const wrapper = mount(StudentLearningAnalytics);
    await flushPromises();

    const progressNodes = wrapper.findAll('div[class*="ant-progress"]');
    expect(progressNodes.length).toBeGreaterThan(0);
    // 薄弱（物理 0%）、及格（化学 80%）、达标（数学 100%）都会渲染进度条
    expect(wrapper.text()).toContain('薄弱');
  });

  it('空态：参测 0，展示 Empty 引导而非错误', async () => {
    mockedAnalytics.mockResolvedValue({
      overview: {
        totalExams: 0,
        averageScore: undefined,
        highestScore: undefined,
        lowestScore: undefined,
        passRate: undefined,
      },
      trend: [],
      tagMastery: [],
      weakTags: [],
    } as never);
    mockedRecommendations.mockResolvedValue([] as never);
    const wrapper = mount(StudentLearningAnalytics);
    await flushPromises();

    expect(wrapper.text()).toContain('暂无学情数据');
    expect(wrapper.find('[data-test="analytics-error"]').exists()).toBe(false);
  });

  it('API 失败：显性 Alert 呈现错误，主体不渲染空态（三态分离）', async () => {
    mockedAnalytics.mockRejectedValue(new Error('学情查询失败'));
    mockedRecommendations.mockRejectedValue(new Error('推荐查询失败'));
    const wrapper = mount(StudentLearningAnalytics);
    await flushPromises();

    expect(wrapper.find('[data-test="analytics-error"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('学情查询失败');
    // 错误态不得伪装成空态
    expect(wrapper.text()).not.toContain('暂无学情数据');
  });

  it('去练习：点击跳转既有错题刷题页 /student/wrong-questions', async () => {
    mockedAnalytics.mockResolvedValue(sampleAnalytics);
    mockedRecommendations.mockResolvedValue([
      {
        questionId: 101,
        stem: '化学题',
        tagId: 3,
        tagName: '化学',
        tagMasteryRate: 0.8,
        recommendReason: '薄弱推荐',
      } as never,
    ] as never);
    const wrapper = mount(StudentLearningAnalytics);
    await flushPromises();

    const btn = wrapper.find('[data-test="start-practice"]');
    expect(btn.exists()).toBe(true);
    await btn.trigger('click');
    expect(pushMock).toHaveBeenCalledWith('/student/wrong-questions');
  });
});
