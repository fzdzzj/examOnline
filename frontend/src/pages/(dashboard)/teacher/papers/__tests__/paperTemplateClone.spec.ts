/**
 * 试卷模板与克隆界面用例（add-paper-template-clone，spec-delta frontend 两 Scenario）。
 *
 * 断言口径（有效性纪律：无 exists 守卫、无 || 双通兜底）：
 * - 「克隆」「另存为模板」按钮真实调用对应 SDK 端（携带 path.id），成功后 invalidateQueries 刷新列表；
 * - 「模板库」Tab 以 isTemplate=true 请求分页视图，切 Tab 后默认视图不带该参数（向后兼容）；
 * - 模板库「使用」以模板 id 调 clone 从模板建卷，且不删除模板本身。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { message } from 'ant-design-vue';

import * as api from '@/api/axios';
import PapersIndexPage from '@/pages/(dashboard)/teacher/papers/index.page.vue';

// ---- 各 mock 模块需要跨 beforeEach 共享的状态（vi.hoisted 提到 mock 之前） ----
const routerMock = vi.hoisted(() => ({ push: vi.fn(), back: vi.fn() }));
const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => ({ query: {} }) }));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  page1: vi.fn(),
  delete1: vi.fn(),
  clone: vi.fn(),
  saveAsTemplate: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref, watch } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown; queryFn: () => unknown; enabled?: unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      const run = () => {
        Promise.resolve()
          .then(options.queryFn)
          .then((value: unknown) => {
            data.value = value;
          })
          .catch((reason: unknown) => {
            error.value = reason;
          });
      };
      watch(
        () => [unref(options.queryKey), unref(options.enabled ?? true)] as const,
        ([, enabled]) => {
          if (enabled) run();
        },
        { immediate: true }
      );
      return { data, error, isFetching: ref(false), refetch: vi.fn() };
    },
  };
});

/** 普通视图两份试卷、模板视图一张模板卷 */
const PAPERS = [
  {
    id: 1,
    title: '试卷1',
    questionCount: 5,
    totalScore: 100,
    status: 0,
    createdTime: '2026-10-01T09:00:00',
  },
  {
    id: 2,
    title: '试卷2',
    questionCount: 5,
    totalScore: 100,
    status: 0,
    createdTime: '2026-10-01T09:00:00',
  },
];
const TEMPLATES = [
  {
    id: 101,
    title: '模板1（副本）',
    questionCount: 5,
    totalScore: 100,
    status: 0,
    createdTime: '2026-10-01T09:00:00',
  },
];

function servedQueries() {
  return vi.mocked(api.page1).mock.calls.map((call) => call[0]?.query);
}

beforeEach(() => {
  routerMock.push.mockClear();
  invalidateQueries.mockClear();
  vi.mocked(api.page1).mockReset();
  vi.mocked(api.clone).mockReset();
  vi.mocked(api.saveAsTemplate).mockReset();
  vi.mocked(api.delete1).mockReset();
  vi.mocked(api.page1).mockImplementation(((arg: { query: { isTemplate?: boolean } }) =>
    Promise.resolve(arg.query.isTemplate ? TEMPLATES : PAPERS)) as never);
  vi.mocked(api.clone).mockResolvedValue({ id: 9, title: '试卷1（副本）' } as never);
  vi.mocked(api.saveAsTemplate).mockResolvedValue({
    id: 100,
    title: '试卷1（副本）',
    isTemplate: 1,
  } as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

describe('试卷模板与克隆界面（papers/index.page）', () => {
  it('操作列「克隆」「另存为模板」调用对应 SDK 并刷新列表', async () => {
    const wrapper = mount(PapersIndexPage);
    await flushPromises();
    await flushPromises();

    const cloneButtons = wrapper.findAll('[data-test="paper-clone"]');
    const saveButtons = wrapper.findAll('[data-test="paper-save-template"]');
    expect(cloneButtons).toHaveLength(2);
    expect(saveButtons).toHaveLength(2);

    await cloneButtons[0].trigger('click');
    await flushPromises();
    expect(vi.mocked(api.clone).mock.calls[0][0]?.path).toEqual({ id: 1 });
    expect(message.success).toHaveBeenCalled();
    expect(invalidateQueries).toHaveBeenCalled();

    await saveButtons[1].trigger('click');
    await flushPromises();
    expect(vi.mocked(api.saveAsTemplate).mock.calls[0][0]?.path).toEqual({ id: 2 });
    expect(message.success).toHaveBeenCalledTimes(2);
    expect(invalidateQueries).toHaveBeenCalledTimes(2);
  });

  it('模板库 Tab 以 isTemplate=true 取数，「使用」以模板建卷且模板本身不动', async () => {
    const wrapper = mount(PapersIndexPage);
    await flushPromises();
    await flushPromises();

    // 默认视图不带 isTemplate（向后兼容：既有调用方行为零变化）
    expect(servedQueries()[0]).toEqual({ page: 1, size: 10 });

    const templateTab = wrapper.findAll('.ant-tabs-tab').find((el) => el.text().includes('模板库'));
    expect(templateTab).toBeDefined();
    await templateTab!.trigger('click');
    await flushPromises();

    const lastQuery = servedQueries()[servedQueries().length - 1];
    expect(lastQuery).toEqual({ page: 1, size: 10, isTemplate: true });

    const useButtons = wrapper.findAll('[data-test="template-use"]');
    expect(useButtons).toHaveLength(1);
    await useButtons[0].trigger('click');
    await flushPromises();

    expect(vi.mocked(api.clone).mock.calls[0][0]?.path).toEqual({ id: 101 });
    expect(message.success).toHaveBeenCalled();
    expect(vi.mocked(api.delete1)).not.toHaveBeenCalled();
  });
});
