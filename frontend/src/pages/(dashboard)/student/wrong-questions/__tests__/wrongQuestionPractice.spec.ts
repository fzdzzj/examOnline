import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref, watch } from 'vue';
import { Checkbox } from 'ant-design-vue';

import WrongQuestionsPage from '@/pages/(dashboard)/student/wrong-questions/index.page.vue';
import { myPracticePool, myWrongQuestions, submitPractice } from '@/api/axios';

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  myWrongQuestions: vi.fn(),
  myPracticePool: vi.fn(),
  submitPractice: vi.fn(),
  list1: vi.fn().mockResolvedValue([]),
  unreadCount: vi.fn().mockResolvedValue(0),
  markRead: vi.fn().mockResolvedValue(undefined),
}));

vi.mock('@tanstack/vue-query', () => ({
  useQuery: (options: { queryKey?: { value: unknown[] }; queryFn?: () => unknown }) => {
    const data = ref<unknown>(undefined);
    const error = ref<unknown>(null);
    const isFetching = ref(false);

    const run = () => {
      if (options.queryFn) {
        Promise.resolve()
          .then(options.queryFn)
          .then((val) => {
            data.value = val;
          })
          .catch((err) => {
            error.value = err;
          });
      }
    };
    run();
    if (options.queryKey) {
      watch(() => options.queryKey?.value, run, { deep: true });
    }
    return { data, error, isFetching, refetch: vi.fn().mockImplementation(run) };
  },
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ path: '/student/wrong-questions' }),
}));

vi.mock('vuex', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vuex')>();
  return {
    ...actual,
    useStore: () => ({
      state: {
        user: {
          id: 1,
          username: 'stu_1',
          name: '学生张三',
          roles: ['STUDENT'],
        },
      },
    }),
  };
});

describe('错题重练界面 (spec-delta frontend)', () => {
  it('Scenario 1: 重练入口与进度展示——错题显示连对进度（x/3）且提供重练入口', async () => {
    vi.mocked(myWrongQuestions).mockResolvedValueOnce({
      total: 1,
      page: 1,
      size: 10,
      groups: [
        {
          examId: 201,
          examTitle: '期末模考',
          wrongQuestions: [
            {
              questionId: 11,
              questionNumber: 1,
              questionType: '单选',
              questionContent: '地球是平的吗？',
              choices: ['是', '否'],
              myAnswer: 'A',
              correctAnswer: 'B',
              myScore: 0,
              fullScore: 5,
              analysis: '地球是球体',
              consecutiveCorrect: 1,
              mastered: false,
            },
          ],
        },
      ],
    } as never);

    vi.mocked(myPracticePool).mockResolvedValueOnce([
      {
        questionId: 11,
        questionType: '单选',
        questionContent: '地球是平的吗？',
        choices: ['是', '否'],
        consecutiveCorrect: 1,
      },
    ] as never);

    const wrapper = mount(WrongQuestionsPage);
    await flushPromises();

    // 检查重练入口按钮存在
    const startBtn = wrapper.find('[data-test="start-practice-btn"]');
    expect(startBtn.text()).toContain('重练');

    // 检查列表中题目显示连对进度 x/3（三态 Tag：攻克中蓝标 1/3）
    const progressTag = wrapper.find('[data-test="mastery-tag"]');
    expect(progressTag.text()).toContain('1/3');
  });

  it('Scenario 2: 逐题作答即时反馈——即时显示对错、正确答案、解析、连对进度与消灭提示', async () => {
    vi.mocked(myWrongQuestions).mockResolvedValue({
      total: 1,
      page: 1,
      size: 10,
      groups: [],
    } as never);

    vi.mocked(myPracticePool).mockResolvedValue([
      {
        questionId: 11,
        questionType: '单选',
        questionContent: '地球是平的吗？',
        choices: ['是', '否'],
        consecutiveCorrect: 2,
      },
    ] as never);

    vi.mocked(submitPractice).mockResolvedValueOnce({
      correct: true,
      correctAnswer: 'B',
      analysis: '地球是球体解析',
      consecutiveCorrect: 3,
      mastered: true,
    } as never);

    const wrapper = mount(WrongQuestionsPage);
    await flushPromises();

    // 点击开始重练进入重练模式
    const startBtn = wrapper.find('[data-test="start-practice-btn"]');
    await startBtn.trigger('click');
    await flushPromises();

    // 验证进入重练卡片区
    const practiceContainer = wrapper.find('[data-test="practice-container"]');
    expect(practiceContainer.text()).toContain('地球是平的吗？');

    // 触发提交
    const submitBtn = wrapper.find('[data-test="practice-submit-btn"]');
    await submitBtn.trigger('click');
    await flushPromises();

    // 验证即时反馈区
    const feedback = wrapper.find('[data-test="practice-feedback"]');
    expect(feedback.text()).toContain('正确答案：B');
    expect(feedback.text()).toContain('地球是球体解析');

    // 连对进度达到 3
    const consecutiveText = wrapper.find('[data-test="feedback-consecutive"]');
    expect(consecutiveText.text()).toContain('3/3');

    // 连续第 3 次答对显示消灭提示
    const masteredAlert = wrapper.find('[data-test="mastered-alert"]');
    expect(masteredAlert.text()).toContain('消灭');
  });

  it('Scenario 3: 已消灭条目翻回——以 includeMastered 翻回显示且带消灭标识', async () => {
    vi.mocked(myWrongQuestions)
      .mockResolvedValueOnce({
        total: 1,
        page: 1,
        size: 10,
        groups: [
          {
            examId: 202,
            examTitle: '已消灭考试',
            wrongQuestions: [],
          },
        ],
      } as never)
      .mockResolvedValueOnce({
        total: 1,
        page: 1,
        size: 10,
        groups: [
          {
            examId: 202,
            examTitle: '已消灭考试',
            wrongQuestions: [
              {
                questionId: 12,
                questionNumber: 2,
                questionType: '单选',
                questionContent: '1+1等于几？',
                choices: ['1', '2'],
                myAnswer: 'A',
                correctAnswer: 'B',
                myScore: 0,
                fullScore: 5,
                analysis: '解析',
                consecutiveCorrect: 3,
                mastered: true,
              },
            ],
          },
        ],
      } as never);

    const wrapper = mount(WrongQuestionsPage);
    await flushPromises();

    // 勾选「显示已消灭错题」
    const toggle = wrapper.findComponent(Checkbox);
    await toggle.vm.$emit('update:checked', true);
    await flushPromises();

    // 断言发起了带 includeMastered: true 的查询
    expect(vi.mocked(myWrongQuestions)).toHaveBeenCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({ includeMastered: true }),
      })
    );

    // 断言列表中出现消灭标识（三态 Tag：已消灭绿标）
    const masteredTag = wrapper.find('[data-test="mastery-tag"]');
    expect(masteredTag.text()).toContain('已消灭');
  });
});
