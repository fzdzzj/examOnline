import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { Popconfirm, Select, message } from 'ant-design-vue';

import * as api from '@/api/axios';
import TeacherGradingPage from '@/pages/(dashboard)/teacher/grading/index.page.vue';

const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));
const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  page2: vi.fn(),
  run: vi.fn(),
  rejudge: vi.fn(),
  subjectiveQuestions: vi.fn(),
  subjectiveRows: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown }) => {
      const key = unref(options.queryKey) as unknown[];
      return {
        data: ref(queryStore.data[String(key[0])]),
        isFetching: ref(false),
        refetch: vi.fn(),
      };
    },
    useQueryClient: () => ({ invalidateQueries }),
  };
});

vi.mock('@/components/postexam/SubjectiveGradingPanel.vue', () => ({
  default: { template: '<div data-test="subjective-panel" />' },
}));

const ENDED_EXAM = { id: 7, title: '待判分考试', status: 2 };
const ANOTHER_ENDED_EXAM = { id: 10, title: '另一场待判分考试', status: 2 };
// 失败行会被重判结果就地回填（页面改的是 unwrap 出来的那个对象），所以每条用例都要拿到独立副本
function failure101() {
  return { submissionId: 101, studentId: 202, error: '答案格式错误' };
}
function failure102() {
  return { submissionId: 102, studentId: 203, error: '快照缺题' };
}

/** 页面文本（去掉空白：Vue 模板换行会在插值间渲染出空格，行文案断言按去空白口径比对）。 */
function bodyText(): string {
  return (document.body.textContent ?? '').replace(/\s+/g, '');
}

function rejudgeButtons(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('button').filter((button) => button.text() === '重判');
}

async function clickButtonByText(wrapper: ReturnType<typeof mount>, text: string): Promise<void> {
  const button = wrapper.findAll('button').find((item) => item.text() === text);
  expect(button).toBeTruthy();
  await button?.trigger('click');
  await flushPromises();
}

/** 选考试 → 运行整场判分，得到带失败清单的结果区（既有的整场判分链路，不做任何改写）。 */
async function mountAndRun(failures: unknown[]): Promise<ReturnType<typeof mount>> {
  vi.mocked(api.run).mockResolvedValue({
    total: failures.length + 2,
    success: 2,
    failed: failures.length,
    failures,
  } as never);
  const wrapper = mount(TeacherGradingPage, { attachTo: document.body });
  wrapper.findComponent(Select).vm.$emit('update:value', ENDED_EXAM.id);
  await flushPromises();
  await clickButtonByText(wrapper, '运行判分');
  return wrapper;
}

beforeEach(() => {
  queryStore.data = {
    exams: [ENDED_EXAM, ANOTHER_ENDED_EXAM],
    grading: [],
  };
  invalidateQueries.mockClear();
  vi.mocked(api.run).mockReset();
  vi.mocked(api.rejudge).mockReset();
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'info').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('判分失败答卷逐卷重判', () => {
  it('只在失败行呈现重判入口；无失败时不出现入口', async () => {
    const wrapper = await mountAndRun([failure101(), failure102()]);

    // 既有展示逻辑零改动：行文案仍是「答卷 ID（学生 ID）：后端原文」
    expect(bodyText()).toContain('答卷101（学生202）：答案格式错误');
    expect(bodyText()).toContain('答卷102（学生203）：快照缺题');
    // 两条失败行两个入口；两份成功卷只有统计值，不呈现入口
    expect(rejudgeButtons(wrapper)).toHaveLength(2);

    vi.mocked(api.run).mockResolvedValue({
      total: 2,
      success: 2,
      failed: 0,
      failures: [],
    } as never);
    await clickButtonByText(wrapper, '运行判分');

    expect(rejudgeButtons(wrapper)).toHaveLength(0);
    expect(bodyText()).toContain('判分完成');
  });

  it('确认后才调用 rejudge，路径参数取契约的 examId + submissionId', async () => {
    vi.mocked(api.rejudge).mockResolvedValue({ submissionId: 101, studentId: 202 } as never);
    const wrapper = await mountAndRun([failure101()]);

    // 入口已呈现但教师尚未确认：不得自动发起
    expect(rejudgeButtons(wrapper)).toHaveLength(1);
    expect(api.rejudge).not.toHaveBeenCalled();

    wrapper.findAllComponents(Popconfirm)[0].vm.$emit('confirm');
    await flushPromises();

    expect(api.rejudge).toHaveBeenCalledTimes(1);
    expect(api.rejudge).toHaveBeenCalledWith({
      client: expect.anything(),
      throwOnError: true,
      path: { examId: ENDED_EXAM.id, submissionId: 101 },
    });
  });

  it('重判在途时重复确认只发起一次请求', async () => {
    let resolveRejudge!: (value: unknown) => void;
    vi.mocked(api.rejudge).mockReturnValue(
      new Promise((resolve) => {
        resolveRejudge = resolve;
      }) as never
    );
    const wrapper = await mountAndRun([failure101()]);

    const popconfirm = wrapper.findAllComponents(Popconfirm)[0];
    popconfirm.vm.$emit('confirm');
    popconfirm.vm.$emit('confirm');
    expect(api.rejudge).toHaveBeenCalledTimes(1);

    resolveRejudge({ submissionId: 101, studentId: 202 });
    await flushPromises();
  });

  it('重判成功：该行移出清单、统计回填、失效批改查询并提示成功', async () => {
    vi.mocked(api.rejudge).mockResolvedValue({
      submissionId: 101,
      studentId: 202,
      error: null,
    } as never);
    const wrapper = await mountAndRun([failure101(), failure102()]);

    wrapper.findAllComponents(Popconfirm)[0].vm.$emit('confirm');
    await flushPromises();

    expect(bodyText()).not.toContain('答卷101');
    // 清单其余行不受影响
    expect(bodyText()).toContain('答卷102（学生203）：快照缺题');
    expect(rejudgeButtons(wrapper)).toHaveLength(1);
    // 统计随之回填，不新造第三种行形态
    expect(bodyText()).toContain('失败：1');
    expect(bodyText()).toContain('成功：3');
    expect(message.success).toHaveBeenCalledWith('答卷 101 重判成功');
    expect(message.error).not.toHaveBeenCalled();
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['grading'] });
  });

  it('重判仍失败：该行文案换成后端本次原文，清单不破坏、统计不回填', async () => {
    vi.mocked(api.rejudge).mockResolvedValue({
      submissionId: 101,
      studentId: 202,
      error: '答案 JSON 损坏',
    } as never);
    const wrapper = await mountAndRun([failure101(), failure102()]);

    wrapper.findAllComponents(Popconfirm)[0].vm.$emit('confirm');
    await flushPromises();

    expect(bodyText()).toContain('答卷101（学生202）：答案JSON损坏');
    expect(bodyText()).not.toContain('答案格式错误');
    expect(rejudgeButtons(wrapper)).toHaveLength(2);
    expect(bodyText()).toContain('失败：2');
    expect(bodyText()).toContain('成功：2');
    expect(message.error).toHaveBeenCalledWith('答案 JSON 损坏');
    expect(message.success).not.toHaveBeenCalled();
    expect(invalidateQueries).not.toHaveBeenCalled();
  });

  it('重判请求被拒：原文呈现且清单与统计保持原状，只由显式确认发起', async () => {
    vi.mocked(api.rejudge).mockRejectedValue(new Error('答卷不存在或不属于该考试'));
    const wrapper = await mountAndRun([failure101()]);

    const popconfirm = wrapper.findAllComponents(Popconfirm)[0];
    popconfirm.vm.$emit('confirm');
    await flushPromises();
    popconfirm.vm.$emit('confirm');
    await flushPromises();

    expect(message.error).toHaveBeenCalledWith('答卷不存在或不属于该考试');
    expect(message.success).not.toHaveBeenCalled();
    expect(bodyText()).toContain('答卷101（学生202）：答案格式错误');
    expect(bodyText()).toContain('失败：1');
    expect(rejudgeButtons(wrapper)).toHaveLength(1);
    expect(invalidateQueries).not.toHaveBeenCalled();
    // 两次调用都对应两次显式确认——请求失败后不自动补发
    expect(api.rejudge).toHaveBeenCalledTimes(2);
  });

  it('切换考试后到达的旧重判响应不回写结果区，也不刷新新考试的查询', async () => {
    let resolveRejudge!: (value: unknown) => void;
    vi.mocked(api.rejudge).mockReturnValue(
      new Promise((resolve) => {
        resolveRejudge = resolve;
      }) as never
    );
    const wrapper = await mountAndRun([failure101()]);

    wrapper.findAllComponents(Popconfirm)[0].vm.$emit('confirm');
    wrapper.findComponent(Select).vm.$emit('update:value', ANOTHER_ENDED_EXAM.id);
    await flushPromises();
    resolveRejudge({ submissionId: 101, studentId: 202 });
    await flushPromises();

    expect(bodyText()).not.toContain('最近一次判分结果');
    expect(message.success).not.toHaveBeenCalled();
    expect(invalidateQueries).not.toHaveBeenCalled();
  });
});
