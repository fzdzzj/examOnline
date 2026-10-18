/**
 * 错题掌握度三态与强化自测用例（spec add-student-wrong-question-mastery）：
 *
 * - 三态彩标：错题本条目按 masteryStatus 渲染三态 Tag——
 *   0 UNTOUCHED 待攻克（橙）、1 PRACTICING 攻克中（蓝，标注 n/3）、2 MASTERED 已消灭（绿）；
 * - 强化自测：顶部「错题强化自测」按钮弹出配置弹窗（题量 1~20 缺省 5 + 策略），
 *   生成后进入自测模式（答题卡导航 + 逐题作答），批量交卷后展示总览结果与逐题正误解析。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref, watch } from 'vue';

import WrongQuestionsPage from '@/pages/(dashboard)/student/wrong-questions/index.page.vue';
import { generatePracticeQuiz, myWrongQuestions, submitPracticeQuiz } from '@/api/axios';

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  myWrongQuestions: vi.fn(),
  myPracticePool: vi.fn(),
  submitPractice: vi.fn(),
  generatePracticeQuiz: vi.fn(),
  submitPracticeQuiz: vi.fn(),
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

function mockWrongList(items: Array<Record<string, unknown>>): void {
  vi.mocked(myWrongQuestions).mockResolvedValue({
    total: 1,
    page: 1,
    size: 10,
    groups: [
      {
        examId: 201,
        examTitle: '期末模考',
        wrongQuestions: items,
      },
    ],
  } as never);
}

function baseItem(overrides: Record<string, unknown>): Record<string, unknown> {
  return {
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
    consecutiveCorrect: 0,
    mastered: false,
    ...overrides,
  };
}

/** Modal 内容 teleport 到 body：从 body 触发弹层内按钮点击。 */
function clickInBody(testId: string): void {
  const el = document.body.querySelector(`[data-test="${testId}"]`);
  expect(el).not.toBeNull();
  el!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
}

afterEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
});

describe('错题掌握度三态彩标', () => {
  it('三态 Tag：待攻克（橙）/攻克中（蓝，标注 n/3）/已消灭（绿）', async () => {
    mockWrongList([
      baseItem({
        questionId: 11,
        consecutiveCorrect: 0,
        masteryStatus: 0,
        masteryStatusName: 'UNTOUCHED',
      }),
      baseItem({
        questionId: 12,
        questionNumber: 2,
        questionContent: '1+1等于几？',
        consecutiveCorrect: 1,
        masteryStatus: 1,
        masteryStatusName: 'PRACTICING',
      }),
      baseItem({
        questionId: 13,
        questionNumber: 3,
        questionContent: '光速是多少？',
        consecutiveCorrect: 3,
        mastered: true,
        masteryStatus: 2,
        masteryStatusName: 'MASTERED',
      }),
    ]);

    const wrapper = mount(WrongQuestionsPage);
    await flushPromises();

    const tags = wrapper.findAll('[data-test="mastery-tag"]');
    expect(tags.length).toBe(3); // '三条错题各渲染一枚三态 Tag');
    expect(tags[0].text()).toContain('待攻克');
    expect(tags[0].classes().join(' ')).toContain('orange');
    expect(tags[1].text()).toContain('攻克中');
    expect(tags[1].text()).toContain('1/3');
    expect(tags[1].classes().join(' ')).toContain('blue');
    expect(tags[2].text()).toContain('已消灭');
    expect(tags[2].classes().join(' ')).toContain('success');
  });

  it('旧字段兼容：后端未带 masteryStatus 时按 consecutiveCorrect/mastered 推导三态', async () => {
    mockWrongList([baseItem({ questionId: 11, consecutiveCorrect: 2 })]);

    const wrapper = mount(WrongQuestionsPage);
    await flushPromises();

    const tags = wrapper.findAll('[data-test="mastery-tag"]');
    expect(tags.length).toBe(1);
    expect(tags[0].text()).toContain('攻克中');
    expect(tags[0].text()).toContain('2/3');
  });
});

describe('错题强化自测（组卷 + 批量交卷）', () => {
  const QUIZ = {
    quizId: 'b3d5f6a7-1c2d-4e5f-8a9b-0c1d2e3f4a5b',
    total: 2,
    items: [
      {
        questionId: 11,
        questionType: '单选',
        questionContent: '地球是平的吗？',
        choices: ['是', '否'],
        consecutiveCorrect: 0,
        masteryStatus: 0,
        masteryStatusName: 'UNTOUCHED',
      },
      {
        questionId: 12,
        questionType: '单选',
        questionContent: '1+1等于几？',
        choices: ['1', '2'],
        consecutiveCorrect: 1,
        masteryStatus: 1,
        masteryStatusName: 'PRACTICING',
      },
    ],
  };

  const QUIZ_RESULT = {
    total: 2,
    correctCount: 1,
    scoreRate: 50.0,
    newlyMasteredCount: 1,
    results: [
      {
        questionId: 11,
        correct: false,
        correctAnswer: 'B',
        analysis: '地球是球体解析',
        consecutiveCorrect: 0,
        mastered: false,
        masteryStatus: 0,
        masteryStatusName: 'UNTOUCHED',
      },
      {
        questionId: 12,
        correct: true,
        correctAnswer: '2',
        analysis: '1+1=2',
        consecutiveCorrect: 3,
        mastered: true,
        masteryStatus: 2,
        masteryStatusName: 'MASTERED',
      },
    ],
  };

  async function openQuizMode() {
    mockWrongList([]);
    vi.mocked(generatePracticeQuiz).mockResolvedValue(QUIZ as never);
    vi.mocked(submitPracticeQuiz).mockResolvedValue(QUIZ_RESULT as never);

    const wrapper = mount(WrongQuestionsPage);
    await flushPromises();

    const startBtn = wrapper.find('[data-test="start-quiz-btn"]');
    expect(startBtn.exists()).toBe(true);
    await startBtn.trigger('click');
    await flushPromises();

    // 配置弹窗出现，点「生成试卷」走缺省配置（题量 5 + RANDOM）
    expect(document.body.querySelector('[data-test="quiz-config-modal"]')).not.toBeNull();
    clickInBody('quiz-generate-btn');
    await flushPromises();

    expect(vi.mocked(generatePracticeQuiz)).toHaveBeenCalledWith(
      expect.objectContaining({
        body: expect.objectContaining({ limit: 5, strategy: 'RANDOM' }),
      })
    );
    return wrapper;
  }

  it('配置弹窗与组卷：缺省题量 5 + RANDOM 发起组卷，自测模式呈现题目与答题卡导航', async () => {
    const wrapper = await openQuizMode();

    const quizContainer = wrapper.find('[data-test="quiz-container"]');
    expect(quizContainer.exists()).toBe(true);
    expect(quizContainer.text()).toContain('地球是平的吗？');
    expect(quizContainer.text()).toContain('1+1等于几？');
    expect(wrapper.findAll('[data-test="quiz-nav-dot"]').length).toBe(2); // '答题卡导航每题一枚');
    // 组卷条目不得出现标准答案
    expect(quizContainer.text()).not.toContain('正确答案');
  });

  it('批量交卷：逐题作答后提交 quizId+answers，总览展示正确率/新消灭数与逐题正误', async () => {
    const wrapper = await openQuizMode();

    // 逐题作答：题 1 选「否」(B)，题 2 选「2」(B 选项字母序为 B)
    const radios = wrapper.findAll('input[type="radio"]');
    expect(radios.length).toBeGreaterThanOrEqual(4);
    await radios[1].setValue(); // 题1 第 2 个选项
    await radios[3].setValue(); // 题2 第 2 个选项
    await flushPromises();

    const submitBtn = wrapper.find('[data-test="quiz-submit-btn"]');
    expect(submitBtn.exists()).toBe(true);
    await submitBtn.trigger('click');
    await flushPromises();

    expect(vi.mocked(submitPracticeQuiz)).toHaveBeenCalledWith(
      expect.objectContaining({
        body: expect.objectContaining({
          quizId: QUIZ.quizId,
          answers: [
            { questionId: 11, answer: 'B' },
            { questionId: 12, answer: 'B' },
          ],
        }),
      })
    );

    const overview = wrapper.find('[data-test="quiz-result-overview"]');
    expect(overview.exists()).toBe(true);
    expect(overview.text()).toContain('2');
    expect(overview.text()).toContain('50');
    expect(overview.text()).toContain('新消灭');

    const resultCards = wrapper.findAll('[data-test="quiz-result-item"]');
    expect(resultCards.length).toBe(2);
    expect(resultCards[0].text()).toContain('回答错误');
    expect(resultCards[0].text()).toContain('地球是球体解析');
    expect(resultCards[1].text()).toContain('回答正确');
    expect(resultCards[1].text()).toContain('已掌握');
  });
});
