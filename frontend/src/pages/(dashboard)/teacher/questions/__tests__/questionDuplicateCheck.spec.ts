/**
 * 题目查重预检前端行为用例（add-question-duplicate-detection）。
 *
 * 覆盖 spec-delta frontend「录题与编辑题干相似度预检与防呆提示」与
 * question-bank「题干文本查重预检」的前端入口：
 *   - QuestionForm 题干≥4 字防抖 500ms 触发 checkDuplicate；命中渲染 warning 提示
 *     与可展开相似题目预览（题干摘要/题型/分值/相似度 Tag）；
 *   - 编辑场景把自身 id 作为 excludeId 传给后端；
 *   - 保存不被阻断（查重纯知情防呆）；
 *   - questions 列表工具栏「题目查重」快捷入口：粘贴题干→查重→命中/未命中反馈。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';

import type { QuestionDuplicateItemResponse, QuestionResponse } from '@/api/axios';

// ==================== 模块 mock ====================
const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));
const invalidateQueries = vi.hoisted(() => vi.fn());
const checkDuplicate = vi.hoisted(() => vi.fn());

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'question-duplicate-check' },
  apiClient: { get: vi.fn() },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  batchDelete: vi.fn(),
  create1: vi.fn(),
  checkDuplicate: checkDuplicate,
  delete_: vi.fn(),
  importQuestions: vi.fn(),
  list: vi.fn().mockResolvedValue([]),
  page: vi.fn(),
  update: vi.fn(),
  upload: vi.fn(),
}));

vi.mock('@/utils/exportDownload', () => ({
  downloadExport: vi.fn(),
  browserSaveFile: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref: vueRef, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown }) => {
      const key = unref(options.queryKey) as unknown[];
      return {
        data: vueRef(queryStore.data[String(key[0])]),
        isFetching: vueRef(false),
      };
    },
  };
});

import QuestionForm from '@/components/question/QuestionForm.vue';
import QuestionsPage from '@/pages/(dashboard)/teacher/questions/index.page.vue';

type FormVm = {
  draft: { content: string; type: number };
  runDuplicateCheck: (stem: string) => Promise<void>;
  duplicateHits: QuestionDuplicateItemResponse[];
  duplicateShowPreview: boolean;
  duplicateLoading: boolean;
};

type PageVm = {
  duplicateStem: string;
  duplicateQuickHits: QuestionDuplicateItemResponse[];
  duplicateChecked: boolean;
  onCheckDuplicateSubmit: () => Promise<void>;
};

function duplicateItem(
  questionId: number,
  content: string,
  similarity: number
): QuestionDuplicateItemResponse {
  return { questionId, type: 1, content, score: 5, similarity, createdBy: 1 };
}

function mountForm(question: QuestionResponse | null = null) {
  return mount(QuestionForm, {
    props: { open: true, question },
  });
}

async function pasteStem(wrapper: ReturnType<typeof mountForm>, stem: string) {
  const vm = wrapper.vm as unknown as FormVm;
  vm.draft.content = stem;
  await nextTick();
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('QuestionForm 题干查重预检（知情防呆）', () => {
  it('题干≥4 字防抖 500ms 后触发 checkDuplicate，命中后在题干下方渲染 warning 与可展开预览', async () => {
    vi.useFakeTimers();
    const wrapper = mountForm();
    await pasteStem(wrapper, '下列关于TCP三次握手说法正确的是');

    vi.mocked(checkDuplicate).mockResolvedValue([
      duplicateItem(101, '下列关于TCP三次握手说法错误的是', 0.877),
    ] as never);

    // 未到 500ms 不请求
    await vi.advanceTimersByTimeAsync(400);
    await flushPromises();
    expect(checkDuplicate).not.toHaveBeenCalled();

    // 停顿满 500ms 触发
    await vi.advanceTimersByTimeAsync(100);
    await flushPromises();

    expect(checkDuplicate).toHaveBeenCalledTimes(1);
    expect(checkDuplicate).toHaveBeenCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({ stem: '下列关于TCP三次握手说法正确的是' }),
      })
    );

    const vm = wrapper.vm as unknown as FormVm;
    expect(vm.duplicateHits).toHaveLength(1);

    // 弹层 teleport 到 body：warning 提示与可展开预览
    expect(document.body.querySelector('[data-test="duplicate-warning"]')).not.toBeNull();
    expect(document.body.textContent).toContain('检测到题库中存在 1 道相似题目（最高相似度 88%）');

    // 展开预览：点「查看相似题目」→ 相似题目题干摘要 + 相似度 Tag
    const toggleBtn = document.body.querySelector(
      '[data-test="duplicate-toggle-preview"]'
    ) as HTMLElement | null;
    expect(toggleBtn).not.toBeNull();
    toggleBtn!.click();
    await nextTick();
    expect(document.body.querySelector('[data-test="duplicate-preview-item"]')).not.toBeNull();

    vi.useRealTimers();
  });

  it('编辑场景把题目自身 id 作为 excludeId 传给后端', async () => {
    vi.useFakeTimers();
    const wrapper = mountForm({
      id: 7,
      type: 1,
      content: '旧题干',
      score: 5,
      difficulty: 1,
      tags: [],
    });
    await pasteStem(wrapper, '更新后的题干文本内容');

    vi.mocked(checkDuplicate).mockResolvedValue([] as never);
    await vi.advanceTimersByTimeAsync(500);
    await flushPromises();

    expect(checkDuplicate).toHaveBeenCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({ excludeId: 7, stem: '更新后的题干文本内容' }),
      })
    );
    vi.useRealTimers();
  });

  it('题干不足 4 字不触发查重（防抖门槛）', async () => {
    vi.useFakeTimers();
    const wrapper = mountForm();
    await pasteStem(wrapper, '短');
    await vi.advanceTimersByTimeAsync(600);
    await flushPromises();
    expect(checkDuplicate).not.toHaveBeenCalled();
    const vm = wrapper.vm as unknown as FormVm;
    expect(vm.duplicateHits).toHaveLength(0);
    vi.useRealTimers();
  });

  it('查重失败静默清空，不阻断保存流程、不渲染告警', async () => {
    vi.useFakeTimers();
    const wrapper = mountForm();
    await pasteStem(wrapper, '这是一道足够长的题干内容');
    vi.mocked(checkDuplicate).mockRejectedValueOnce(new Error('系统繁忙'));
    await vi.advanceTimersByTimeAsync(500);
    await flushPromises();
    const vm = wrapper.vm as unknown as FormVm;
    expect(vm.duplicateHits).toHaveLength(0);
    expect(document.body.querySelector('[data-test="duplicate-warning"]')).toBeNull();
    vi.useRealTimers();
  });
});

describe('题库列表「题目查重」快捷入口', () => {
  function mountPage() {
    queryStore.data = {
      questions: { list: [], total: 0 },
      tags: [],
    };
    return mount(QuestionsPage);
  }

  it('粘贴题干点击查重：命中相似题目并渲染提示', async () => {
    const wrapper = mountPage();
    await flushPromises();

    const openBtn = wrapper
      .findAll('button')
      .find((b) => b.text().replace(/\s/g, '').includes('题目查重'));
    expect(openBtn).toBeTruthy();
    await openBtn!.trigger('click');
    await nextTick();

    const vm = wrapper.vm as unknown as PageVm;
    vm.duplicateStem = '一道高度相似的题干文字内容';
    vi.mocked(checkDuplicate).mockResolvedValue([
      duplicateItem(200, '一道高度相似的题干文字内容', 0.92),
    ] as never);

    await vm.onCheckDuplicateSubmit();
    await flushPromises();

    expect(checkDuplicate).toHaveBeenCalledTimes(1);
    expect(checkDuplicate).toHaveBeenCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({ stem: '一道高度相似的题干文字内容' }),
      })
    );
    expect(vm.duplicateQuickHits).toHaveLength(1);
    expect(document.body.querySelector('[data-test="quick-duplicate-warning"]')).not.toBeNull();
    expect(document.body.querySelector('[data-test="quick-duplicate-item"]')).not.toBeNull();
  });

  it('未命中显示空态「未检测到相似题目」', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const openBtn = wrapper
      .findAll('button')
      .find((b) => b.text().replace(/\s/g, '').includes('题目查重'));
    await openBtn!.trigger('click');
    await nextTick();

    const vm = wrapper.vm as unknown as PageVm;
    vm.duplicateStem = '题库中没有的题干内容';
    vi.mocked(checkDuplicate).mockResolvedValue([] as never);

    await vm.onCheckDuplicateSubmit();
    await flushPromises();

    expect(vm.duplicateChecked).toBe(true);
    expect(document.body.querySelector('[data-test="quick-duplicate-empty"]')).not.toBeNull();
    expect(document.body.querySelector('[data-test="quick-duplicate-warning"]')).toBeNull();
  });
});
