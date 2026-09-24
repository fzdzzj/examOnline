import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { message, Select } from 'ant-design-vue';

import * as api from '@/api/axios';
import { ApiError } from '@/api/types';
import TeacherScoresPage from '@/pages/(dashboard)/teacher/scores/index.page.vue';

const routerMock = vi.hoisted(() => ({ push: vi.fn() }));
const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));

vi.mock('vue-router', () => ({ useRouter: () => routerMock }));

vi.mock('vuex', () => ({
  useStore: () => ({ state: {} }),
}));

vi.mock('@/store', () => ({
  highestRoleOf: () => 'ADMIN',
}));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: vi.fn() },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  page2: vi.fn(),
  publish: vi.fn(),
  publishPreview: vi.fn(),
  revoke: vi.fn(),
  summarize: vi.fn(),
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
  };
});

beforeEach(() => {
  queryStore.data = { exams: [{ id: 7, title: '待判分考试', status: 2 }] };
  routerMock.push.mockClear();
  vi.mocked(api.summarize).mockReset();
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('成绩汇总前置失败引导', () => {
  it('拦截器包装后的汇总前置错误仍显示前往批改工作台', async () => {
    vi.mocked(api.summarize).mockRejectedValue(
      new ApiError(400, '请求参数不合法（存在未完成判分的答卷，不能汇总成绩）')
    );

    const wrapper = mount(TeacherScoresPage, { attachTo: document.body });
    await flushPromises();
    wrapper.findComponent(Select).vm.$emit('update:value', 7);
    await flushPromises();

    await wrapper
      .findAll('button')
      .find((button) => button.text() === '汇总成绩')
      ?.trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain(
      '请求参数不合法（存在未完成判分的答卷，不能汇总成绩）'
    );
    const gradingButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '前往批改工作台');
    expect(gradingButton).toBeTruthy();

    await gradingButton?.trigger('click');
    expect(routerMock.push).toHaveBeenCalledWith('/teacher/grading');
  });

  it('裸文案「存在未完成判分的答卷，不能汇总成绩」仍显示前往批改工作台', async () => {
    vi.mocked(api.summarize).mockRejectedValue(
      new ApiError(400, '存在未完成判分的答卷，不能汇总成绩')
    );

    const wrapper = mount(TeacherScoresPage, { attachTo: document.body });
    await flushPromises();
    wrapper.findComponent(Select).vm.$emit('update:value', 7);
    await flushPromises();

    await wrapper
      .findAll('button')
      .find((button) => button.text() === '汇总成绩')
      ?.trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('存在未完成判分的答卷，不能汇总成绩');
    const gradingButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '前往批改工作台');
    expect(gradingButton).toBeTruthy();
  });

  it('「判分服务暂不可用」不显示前往批改工作台', async () => {
    vi.mocked(api.summarize).mockRejectedValue(new Error('判分服务暂不可用'));

    const wrapper = mount(TeacherScoresPage, { attachTo: document.body });
    await flushPromises();
    wrapper.findComponent(Select).vm.$emit('update:value', 7);
    await flushPromises();

    await wrapper
      .findAll('button')
      .find((button) => button.text() === '汇总成绩')
      ?.trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('判分服务暂不可用');
    const gradingButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '前往批改工作台');
    expect(gradingButton).toBeFalsy();
  });

  it('网络故障只显示通用汇总失败，不引导到批改工作台', async () => {
    vi.mocked(api.summarize).mockRejectedValue(new Error('网络连接失败'));

    const wrapper = mount(TeacherScoresPage, { attachTo: document.body });
    await flushPromises();
    wrapper.findComponent(Select).vm.$emit('update:value', 7);
    await flushPromises();

    await wrapper
      .findAll('button')
      .find((button) => button.text() === '汇总成绩')
      ?.trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('网络连接失败');
    expect(document.body.textContent).not.toContain('前往批改工作台');
    expect(routerMock.push).not.toHaveBeenCalled();
  });

  it('旧考试的汇总失败请求在切换考试后不会污染新考试的错误展示', async () => {
    let rejectLate!: (error: unknown) => void;
    const pending = new Promise((_, reject) => {
      rejectLate = reject;
    });
    vi.mocked(api.summarize).mockReturnValue(pending as never);
    const wrapper = mount(TeacherScoresPage, { attachTo: document.body });
    await flushPromises();
    queryStore.data.exams = [
      { id: 7, title: '待判分考试', status: 2 },
      { id: 10, title: '另一场考试', status: 2 },
    ];
    wrapper.findComponent(Select).vm.$emit('update:value', 7);
    await flushPromises();
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '汇总成绩')
      ?.trigger('click');
    wrapper.findComponent(Select).vm.$emit('update:value', 10);
    await flushPromises();
    rejectLate(new ApiError(400, '请求参数不合法（存在未完成判分的答卷，不能汇总成绩）'));
    await flushPromises();
    const body = document.body.textContent || '';
    expect(body).not.toContain('存在未完成判分的答卷');
    const gradingBtn = wrapper
      .findAll('button')
      .find((button) => button.text() === '前往批改工作台');
    expect(gradingBtn).toBeFalsy();
  });

  it('旧考试汇总成功返回后，新考试不能出现旧的汇总结果卡片', async () => {
    let resolveLate!: (value: unknown) => void;
    const pending = new Promise((resolve) => {
      resolveLate = resolve;
    });
    vi.mocked(api.summarize).mockReturnValue(pending as never);

    const wrapper = mount(TeacherScoresPage, { attachTo: document.body });
    await flushPromises();
    queryStore.data.exams = [
      { id: 7, title: '待判分考试', status: 2 },
      { id: 10, title: '另一场考试', status: 2 },
    ];
    wrapper.findComponent(Select).vm.$emit('update:value', 7);
    await flushPromises();
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '汇总成绩')
      ?.trigger('click');

    wrapper.findComponent(Select).vm.$emit('update:value', 10);
    await flushPromises();

    resolveLate({ summarized: 5, skipped: 1, examGraded: true });
    await flushPromises();

    const body = document.body.textContent || '';
    expect(body).not.toContain('汇总结果');
    expect(body).not.toContain('本次汇总');
  });

  it('切换考试时清除已展示的汇总结果卡片', async () => {
    vi.mocked(api.summarize).mockResolvedValue({
      summarized: 3,
      skipped: 0,
      examGraded: false,
    } as never);

    const wrapper = mount(TeacherScoresPage, { attachTo: document.body });
    await flushPromises();
    queryStore.data.exams = [
      { id: 7, title: '待判分考试', status: 2 },
      { id: 10, title: '另一场考试', status: 2 },
    ];
    wrapper.findComponent(Select).vm.$emit('update:value', 7);
    await flushPromises();
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '汇总成绩')
      ?.trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('汇总结果');
    expect(document.body.textContent).toContain('本次汇总');

    wrapper.findComponent(Select).vm.$emit('update:value', 10);
    await flushPromises();

    const body = document.body.textContent || '';
    expect(body).not.toContain('汇总结果');
    expect(body).not.toContain('本次汇总');
  });
});
