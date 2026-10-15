/**
 * 试卷详情查询失败三态（harden-data-sanitization-and-ui-states M-03，U-1 口径在 papers/[id] 页的补全）。
 *
 * 现状缺陷：详情 `useQuery` 只解构 { data, isFetching }，未消费 error；
 * 查询失败时 data=undefined → 落「试卷不存在或加载失败」空态，与「试卷确实不存在」不可区分。
 *
 * 守住：
 * - 查询失败 → 错误 Alert（data-test="paper-error"）显性呈现后端 message，
 *   且「试卷不存在或加载失败」空态与详情内容均不出现；
 * - 成功 → 详情正常渲染、不弹错误 Alert（零回归）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

import type { PaperDetailResponse } from '@/api/axios';

// ---- 各 mock 模块需要跨 beforeEach 共享的状态（vi.hoisted 提到 mock 之前） ----
const routeMock = vi.hoisted(() => ({ params: { id: '42' } }));
const queryStore = vi.hoisted(() => ({
  data: {} as Record<string, unknown>,
  error: {} as Record<string, unknown>,
}));

vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
}));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: vi.fn() },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'paper-detail-query-failure' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  addQuestions: vi.fn(),
  commitRandomDraw: vi.fn(),
  detail1: vi.fn(),
  list: vi.fn(),
  previewDraw: vi.fn(),
  removeQuestion: vi.fn(),
  update1: vi.fn(),
  updateOrder: vi.fn(),
  updateQuestionScore: vi.fn(),
}));

/** useQuery mock：按 queryKey 首元素从 queryStore 取 data/error（查询失败经 error 槽注入）。 */
vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown }) => {
      const key = unref(options.queryKey) as unknown[];
      return {
        data: ref(queryStore.data[String(key[0])]),
        error: ref(queryStore.error[String(key[0])] ?? null),
        isFetching: ref(false),
        refetch: vi.fn(),
      };
    },
  };
});

import PaperDetailPage from '@/pages/(dashboard)/teacher/papers/[id].page.vue';

function paper(status: number): PaperDetailResponse {
  return {
    id: 42,
    title: '高等数学期中试卷',
    description: '函数与导数',
    totalScore: 15,
    questionCount: 2,
    status,
    questions: [
      {
        questionId: 101,
        number: 1,
        score: 5,
        defaultScore: 5,
        questionType: 1,
        content: '函数最小值？',
        questionDeleted: false,
      },
      {
        questionId: 102,
        number: 2,
        score: 10,
        defaultScore: 10,
        questionType: 4,
        content: '简述二次函数',
        questionDeleted: false,
      },
    ],
  };
}

function mountPage(overrides?: {
  data?: Record<string, unknown>;
  error?: Record<string, unknown>;
}) {
  queryStore.data = { paper: undefined, tags: [], ...(overrides?.data ?? {}) };
  queryStore.error = { ...(overrides?.error ?? {}) };
  return mount(PaperDetailPage);
}

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('试卷详情查询失败三态（M-03）', () => {
  it('查询失败：错误 Alert 显性呈现后端 message，「试卷不存在或加载失败」空态与详情均不出现', async () => {
    const wrapper = mountPage({ error: { paper: new Error('试卷加载失败') } });
    await flushPromises();

    const alert = wrapper.find('[data-test="paper-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('试卷加载失败');
    // 失败不得伪装成「试卷不存在」：既有空态文案不出现，详情内容不渲染
    expect(wrapper.text()).not.toContain('试卷不存在或加载失败');
    expect(wrapper.text()).not.toContain('高等数学期中试卷');
  });

  it('成功：详情正常渲染且不弹错误 Alert（零回归）', async () => {
    const wrapper = mountPage({ data: { paper: paper(0) } });
    await flushPromises();

    expect(wrapper.find('[data-test="paper-error"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('高等数学期中试卷');
    expect(wrapper.text()).toContain('函数最小值？');
  });
});
