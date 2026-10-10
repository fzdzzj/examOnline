import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { Alert, Empty, Table } from 'ant-design-vue';

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref, watch } = await import('vue');
  return {
    useQuery: (options: { queryKey?: unknown; queryFn?: () => unknown; enabled?: unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      const isFetching = ref(false);
      const run = () => {
        if (options.enabled !== undefined && !unref(options.enabled)) return;
        isFetching.value = true;
        Promise.resolve()
          .then(options.queryFn as () => unknown)
          .then((value: unknown) => {
            data.value = value;
            isFetching.value = false;
          })
          .catch((reason: unknown) => {
            error.value = reason;
            isFetching.value = false;
          });
      };
      if (options.enabled !== undefined) {
        watch(() => unref(options.enabled), run);
      }
      run();
      return { data, error, isFetching, refetch: run };
    },
  };
});

import LeaderboardPanel, { type LeaderboardRowView } from '../LeaderboardPanel.vue';
import type { ScoreLeaderboardResponse } from '@/api/axios';
import { ApiError } from '@/api/types';

const MOCK_LEADERBOARD_TOP_WITH_ME: ScoreLeaderboardResponse = {
  examId: 101,
  examTitle: '期末统考',
  myRow: undefined,
  top: [
    { rank: 1, displayName: '张**', totalScore: 98, isMe: false },
    { rank: 2, displayName: '李四', totalScore: 95, isMe: true },
    { rank: 3, displayName: '王**', totalScore: 90, isMe: false },
  ],
};

const MOCK_LEADERBOARD_OUTSIDE_TOP10: ScoreLeaderboardResponse = {
  examId: 102,
  examTitle: '全校联考',
  myRow: { rank: 13, totalScore: 60, isMe: true },
  top: Array.from({ length: 10 }, (_, i) => ({
    rank: i + 1,
    displayName: `学**${i + 1}`,
    totalScore: 100 - i * 2,
    isMe: false,
  })),
};

describe('LeaderboardPanel (班级匿名榜单组件)', () => {
  it('渲染匿名前 10 榜单与本人行高亮', () => {
    const wrapper = mount(LeaderboardPanel, {
      props: { data: MOCK_LEADERBOARD_TOP_WITH_ME },
    });

    const table = wrapper.findComponent(Table);
    expect(table.exists()).toBe(true);

    const rows = table.props('dataSource') as LeaderboardRowView[];
    expect(rows).toHaveLength(3);

    // 第 1 名：他人，脱敏
    expect(rows[0].rank).toBe(1);
    expect(rows[0].displayName).toBe('张**');
    expect(rows[0].totalScore).toBe(98);
    expect(rows[0].isMe).toBe(false);

    // 第 2 名：本人，实名高亮
    expect(rows[1].rank).toBe(2);
    expect(rows[1].displayName).toBe('李四');
    expect(rows[1].totalScore).toBe(95);
    expect(rows[1].isMe).toBe(true);

    // 严禁包含他人 studentId
    expect((rows[0] as unknown as Record<string, unknown>).studentId).toBeUndefined();
    expect((rows[2] as unknown as Record<string, unknown>).studentId).toBeUndefined();

    // 包含"本人"标记 Tag
    expect(wrapper.text()).toContain('李四');
    expect(wrapper.text()).toContain('本人');
  });

  it('本人不在前 10 时在榜尾渲染 myRow 行', () => {
    const wrapper = mount(LeaderboardPanel, {
      props: { data: MOCK_LEADERBOARD_OUTSIDE_TOP10 },
    });

    const table = wrapper.findComponent(Table);
    expect(table.exists()).toBe(true);

    const rows = table.props('dataSource') as LeaderboardRowView[];
    // 前 10 名 + 榜尾本人行共 11 行
    expect(rows).toHaveLength(11);

    // 前 10 名皆非本人且脱敏
    for (let i = 0; i < 10; i++) {
      expect(rows[i].isMe).toBe(false);
      expect(rows[i].displayName).toContain('**');
    }

    // 榜尾行（第 11 行）：本人名次 13、总分 60、isMe=true
    const myRow = rows[10];
    expect(myRow.rank).toBe(13);
    expect(myRow.totalScore).toBe(60);
    expect(myRow.isMe).toBe(true);
    expect(myRow.isBottomMyRow).toBe(true);
    expect(myRow.displayName).toBe('本人');

    expect(wrapper.text()).toContain('本人 (榜尾)');
  });

  it('404 语义（暂无本人成绩记录）：渲染空态文案且不渲染表格骨架', () => {
    const wrapper = mount(LeaderboardPanel, {
      props: { error: new ApiError(404, '暂无本人成绩记录') },
    });

    expect(wrapper.findComponent(Table).exists()).toBe(false);
    const empty = wrapper.findComponent(Empty);
    expect(empty.exists()).toBe(true);
    expect(empty.props('description')).toBe('暂无本人成绩记录');
    expect(wrapper.text()).toContain('暂无本人成绩记录');
    expect(wrapper.findComponent(Alert).exists()).toBe(false);
  });

  it('非 404 错误（如 500 / 网络异常）：渲染错误 Alert 而非空态', () => {
    const wrapper = mount(LeaderboardPanel, {
      props: { error: new Error('网络连接超时') },
    });

    expect(wrapper.findComponent(Table).exists()).toBe(false);
    const alert = wrapper.findComponent(Alert);
    expect(alert.exists()).toBe(true);
    expect(alert.props('type')).toBe('error');
    expect(alert.props('message')).toBe('网络连接超时');
    expect(wrapper.findComponent(Empty).exists()).toBe(false);
  });
});
