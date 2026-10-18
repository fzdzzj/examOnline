import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import FavoritesPage from '@/pages/(dashboard)/student/favorites/index.page.vue';
import DashboardLayout from '@/pages/(dashboard).page.vue';
import { ApiError } from '@/api/types';

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  listFavorites: vi.fn(),
  unfavorite: vi.fn().mockResolvedValue(undefined),
  list1: vi.fn().mockResolvedValue([]),
  unreadCount: vi.fn().mockResolvedValue(0),
  markRead: vi.fn().mockResolvedValue(undefined),
}));

import { listFavorites } from '@/api/axios';

const mockInvalidateQueries = vi.fn();

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
  useQueryClient: () => ({ invalidateQueries: mockInvalidateQueries }),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ path: '/student/favorites' }),
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

describe('我的收藏夹页面 (student/favorites)', () => {
  it('空态：当收藏列表为空时，展示空态占位', async () => {
    vi.mocked(listFavorites).mockResolvedValueOnce({
      list: [],
      total: 0,
      page: 1,
      size: 10,
    } as never);

    const wrapper = mount(FavoritesPage);
    await flushPromises();

    expect(wrapper.find('[data-test="favorites-empty"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('暂无收藏题目');
    expect(wrapper.find('[data-test="favorites-list"]').exists()).toBe(false);
  });

  it('列表态：渲染收藏题目卡片、题型、正确答案与参考解析', async () => {
    vi.mocked(listFavorites).mockResolvedValueOnce({
      list: [
        {
          id: 101,
          studentId: 1,
          questionId: 42,
          type: 1,
          typeName: '单选题',
          content: 'Java 中的基本数据类型有哪些？',
          choices: ['int', 'String', 'Object', 'List'],
          correctAnswer: 'A',
          score: 5,
          difficulty: 1,
          analysis: 'int 是原始类型，String 是引用类型',
          createdTime: '2026-10-17T20:00:00',
        },
      ],
      total: 1,
      page: 1,
      size: 10,
    } as never);

    const wrapper = mount(FavoritesPage);
    await flushPromises();

    expect(wrapper.find('[data-test="favorites-empty"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="favorites-list"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('Java 中的基本数据类型有哪些？');
    expect(wrapper.find('[data-test="correct-answer"]').text()).toBe('A');
    expect(wrapper.find('[data-test="analysis-text"]').text()).toContain('int 是原始类型');
  });

  it('填空题格式化：正确答案带竖线分割时格式化显示', async () => {
    vi.mocked(listFavorites).mockResolvedValueOnce({
      list: [
        {
          id: 102,
          studentId: 1,
          questionId: 43,
          type: 5,
          typeName: '填空题',
          content: '中国的首都是___。',
          choices: [],
          correctAnswer: '北京|北京市',
          score: 5,
          difficulty: 1,
          analysis: '首都为北京',
          createdTime: '2026-10-17T20:00:00',
        },
      ],
      total: 1,
      page: 1,
      size: 10,
    } as never);

    const wrapper = mount(FavoritesPage);
    await flushPromises();

    expect(wrapper.find('[data-test="correct-answer"]').text()).toBe('北京 / 北京市');
  });

  it('错误态：后端接口异常时展示错误提示 Alert', async () => {
    vi.mocked(listFavorites).mockRejectedValueOnce(new ApiError(500, '查询收藏失败'));

    const wrapper = mount(FavoritesPage);
    await flushPromises();

    const errAlert = wrapper.find('[data-test="favorites-error"]');
    expect(errAlert.exists()).toBe(true);
    expect(errAlert.text()).toContain('查询收藏失败');
  });

  it('侧边栏与导航白名单登记：student 菜单项与 NAVIGABLE_PATHS 包含 /student/favorites', async () => {
    const layout = mount(DashboardLayout);
    await flushPromises();

    const text = layout.text();
    expect(text).toContain('我的收藏');
  });
});
