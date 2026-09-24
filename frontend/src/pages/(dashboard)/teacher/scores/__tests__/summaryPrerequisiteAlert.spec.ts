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
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('成绩汇总前置失败引导', () => {
  it('后端拒绝未判分汇总时显示原因，并提供回批改工作台操作', async () => {
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

    await gradingButton?.trigger('click');
    expect(routerMock.push).toHaveBeenCalledWith('/teacher/grading');
  });
});
