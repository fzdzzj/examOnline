/**
 * 自适应测验面板用例（spec add-irt-adaptive-testing）：
 *
 * - 开局：缺省题数 10 发起 start，渲染首题且不展示答案解析；
 * - 逐题作答：单选提交后即时反馈判定与 θ/SE，并进入下一题（能力值实时反馈）；
 * - 终止：后端返回 terminated 时拉取能力报告，展示 θ/SE/正确率/终止原因/逐题轨迹；
 * - 失败：题池为空等后端错误原文呈现（不吞错）。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

import AdaptiveQuizPanel from '@/components/student/AdaptiveQuizPanel.vue';
import { getAdaptiveQuizReport, startAdaptiveQuiz, submitAdaptiveQuizAnswer } from '@/api/axios';
import { QUESTION_TYPE_LABELS } from '@/utils/questionTypes';

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  startAdaptiveQuiz: vi.fn(),
  submitAdaptiveQuizAnswer: vi.fn(),
  getAdaptiveQuizReport: vi.fn(),
}));

const START_RESPONSE = {
  sessionId: 9,
  status: 'IN_PROGRESS',
  theta: 0,
  se: 1,
  answeredCount: 0,
  maxItems: 10,
  seThreshold: 0.3,
  currentQuestion: {
    order: 1,
    questionId: 11,
    questionType: QUESTION_TYPE_LABELS[1],
    questionContent: '地球是平的吗？',
    choices: ['是', '否'],
  },
};

const NEXT_QUESTION = {
  order: 2,
  questionId: 12,
  questionType: QUESTION_TYPE_LABELS[5],
  questionContent: '光速约为____ m/s',
  choices: null,
};

const ANSWER_CORRECT = {
  sessionId: 9,
  questionId: 11,
  correct: true,
  correctAnswer: 'B',
  analysis: '地球是球体',
  theta: 0.62,
  se: 0.81,
  answeredCount: 1,
  terminated: false,
  terminationReason: null,
  nextQuestion: NEXT_QUESTION,
};

const ANSWER_FINISHED = {
  sessionId: 9,
  questionId: 11,
  correct: false,
  correctAnswer: 'B',
  analysis: '地球是球体',
  theta: -0.35,
  se: 0.29,
  answeredCount: 10,
  terminated: true,
  terminationReason: 'MAX_ITEMS',
  nextQuestion: null,
};

const REPORT = {
  sessionId: 9,
  studentId: 7,
  status: 'COMPLETED',
  theta: 0.42,
  se: 0.28,
  answeredCount: 2,
  maxItems: 10,
  correctCount: 1,
  accuracy: 50,
  terminationReason: 'MAX_ITEMS',
  items: [
    {
      order: 1,
      questionId: 11,
      questionType: QUESTION_TYPE_LABELS[1],
      questionContent: '地球是平的吗？',
      answer: 'B',
      correct: true,
      correctAnswer: 'B',
      thetaAfter: 0.7,
      seAfter: 0.8,
    },
    {
      order: 2,
      questionId: 12,
      questionType: QUESTION_TYPE_LABELS[5],
      questionContent: '光速约为____ m/s',
      answer: '300000000',
      correct: false,
      correctAnswer: '300000000',
      thetaAfter: 0.42,
      seAfter: 0.28,
    },
  ],
};

async function mountAndStart() {
  vi.mocked(startAdaptiveQuiz).mockResolvedValue(START_RESPONSE as never);
  const wrapper = mount(AdaptiveQuizPanel);
  await wrapper.find('[data-test="adaptive-start-btn"]').trigger('click');
  await flushPromises();
  return wrapper;
}

afterEach(() => {
  vi.clearAllMocks();
});

describe('自适应测验面板', () => {
  it('开局：缺省题数 10 发起 start，渲染首题且不泄露答案解析', async () => {
    const wrapper = await mountAndStart();

    expect(vi.mocked(startAdaptiveQuiz)).toHaveBeenCalledWith(
      expect.objectContaining({ body: expect.objectContaining({ maxItems: 10 }) })
    );
    const question = wrapper.find('[data-test="adaptive-question"]');
    expect(question.exists()).toBe(true);
    expect(question.text()).toContain('地球是平的吗？');
    expect(question.text()).not.toContain('正确答案');
    // 首题作答前 θ=0 / SE=1（先验）
    expect(wrapper.find('[data-test="adaptive-theta"]').text()).toBe('0.00');
    expect(wrapper.find('[data-test="adaptive-se"]').text()).toBe('1.00');
  });

  it('逐题作答：提交单选答案后即时展示判定与 θ/SE，并进入下一题', async () => {
    vi.mocked(submitAdaptiveQuizAnswer).mockResolvedValue(ANSWER_CORRECT as never);
    const wrapper = await mountAndStart();

    const radios = wrapper.findAll('input[type="radio"]');
    expect(radios.length).toBe(2);
    await radios[1].setValue();
    await wrapper.find('[data-test="adaptive-submit-btn"]').trigger('click');
    await flushPromises();

    expect(vi.mocked(submitAdaptiveQuizAnswer)).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { sessionId: 9 },
        body: { questionId: 11, answer: 'B' },
      })
    );
    const feedback = wrapper.find('[data-test="adaptive-feedback"]');
    expect(feedback.exists()).toBe(true);
    expect(feedback.text()).toContain('回答正确');
    expect(feedback.text()).toContain('B');
    expect(feedback.text()).toContain('地球是球体');
    // 能力值实时反馈（θ 由后端 EAP 给出，前端只呈现）
    expect(wrapper.find('[data-test="adaptive-theta"]').text()).toBe('0.62');
    expect(wrapper.find('[data-test="adaptive-se"]').text()).toBe('0.81');
    // 下一题（填空）已就绪
    expect(wrapper.find('[data-test="adaptive-question"]').text()).toContain('光速约为');
    expect(wrapper.find('[data-test="adaptive-fill-input"]').exists()).toBe(true);
  });

  it('终止：后端返回 terminated 时拉取能力报告，展示 θ/SE/正确率/终止原因/逐题轨迹', async () => {
    vi.mocked(submitAdaptiveQuizAnswer).mockResolvedValue(ANSWER_FINISHED as never);
    vi.mocked(getAdaptiveQuizReport).mockResolvedValue(REPORT as never);
    const wrapper = await mountAndStart();

    const radios = wrapper.findAll('input[type="radio"]');
    await radios[1].setValue();
    await wrapper.find('[data-test="adaptive-submit-btn"]').trigger('click');
    await flushPromises();

    expect(vi.mocked(getAdaptiveQuizReport)).toHaveBeenCalledWith(
      expect.objectContaining({ path: { sessionId: 9 } })
    );
    const report = wrapper.find('[data-test="adaptive-report"]');
    expect(report.exists()).toBe(true);
    expect(wrapper.find('[data-test="adaptive-termination-reason"]').text()).toContain(
      '已达最大题数'
    );
    expect(wrapper.find('[data-test="adaptive-report-theta"]').text()).toBe('0.42');
    expect(wrapper.find('[data-test="adaptive-report-se"]').text()).toBe('0.28');
    expect(wrapper.find('[data-test="adaptive-report-accuracy"]').text()).toBe('50%');
    const items = wrapper.findAll('[data-test="adaptive-report-item"]');
    expect(items.length).toBe(2);
    expect(items[0].text()).toContain('地球是平的吗？');
    expect(items[1].text()).toContain('光速约为');
  });

  it('失败：题池不可用等后端错误原文呈现，不静默', async () => {
    vi.mocked(startAdaptiveQuiz).mockRejectedValue(new Error('暂无可用自适应题池'));
    const wrapper = mount(AdaptiveQuizPanel);

    await wrapper.find('[data-test="adaptive-start-btn"]').trigger('click');
    await flushPromises();

    const error = wrapper.find('[data-test="adaptive-error"]');
    expect(error.exists()).toBe(true);
    expect(error.text()).toContain('暂无可用自适应题池');
    expect(wrapper.find('[data-test="adaptive-question"]').exists()).toBe(false);
  });
});
