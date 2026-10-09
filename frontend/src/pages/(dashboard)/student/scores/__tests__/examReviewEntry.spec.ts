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
}));

import { myExams, myScore, myExamReview } from '@/api/axios';

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
});
