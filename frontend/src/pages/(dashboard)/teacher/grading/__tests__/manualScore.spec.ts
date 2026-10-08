import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { InputNumber, Popconfirm, Select, message } from 'ant-design-vue';

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
  manualScore: vi.fn(),
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
// 失败行会被给分结果就地回填（页面改的是 unwrap 出来的那个对象），所以每条用例都要拿到独立副本
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

function actionButtons(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').filter((button) => button.text() === text);
}

/** 行内动作的 Popconfirm 按触发器文案区分（失败行另有「重判」的 Popconfirm）。 */
function actionPopconfirms(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper
    .findAllComponents(Popconfirm)
    .filter((popconfirm) => popconfirm.text().trim() === text);
}

function scoreInputs(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAllComponents(InputNumber);
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
  const runButton = wrapper.findAll('button').find((button) => button.text() === '运行判分');
  expect(runButton).toBeTruthy();
  await runButton?.trigger('click');
  await flushPromises();
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
  vi.mocked(api.manualScore).mockReset();
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'info').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('判分失败答卷手动给分', () => {
  it('只在失败行呈现手动给分入口并与「重判」并存；无失败时不出现入口', async () => {
    const wrapper = await mountAndRun([failure101(), failure102()]);

    // 既有展示逻辑零改动：行文案仍是「答卷 ID（学生 ID）：后端原文」
    expect(bodyText()).toContain('答卷101（学生202）：答案格式错误');
    expect(bodyText()).toContain('答卷102（学生203）：快照缺题');
    // 两条失败行各一个手动给分入口，且与「重判」并存
    expect(actionButtons(wrapper, '手动给分')).toHaveLength(2);
    expect(actionButtons(wrapper, '重判')).toHaveLength(2);
    expect(actionPopconfirms(wrapper, '手动给分')).toHaveLength(2);

    vi.mocked(api.run).mockResolvedValue({
      total: 2,
      success: 2,
      failed: 0,
      failures: [],
    } as never);
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '运行判分')
      ?.trigger('click');
    await flushPromises();

    expect(actionButtons(wrapper, '手动给分')).toHaveLength(0);
    expect(actionPopconfirms(wrapper, '手动给分')).toHaveLength(0);
    expect(bodyText()).toContain('判分完成');
  });

  it('确认文案明示绕过引擎且教师裁定即终局，数值精度对齐契约', async () => {
    const wrapper = await mountAndRun([failure101()]);

    const title = String(actionPopconfirms(wrapper, '手动给分')[0].props('title'));
    expect(title).toContain('绕过判分引擎');
    expect(title).toContain('教师裁定即终局');
    expect(title).toContain('不再重算');

    // 契约 ManualScoreRequest @Digits(integer = 3, fraction = 1) → 非负、最多 1 位小数
    const input = scoreInputs(wrapper)[0];
    expect(input.props('min')).toBe(0);
    expect(input.props('max')).toBe(999);
    expect(input.props('precision')).toBe(1);

    // 未确认前不发起任何请求
    expect(api.manualScore).not.toHaveBeenCalled();
  });

  it('确认后按契约路径参数与请求体调用 manualScore；未录入分数不发起', async () => {
    vi.mocked(api.manualScore).mockResolvedValue(undefined as never);
    const wrapper = await mountAndRun([failure101()]);
    const popconfirm = actionPopconfirms(wrapper, '手动给分')[0];

    // 空值不构成一次给分请求（不是后端会拒绝的业务错误，而是这次动作不成立）
    popconfirm.vm.$emit('confirm');
    await flushPromises();
    expect(api.manualScore).not.toHaveBeenCalled();
    expect(message.error).not.toHaveBeenCalled();

    scoreInputs(wrapper)[0].vm.$emit('update:value', 88.5);
    await flushPromises();
    popconfirm.vm.$emit('confirm');
    await flushPromises();

    expect(api.manualScore).toHaveBeenCalledTimes(1);
    expect(api.manualScore).toHaveBeenCalledWith({
      client: expect.anything(),
      throwOnError: true,
      path: { examId: ENDED_EXAM.id, submissionId: 101 },
      body: { objectiveScore: 88.5 },
    });
  });

  it('给分在途时重复确认只发起一次请求', async () => {
    let resolveManual!: (value: unknown) => void;
    vi.mocked(api.manualScore).mockReturnValue(
      new Promise((resolve) => {
        resolveManual = resolve;
      }) as never
    );
    const wrapper = await mountAndRun([failure101()]);
    scoreInputs(wrapper)[0].vm.$emit('update:value', 60);
    await flushPromises();

    const popconfirm = actionPopconfirms(wrapper, '手动给分')[0];
    popconfirm.vm.$emit('confirm');
    popconfirm.vm.$emit('confirm');
    expect(api.manualScore).toHaveBeenCalledTimes(1);

    resolveManual(undefined);
    await flushPromises();
  });

  it('给分成功：该行移出清单、统计回填、失效批改查询并提示成功', async () => {
    vi.mocked(api.manualScore).mockResolvedValue(undefined as never);
    const wrapper = await mountAndRun([failure101(), failure102()]);
    scoreInputs(wrapper)[0].vm.$emit('update:value', 88.5);
    await flushPromises();

    actionPopconfirms(wrapper, '手动给分')[0].vm.$emit('confirm');
    await flushPromises();

    expect(bodyText()).not.toContain('答卷101');
    // 清单其余行不受影响
    expect(bodyText()).toContain('答卷102（学生203）：快照缺题');
    expect(actionButtons(wrapper, '手动给分')).toHaveLength(1);
    // 统计随之回填（与重判同口径），不新造第三种行形态
    expect(bodyText()).toContain('失败：1');
    expect(bodyText()).toContain('成功：3');
    expect(message.success).toHaveBeenCalledWith('答卷 101 手动给分成功');
    expect(message.error).not.toHaveBeenCalled();
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['grading'] });
  });

  it('给分请求被拒：原文呈现且清单与统计保持原状，不自动重试', async () => {
    vi.mocked(api.manualScore).mockRejectedValue(new Error('手动给分不得超过客观题满分 90'));
    const wrapper = await mountAndRun([failure101()]);
    scoreInputs(wrapper)[0].vm.$emit('update:value', 95);
    await flushPromises();

    const popconfirm = actionPopconfirms(wrapper, '手动给分')[0];
    popconfirm.vm.$emit('confirm');
    await flushPromises();
    popconfirm.vm.$emit('confirm');
    await flushPromises();

    expect(message.error).toHaveBeenCalledWith('手动给分不得超过客观题满分 90');
    expect(message.success).not.toHaveBeenCalled();
    expect(bodyText()).toContain('答卷101（学生202）：答案格式错误');
    expect(bodyText()).toContain('失败：1');
    expect(actionButtons(wrapper, '手动给分')).toHaveLength(1);
    expect(invalidateQueries).not.toHaveBeenCalled();
    // 两次调用都对应两次显式确认——请求失败后不自动补发
    expect(api.manualScore).toHaveBeenCalledTimes(2);
  });

  it('切换考试后到达的旧给分响应不回写结果区，也不刷新新考试的查询', async () => {
    let resolveManual!: (value: unknown) => void;
    vi.mocked(api.manualScore).mockReturnValue(
      new Promise((resolve) => {
        resolveManual = resolve;
      }) as never
    );
    const wrapper = await mountAndRun([failure101()]);
    scoreInputs(wrapper)[0].vm.$emit('update:value', 60);
    await flushPromises();

    actionPopconfirms(wrapper, '手动给分')[0].vm.$emit('confirm');
    wrapper.findComponent(Select).vm.$emit('update:value', ANOTHER_ENDED_EXAM.id);
    await flushPromises();
    resolveManual(undefined);
    await flushPromises();

    expect(bodyText()).not.toContain('最近一次判分结果');
    expect(message.success).not.toHaveBeenCalled();
    expect(invalidateQueries).not.toHaveBeenCalled();
  });

  it('重判入口与行为零回归：确认文案与路径参数不变，且不误触手动给分', async () => {
    vi.mocked(api.rejudge).mockResolvedValue({ submissionId: 101, studentId: 202 } as never);
    const wrapper = await mountAndRun([failure101()]);

    const rejudgePopconfirm = actionPopconfirms(wrapper, '重判')[0];
    expect(String(rejudgePopconfirm.props('title'))).toBe('将对这一份答卷重新判分，确认重判？');

    rejudgePopconfirm.vm.$emit('confirm');
    await flushPromises();

    expect(api.rejudge).toHaveBeenCalledWith({
      client: expect.anything(),
      throwOnError: true,
      path: { examId: ENDED_EXAM.id, submissionId: 101 },
    });
    expect(api.manualScore).not.toHaveBeenCalled();
  });
});
