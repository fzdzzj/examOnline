/**
 * 自适应测验入口用例（spec add-irt-adaptive-testing）：
 *
 * 学生练习入口（错题本页头部）新增「🧠 自适应测验」按钮，点击后弹出自适应测验面板，
 * 与错题强化自测入口并列且互不遮挡（面板在 Modal 内，destroy-on-close）。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref, watch } from 'vue';

import WrongQuestionsPage from '@/pages/(dashboard)/student/wrong-questions/index.page.vue';

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
  startAdaptiveQuiz: vi.fn(),
  submitAdaptiveQuizAnswer: vi.fn(),
  getAdaptiveQuizReport: vi.fn(),
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
        user: { id: 1, username: 'stu_1', name: '学生张三', roles: ['STUDENT'] },
      },
    }),
  };
});

afterEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
});

describe('自适应测验入口（错题本页）', () => {
  it('入口按钮存在，点击后在弹层内渲染自适应测验面板', async () => {
    const wrapper = mount(WrongQuestionsPage);
    await flushPromises();

    const startBtn = wrapper.find('[data-test="start-adaptive-quiz-btn"]');
    expect(startBtn.exists()).toBe(true);
    expect(startBtn.text()).toContain('自适应测验');

    await startBtn.trigger('click');
    await flushPromises();

    const panel = document.body.querySelector('[data-test="adaptive-quiz-panel"]');
    expect(panel).not.toBeNull();
    expect(document.body.querySelector('[data-test="adaptive-start-btn"]')).not.toBeNull();
    expect(document.body.querySelector('[data-test="adaptive-intro"]')).not.toBeNull();
  });
});
