/**
 * 题目快速复制另存（polish-authoring-monitoring-and-grading-flow 特性 1）。
 *
 * 红线：复制提交必须走新建端点 create1（POST /api/questions），**不得**走 update——
 * 源题内容与更新时间零影响。复制模式以「复制为新题」打开、全字段深拷贝回填、
 * 题干前缀「（副本）」，且复制不视为编辑（isEdit 语义保持 `question?.id !== undefined`）。
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import type { QuestionResponse } from '@/api/axios';
import QuestionForm from '@/components/question/QuestionForm.vue';

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  create1: vi.fn().mockResolvedValue({}),
  list: vi.fn().mockResolvedValue([]),
  update: vi.fn().mockResolvedValue({}),
  checkDuplicate: vi.fn().mockResolvedValue([]),
  upload: vi.fn(),
}));

vi.mock('@tanstack/vue-query', () => ({
  useQuery: () => ({ data: ref([]) }),
}));

import { create1, update } from '@/api/axios';

type FormVm = {
  onSubmit: () => Promise<void>;
  draft: {
    content: string;
    score?: number;
    difficulty?: number;
    type: number;
    shortAnswer: string;
  };
};

/** 源题：单选（type=1），含题干/选项/正确答案/分值/难度/解析/标签，保证复制后字段齐全。 */
const SOURCE_QUESTION: QuestionResponse = {
  id: 77,
  type: 1,
  content: '以下哪种排序算法最坏时间复杂度为 O(n²)？',
  imageUrl: '/images/q.png',
  choices: ['冒泡排序', '归并排序', '堆排序', '快速排序(最优)'],
  correctAnswer: 'A',
  score: 10,
  difficulty: 2,
  analysis: '冒泡排序最坏为 O(n²)。',
  tags: [{ id: 3, name: '算法' }],
  updatedTime: '2026-09-01T10:00:00',
};

async function mountCopy() {
  return mount(QuestionForm, {
    props: { open: true, question: null, copyFrom: SOURCE_QUESTION },
  });
}

beforeEach(() => {
  vi.mocked(create1).mockClear();
  vi.mocked(update).mockClear();
});

describe('题目复制另存（QuestionForm copyFrom）', () => {
  it('复制模式以「复制为新题」标题打开，源题全字段深拷贝回填且题干前缀「（副本）」', async () => {
    const wrapper = await mountCopy();
    await flushPromises();

    const vm = wrapper.vm as unknown as FormVm;
    // Modal teleport 到 body：标题在 document.body 里断言
    expect(document.body.textContent ?? '').toContain('复制为新题');
    expect(vm.draft.content).toBe(`（副本）${SOURCE_QUESTION.content}`);
    expect(vm.draft.type).toBe(1);
    expect(vm.draft.score).toBe(10);
    expect(vm.draft.difficulty).toBe(2);
    // 选项与正确答案、解析、标签均回填
    expect((wrapper.vm as unknown as { draft: { choices: unknown[] } }).draft.choices).toEqual([
      { text: '冒泡排序', isCorrect: true },
      { text: '归并排序', isCorrect: false },
      { text: '堆排序', isCorrect: false },
      { text: '快速排序(最优)', isCorrect: false },
    ]);
    expect(
      (wrapper.vm as unknown as { draft: { analysis: string; tagIds: number[] } }).draft.analysis
    ).toBe('冒泡排序最坏为 O(n²)。');
    expect((wrapper.vm as unknown as { draft: { tagIds: number[] } }).draft.tagIds).toEqual([3]);
  });

  it('复制模式不是编辑：提交走 create1 且绝不调用 update（源题零影响）', async () => {
    const wrapper = await mountCopy();
    await flushPromises();

    const vm = wrapper.vm as unknown as FormVm;
    await vm.onSubmit();
    await flushPromises();

    expect(create1).toHaveBeenCalledTimes(1);
    expect(update).not.toHaveBeenCalled();
  });

  it('复制保存是「新建」，绝不触碰 update 端点（源题零影响）', async () => {
    const wrapper = await mountCopy();
    await flushPromises();
    const vm = wrapper.vm as unknown as FormVm;
    await vm.onSubmit();
    await flushPromises();

    expect(create1).toHaveBeenCalledTimes(1);
    expect(update).not.toHaveBeenCalled();
  });

  it('编辑模式（question prop）回归零破坏：仍走 update 不走 create', async () => {
    const wrapper = mount(QuestionForm, {
      props: { open: true, question: SOURCE_QUESTION, copyFrom: null },
    });
    await flushPromises();
    const vm = wrapper.vm as unknown as FormVm;
    await vm.onSubmit();
    await flushPromises();

    expect(update).toHaveBeenCalledTimes(1);
    expect(create1).not.toHaveBeenCalled();
  });
});
