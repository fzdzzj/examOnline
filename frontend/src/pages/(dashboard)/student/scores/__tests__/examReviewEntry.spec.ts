import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

import StudentScoresPage from '@/pages/(dashboard)/student/scores/index.page.vue';
import { ApiError } from '@/api/types';

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  apply: vi.fn(),
  myExams: vi.fn(),
  myMakeupFinalScore: vi.fn(),
  myScore: vi.fn(),
  myExamReview: vi.fn(),
  leaderboard: vi.fn(),
  checkFavorites: vi.fn().mockResolvedValue([]),
  favorite: vi.fn().mockResolvedValue(undefined),
  unfavorite: vi.fn().mockResolvedValue(undefined),
}));

import { myExams, myScore, myExamReview, checkFavorites, unfavorite } from '@/api/axios';

vi.mock('@tanstack/vue-query', async () => {
  const { ref, watch } = await import('vue');
  return {
    useQuery: (options: { queryKey?: unknown; queryFn?: () => unknown; enabled?: unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      const isFetching = ref(false);

      const execute = async () => {
        if (options.enabled && typeof options.enabled === 'object' && 'value' in options.enabled) {
          if (!options.enabled.value) return;
        }
        if (options.queryFn) {
          try {
            isFetching.value = true;
            data.value = await options.queryFn();
          } catch (err) {
            error.value = err;
          } finally {
            isFetching.value = false;
          }
        }
      };

      if (options.enabled && typeof options.enabled === 'object' && 'value' in options.enabled) {
        watch(options.enabled, execute);
      }
      if (options.queryKey && typeof options.queryKey === 'object' && 'value' in options.queryKey) {
        watch(options.queryKey, execute);
      }
      void execute();

      return { data, error, isFetching, refetch: execute };
    },
  };
});

describe('查分页逐题回顾入口与弹层 (student/scores)', () => {
  it('已发布考试：成绩卡片呈现「逐题回顾」按钮', async () => {
    vi.mocked(myExams).mockResolvedValueOnce([
      { examId: 101, title: '已发布考试', status: 3 },
    ] as never);
    vi.mocked(myScore).mockResolvedValueOnce({
      examId: 101,
      examTitle: '已发布考试',
      totalScore: 90,
      objectiveScore: 50,
      subjectiveScore: 40,
      rank: 1,
      reviewing: false,
    } as never);

    const wrapper = mount(StudentScoresPage);
    // 选中 examId 101
    (wrapper.vm as unknown as { selectedExamId?: number }).selectedExamId = 101;
    await flushPromises();

    const reviewBtn = wrapper.find('[data-test="review-exam-btn"]');
    expect(reviewBtn.exists()).toBe(true);
    expect(reviewBtn.text()).toContain('逐题回顾');
  });

  it('未发布或复核中考试：严格不渲染「逐题回顾」按钮', async () => {
    vi.mocked(myExams).mockResolvedValueOnce([
      { examId: 102, title: '未发布考试', status: 2 },
    ] as never);
    vi.mocked(myScore).mockRejectedValueOnce(new ApiError(400, '成绩待发布'));

    const wrapper = mount(StudentScoresPage);
    (wrapper.vm as unknown as { selectedExamId?: number }).selectedExamId = 102;
    await flushPromises();

    expect(wrapper.find('[data-test="review-exam-btn"]').exists()).toBe(false);
  });

  it('点击「逐题回顾」打开弹层并渲染单场全题明细', async () => {
    vi.mocked(myExams).mockResolvedValueOnce([
      { examId: 101, title: '已发布考试', status: 3 },
    ] as never);
    vi.mocked(myScore).mockResolvedValueOnce({
      examId: 101,
      examTitle: '已发布考试',
      totalScore: 90,
      reviewing: false,
    } as never);
    vi.mocked(myExamReview).mockResolvedValueOnce({
      examId: 101,
      examTitle: '已发布考试',
      studentName: '张三',
      totalScore: 90,
      objectiveScore: 50,
      subjectiveScore: 40,
      rank: 1,
      questions: [
        {
          questionId: 1,
          questionNumber: 1,
          questionType: '单选',
          questionContent: '单选题干测试',
          myAnswer: 'A',
          correctAnswer: 'A',
          myScore: 5,
          fullScore: 5,
          analysis: '单选详细解析内容',
        },
      ],
    } as never);

    const wrapper = mount(StudentScoresPage);
    (wrapper.vm as unknown as { selectedExamId?: number }).selectedExamId = 101;
    await flushPromises();

    const reviewBtn = wrapper.find('[data-test="review-exam-btn"]');
    expect(reviewBtn.exists()).toBe(true);

    await reviewBtn.trigger('click');
    await flushPromises();

    expect((wrapper.vm as unknown as { reviewModalOpen?: boolean }).reviewModalOpen).toBe(true);
    expect(document.body.textContent).toContain('单选题干测试');
    expect(document.body.textContent).toContain('单选详细解析内容');
  });

  it('逐题回顾：填空题展示学生原文与可接受答案列表（与简答参考答案形态区分）', async () => {
    vi.mocked(myExams).mockResolvedValueOnce([
      { examId: 103, title: '填空考试', status: 3 },
    ] as never);
    vi.mocked(myScore).mockResolvedValueOnce({
      examId: 103,
      examTitle: '填空考试',
      totalScore: 6,
      reviewing: false,
    } as never);
    vi.mocked(myExamReview).mockResolvedValueOnce({
      examId: 103,
      examTitle: '填空考试',
      studentName: '李四',
      totalScore: 6,
      questions: [
        {
          questionId: 21,
          questionNumber: 1,
          questionType: '填空',
          questionContent: '中国的首都是 ____',
          myAnswer: '北京',
          correctAnswer: '北京|beijing',
          myScore: 6,
          fullScore: 6,
          analysis: '首都是北京',
        },
      ],
    } as never);

    const wrapper = mount(StudentScoresPage);
    (wrapper.vm as unknown as { selectedExamId?: number }).selectedExamId = 103;
    await flushPromises();

    const reviewBtn = wrapper.find('[data-test="review-exam-btn"]');
    expect(reviewBtn.exists()).toBe(true);
    await reviewBtn.trigger('click');
    await flushPromises();

    // 可接受答案列表形态（命中任一）——不是把归一化串原样 `北京|beijing` 展示
    expect(document.body.textContent).toContain('可接受答案（命中任一即可）');
    expect(document.body.textContent).toContain('北京 / beijing');
    expect(document.body.textContent).toContain('中国的首都是 ____');
  });

  it('逐题回顾题干图（add-question-image）：含图题目渲染图片、无图题目零回归', async () => {
    vi.mocked(myExams).mockResolvedValueOnce([
      { examId: 104, title: '带图考试', status: 3 },
    ] as never);
    vi.mocked(myScore).mockResolvedValueOnce({
      examId: 104,
      examTitle: '带图考试',
      totalScore: 10,
      reviewing: false,
    } as never);
    vi.mocked(myExamReview).mockResolvedValueOnce({
      examId: 104,
      examTitle: '带图考试',
      studentName: '王五',
      totalScore: 10,
      questions: [
        {
          questionId: 31,
          questionNumber: 1,
          questionType: '简答',
          questionContent: '看图作答',
          imageUrl: '/images/review-img.png',
          myAnswer: '答',
          correctAnswer: '标准',
          myScore: 6,
          fullScore: 6,
        },
        {
          questionId: 32,
          questionNumber: 2,
          questionType: '单选',
          questionContent: '无图单选题',
          myAnswer: 'A',
          correctAnswer: 'A',
          myScore: 4,
          fullScore: 4,
        },
      ],
    } as never);

    const wrapper = mount(StudentScoresPage);
    (wrapper.vm as unknown as { selectedExamId?: number }).selectedExamId = 104;
    await flushPromises();
    await wrapper.find('[data-test="review-exam-btn"]').trigger('click');
    await flushPromises();

    const image = document.body.querySelector('[data-test="question-image"]');
    expect(image).not.toBeNull();
    expect((image as HTMLImageElement | null)?.getAttribute('src')).toBe('/images/review-img.png');
    // 无图题不渲染图片：全弹层只有含图题那一张
    expect(document.body.querySelectorAll('[data-test="question-image"]').length).toBe(1);
  });

  it('答卷回顾收藏交互：批量回显收藏状态并支持一键收藏/取消收藏', async () => {
    vi.mocked(myExams).mockResolvedValueOnce([{ examId: 105, title: '收藏测试考试' }] as never);
    vi.mocked(myScore).mockResolvedValueOnce({
      examId: 105,
      examTitle: '收藏测试考试',
      finalScore: 100,
      reviewing: false,
    } as never);
    vi.mocked(myExamReview).mockResolvedValueOnce({
      examId: 105,
      examTitle: '收藏测试考试',
      studentName: '测试生',
      totalScore: 100,
      questions: [
        {
          questionId: 501,
          questionNumber: 1,
          questionType: '单选',
          questionContent: '经典题1',
          correctAnswer: 'A',
          myScore: 5,
          fullScore: 5,
        },
      ],
    } as never);
    vi.mocked(checkFavorites).mockResolvedValueOnce([501] as never);

    const wrapper = mount(StudentScoresPage);
    (wrapper.vm as unknown as { selectedExamId?: number }).selectedExamId = 105;
    await flushPromises();
    await wrapper.find('[data-test="review-exam-btn"]').trigger('click');
    await flushPromises();

    expect(checkFavorites).toHaveBeenCalledWith(
      expect.objectContaining({
        query: { questionIds: [501] },
      })
    );

    // 初始状态已收藏，点击触发取消收藏
    const starBtn = document.body.querySelector(
      '[data-test="favorite-toggle-501"]'
    ) as HTMLElement | null;
    expect(starBtn).not.toBeNull();
    starBtn?.click();
    await flushPromises();

    expect(unfavorite).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { questionId: 501 },
      })
    );
  });
});
