/**
 * 组卷批量入卷（add-paper-batch-add-questions）前端护栏与行为用例。
 *
 * 词法护栏：组卷页源码不得再出现串行单题入卷循环（半批静默缺陷的根源——
 * 中途失败即中断且无提示），必须走单次批量调用；变异还原（改回逐题
 * await unwrap(addQuestion(...)) 循环）应红。
 * 行为用例：失败提示透出 ApiError message（不再半批静默）、成功路径
 * （单次批量调用 + 成功提示 + invalidatePaper）、「所选题目均已在试卷中」前置判断保留。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { message } from 'ant-design-vue';

import type { PaperDetailResponse } from '@/api/axios';

// 文件名含 [id]，new URL() 会把方括号误解析为 IPv6 主机，故用 path 拼
const PAGE_SOURCE = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '[id].page.vue');

const routeMock = vi.hoisted(() => ({ params: { id: '42' } }));
const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));
const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
}));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'paper-batch-add' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  addQuestion: vi.fn(),
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
import QuestionPickerModal from '@/components/question/QuestionPickerModal.vue';
import * as api from '@/api/axios';

/** 卷中已有题目 101、102（草稿态） */
function paper(): PaperDetailResponse {
  return {
    id: 42,
    title: '批量入卷测试卷',
    description: '',
    totalScore: 15,
    questionCount: 2,
    status: 0,
    questions: [
      { questionId: 101, number: 1, score: 5, questionDeleted: false },
      { questionId: 102, number: 2, score: 10, questionDeleted: false },
    ],
  };
}

function mountPage() {
  queryStore.data = { paper: paper(), tags: [] };
  return mount(PaperDetailPage);
}

/** 模拟选题器确认选择（emit picked）。 */
function pick(wrapper: ReturnType<typeof mountPage>, ids: number[]) {
  wrapper.findComponent(QuestionPickerModal).vm.$emit(
    'picked',
    ids.map((id) => ({ id }))
  );
}

beforeEach(() => {
  // 模块 mock（vi.fn()）的调用记录不随 restoreAllMocks 重置，须逐用例清零
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  vi.spyOn(message, 'info').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('词法护栏：组卷页 onPick 批量化', () => {
  it('源码不再含串行单题入卷循环（for (const question of fresh) / await unwrap(addQuestion(）', () => {
    const source = readFileSync(PAGE_SOURCE, 'utf-8');
    expect(source).not.toContain('for (const question of fresh)');
    expect(source).not.toMatch(/unwrap\(\s*addQuestion\(/);
  });

  it('源码含批量入卷调用（unwrap(addQuestions(）', () => {
    const source = readFileSync(PAGE_SOURCE, 'utf-8');
    expect(source).toMatch(/unwrap\(\s*addQuestions\(/);
  });
});

describe('组卷页批量入卷行为', () => {
  it('批量入卷成功：单次批量调用 + 成功提示 + 失效刷新，单题端点零调用', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(api.addQuestions).mockResolvedValue({} as never);

    pick(wrapper, [201, 202]);
    await flushPromises();

    expect(api.addQuestions).toHaveBeenCalledTimes(1);
    expect(api.addQuestion).not.toHaveBeenCalled();
    expect(message.success).toHaveBeenCalledWith('已加入 2 题');
    expect(invalidateQueries).toHaveBeenCalled();
  });

  it('批量入卷失败：ApiError message 透出提示，不再半批静默', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(api.addQuestions).mockRejectedValueOnce(new Error('该题目已在试卷中'));

    pick(wrapper, [201, 202]);
    await flushPromises();

    expect(api.addQuestions).toHaveBeenCalledTimes(1);
    expect(message.error).toHaveBeenCalledWith('该题目已在试卷中');
    expect(message.success).not.toHaveBeenCalled();
    expect(invalidateQueries).not.toHaveBeenCalled();
  });

  it('所选题目均已在试卷中：前端前置提示保留，不发起任何请求', async () => {
    const wrapper = mountPage();
    await flushPromises();

    pick(wrapper, [101, 102]);
    await flushPromises();

    expect(message.info).toHaveBeenCalledWith('所选题目均已在试卷中');
    expect(api.addQuestions).not.toHaveBeenCalled();
    expect(api.addQuestion).not.toHaveBeenCalled();
  });
});
