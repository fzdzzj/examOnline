/**
 * 试卷详情题目表回归：Table 必须由 ant-design-vue 真实渲染，不能只依赖题数/分值摘要。
 *
 * 数据与网络边界由 mock 提供；页面本身与 ant-design-vue Table 保持真实挂载，
 * 用例同时覆盖草稿态可编辑入口和锁定态只读内容。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

import type { PaperDetailResponse } from '@/api/axios';

const routeMock = vi.hoisted(() => ({ params: { id: '42' } }));
const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));

vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
}));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: vi.fn() },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'paper-detail-table' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  addQuestion: vi.fn(),
  commitRandomDraw: vi.fn(),
  detail1: vi.fn(),
  list: vi.fn(),
  previewDraw: vi.fn(),
  removeQuestion: vi.fn(),
  update1: vi.fn(),
  updateOrder: vi.fn(),
  updateQuestionScore: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown }) => {
      const key = unref(options.queryKey) as unknown[];
      return {
        data: ref(queryStore.data[String(key[0])]),
        isFetching: ref(false),
      };
    },
  };
});

import PaperDetailPage from '@/pages/(dashboard)/teacher/papers/[id].page.vue';

const QUESTIONS = [
  {
    questionId: 101,
    number: 1,
    score: 5,
    defaultScore: 5,
    questionType: 1,
    content: '函数 f(x)=x² 的最小值是多少？',
    questionDeleted: false,
  },
  {
    questionId: 102,
    number: 2,
    score: 10,
    defaultScore: 10,
    questionType: 4,
    content: '请说明二次函数图像的基本性质。',
    questionDeleted: false,
  },
];

function paper(status: number): PaperDetailResponse {
  return {
    id: 42,
    title: '高等数学期中试卷',
    description: '函数与导数',
    totalScore: 15,
    questionCount: QUESTIONS.length,
    status,
    questions: QUESTIONS,
  };
}

function mountPage(detail: PaperDetailResponse) {
  queryStore.data = { paper: detail, tags: [] };
  return mount(PaperDetailPage);
}

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('教师试卷详情：题目表真实呈现', () => {
  it('草稿态真实渲染题目表、题干和改分/排序/移出入口，且无 Table 解析警告', async () => {
    const wrapper = mountPage(paper(0));
    await flushPromises();

    expect(wrapper.find('.ant-table').exists()).toBe(true);
    expect(wrapper.find('.ant-table-tbody').text()).toContain(QUESTIONS[0].content);
    expect(wrapper.find('.ant-table-tbody').text()).toContain(QUESTIONS[1].content);
    expect(wrapper.text()).toContain('上移');
    expect(wrapper.text()).toContain('下移');
    expect(wrapper.text()).toContain('移出');
    expect(wrapper.findAll('input').length).toBeGreaterThan(0);

    const warnings = vi.mocked(console.warn).mock.calls.flat().join(' ');
    expect(warnings).not.toContain('Failed to resolve component: Table');
  });

  it('锁定态真实渲染题目内容和只读分值，不呈现编辑操作', async () => {
    const wrapper = mountPage(paper(1));
    await flushPromises();

    expect(wrapper.find('.ant-table').exists()).toBe(true);
    expect(wrapper.find('.ant-table-tbody').text()).toContain(QUESTIONS[0].content);
    expect(wrapper.find('.ant-table-tbody').text()).toContain(QUESTIONS[1].content);
    expect(wrapper.text()).toContain('只读');
    expect(wrapper.text()).not.toContain('上移');
    expect(wrapper.text()).not.toContain('下移');
    expect(wrapper.text()).not.toContain('移出');

    const warnings = vi.mocked(console.warn).mock.calls.flat().join(' ');
    expect(warnings).not.toContain('Failed to resolve component: Table');
  });
});
