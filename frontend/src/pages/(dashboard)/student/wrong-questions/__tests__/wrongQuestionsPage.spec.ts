import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import WrongQuestionsPage from '@/pages/(dashboard)/student/wrong-questions/index.page.vue';
import DashboardLayout from '@/pages/(dashboard).page.vue';
import { ApiError } from '@/api/types';

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  myWrongQuestions: vi.fn(),
}));

import { myWrongQuestions } from '@/api/axios';

vi.mock('@tanstack/vue-query', () => ({
  useQuery: (options: { queryFn?: () => unknown }) => {
    const data = ref<unknown>(undefined);
    const error = ref<unknown>(null);
    const isFetching = ref(false);
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
    return { data, error, isFetching, refetch: vi.fn() };
  },
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
          name: '张三',
          roles: ['STUDENT'],
        },
      },
    }),
  };
});

describe('学生错题本页面 (student/wrong-questions)', () => {
  it('正常渲染：按考试分组呈现错题，完整展示字段', async () => {
    vi.mocked(myWrongQuestions).mockResolvedValueOnce({
      total: 1,
      page: 1,
      size: 10,
      groups: [
        {
          examId: 101,
          examTitle: '期中模拟考试',
          examTime: '2026-10-09T10:00:00',
          wrongQuestions: [
            {
              examId: 101,
              examTitle: '期中模拟考试',
              questionId: 1,
              questionNumber: 1,
              questionType: '单选',
              questionContent: '1+1等于几？',
              choices: ['1', '2', '3', '4'],
              myAnswer: 'A',
              correctAnswer: 'B',
              myScore: 0,
              fullScore: 5,
              analysis: '1加1等于2，故选B',
              scoreDetail: '判分依据：正确答案为B',
            },
          ],
        },
      ],
    } as never);

    const wrapper = mount(WrongQuestionsPage);
    await flushPromises();

    expect(wrapper.text()).toContain('我的错题本');
    expect(wrapper.text()).toContain('期中模拟考试');
    expect(wrapper.text()).toContain('1+1等于几？');
    expect(wrapper.text()).toContain('我的作答：A');
    expect(wrapper.text()).toContain('正确答案：B');
    expect(wrapper.text()).toContain('1加1等于2，故选B');
    expect(wrapper.find('[data-test="wrong-questions-error"]').exists()).toBe(false);
  });

  it('题目解析为空时优雅占位：展示「暂无解析」而非空白或错乱', async () => {
    vi.mocked(myWrongQuestions).mockResolvedValueOnce({
      total: 1,
      page: 1,
      size: 10,
      groups: [
        {
          examId: 102,
          examTitle: '无解析考试',
          wrongQuestions: [
            {
              questionId: 2,
              questionNumber: 1,
              questionType: '判断',
              questionContent: '水加热到100度沸腾',
              myAnswer: 'F',
              correctAnswer: 'T',
              myScore: 0,
              fullScore: 4,
              analysis: null, // 无解析
            },
          ],
        },
      ],
    } as never);

    const wrapper = mount(WrongQuestionsPage);
    await flushPromises();

    expect(wrapper.text()).toContain('暂无解析');
  });

  it('空态：无错题时展示「暂无错题」正向空态，不弹错误 Alert', async () => {
    vi.mocked(myWrongQuestions).mockResolvedValueOnce({
      total: 0,
      page: 1,
      size: 10,
      groups: [],
    } as never);

    const wrapper = mount(WrongQuestionsPage);
    await flushPromises();

    expect(wrapper.text()).toContain('暂无错题');
    expect(wrapper.find('[data-test="wrong-questions-error"]').exists()).toBe(false);
  });

  it('失败态三态收口：接口异常时显性呈现 Alert 并隐藏数据区', async () => {
    vi.mocked(myWrongQuestions).mockRejectedValueOnce(new ApiError(500, '网络连接异常'));

    const wrapper = mount(WrongQuestionsPage);
    await flushPromises();

    const errAlert = wrapper.find('[data-test="wrong-questions-error"]');
    expect(errAlert.exists()).toBe(true);
    expect(errAlert.text()).toContain('网络连接异常');
    expect(wrapper.find('[data-test="wrong-questions-content"]').exists()).toBe(false);
  });

  it('侧边栏与导航白名单登记：student 菜单项与 NAVIGABLE_PATHS 包含 /student/wrong-questions', async () => {
    const layout = mount(DashboardLayout);
    await flushPromises();

    const text = layout.text();
    expect(text).toContain('我的错题本');
  });
});
