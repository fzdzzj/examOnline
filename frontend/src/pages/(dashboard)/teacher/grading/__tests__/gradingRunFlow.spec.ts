import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { Alert, Select, message } from 'ant-design-vue';

import * as api from '@/api/axios';
import TeacherGradingPage from '@/pages/(dashboard)/teacher/grading/index.page.vue';

const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));
const refetches = vi.hoisted(() => ({ questions: vi.fn(), rows: vi.fn() }));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  page2: vi.fn(),
  run: vi.fn(),
  subjectiveQuestions: vi.fn(),
  subjectiveRows: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown }) => {
      const key = unref(options.queryKey) as unknown[];
      const refetch = key[1] === 'questions' ? refetches.questions : refetches.rows;
      return {
        data: ref(queryStore.data[String(key[0])]),
        isFetching: ref(false),
        refetch,
      };
    },
  };
});

vi.mock('@/components/postexam/SubjectiveGradingPanel.vue', () => ({
  default: { template: '<div data-test="subjective-panel" />' },
}));

const ENDED_EXAM = { id: 7, title: '待判分考试', status: 2 };
const GRADED_EXAM = { id: 8, title: '已批改考试', status: 3 };
const PUBLISHED_EXAM = { id: 9, title: '已发布考试', status: 4 };

function bodyText(): string {
  return document.body.textContent ?? '';
}

async function selectExam(wrapper: ReturnType<typeof mount>, examId: number): Promise<void> {
  wrapper.findComponent(Select).vm.$emit('update:value', examId);
  await flushPromises();
}

beforeEach(() => {
  queryStore.data = {
    exams: [ENDED_EXAM, GRADED_EXAM, PUBLISHED_EXAM],
    grading: [],
  };
  refetches.questions.mockClear();
  refetches.rows.mockClear();
  vi.mocked(api.run).mockReset();
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'info').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('教师批改工作台显式运行判分', () => {
  it('仅已结束考试显示入口；重复点击只调用一次，并刷新题级进度与学生行', async () => {
    let resolveRun!: (value: unknown) => void;
    vi.mocked(api.run).mockReturnValue(
      new Promise((resolve) => {
        resolveRun = resolve;
      }) as never
    );

    const wrapper = mount(TeacherGradingPage, { attachTo: document.body });
    await selectExam(wrapper, ENDED_EXAM.id);

    const runButton = wrapper.findAll('button').find((button) => button.text() === '运行判分');
    expect(runButton).toBeTruthy();
    await runButton?.trigger('click');
    await runButton?.trigger('click');
    expect(api.run).toHaveBeenCalledTimes(1);
    expect(api.run).toHaveBeenCalledWith({
      client: expect.anything(),
      throwOnError: true,
      path: { examId: ENDED_EXAM.id },
    });

    resolveRun({ total: 2, success: 2, failed: 0, failures: [] });
    await flushPromises();

    expect(bodyText()).toContain('总数');
    expect(bodyText()).toContain('2');
    expect(bodyText()).toContain('成功');
    expect(bodyText()).toContain('判分完成');
    expect(refetches.questions).toHaveBeenCalledTimes(1);
    expect(refetches.rows).toHaveBeenCalledTimes(1);

    await selectExam(wrapper, GRADED_EXAM.id);
    expect(wrapper.findAll('button').some((button) => button.text() === '运行判分')).toBe(false);
    await selectExam(wrapper, PUBLISHED_EXAM.id);
    expect(wrapper.findAll('button').some((button) => button.text() === '运行判分')).toBe(false);
  });

  it('零答卷和部分失败分别呈现，不冒称判分成功，并显示失败清单', async () => {
    vi.mocked(api.run).mockResolvedValue({
      total: 3,
      success: 2,
      failed: 1,
      failures: [{ submissionId: 101, studentId: 202, error: '答案格式错误' }],
    } as never);

    const wrapper = mount(TeacherGradingPage, { attachTo: document.body });
    await selectExam(wrapper, ENDED_EXAM.id);
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '运行判分')
      ?.trigger('click');
    await flushPromises();

    expect(bodyText()).toContain('总数');
    expect(bodyText()).toContain('3');
    expect(bodyText()).toContain('成功');
    expect(bodyText()).toContain('2');
    expect(bodyText()).toContain('失败');
    expect(bodyText()).toContain('1');
    expect(bodyText()).toContain('答案格式错误');
    expect(bodyText()).toContain('部分答卷判分失败');
    expect(bodyText()).not.toContain('判分完成');

    vi.mocked(api.run).mockResolvedValue({
      total: 0,
      success: 0,
      failed: 0,
      failures: [],
    } as never);
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '运行判分')
      ?.trigger('click');
    await flushPromises();

    expect(bodyText()).toContain('无已交卷答卷');
    expect(
      wrapper.findAllComponents(Alert).some((alert) => alert.text().includes('判分完成'))
    ).toBe(false);
  });
});
