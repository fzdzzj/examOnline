/**
 * 题库批量打标/解绑（add-batch-tagging-and-score-balancing）前端护栏与行为用例。
 *
 * 护栏：题库页须显性提供「批量标签(N)」按钮（带选中数徽标），并走单次 batchTags
 * 调用（禁用逐题循环打标）与逐题结果信封解析；变异还原（把 batchTags 改回逐题
 * for-of edit/remove 循环、或删掉该按钮）应红。
 * 行为用例：模态框 BIND/UNBIND 模式切换、按分类组织标签多选、提交入参正确、
 * 成功提示 + 刷新、部分失败泄露逐条 reason、打开即复位选中标签。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { message, Table } from 'ant-design-vue';

import type { QuestionResponse } from '@/api/axios';

const PAGE_SOURCE = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'index.page.vue');

const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));
const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'question-batch-tags' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  batchDelete: vi.fn(),
  batchTags: vi.fn(),
  checkDuplicate: vi.fn(),
  delete_: vi.fn(),
  importQuestions: vi.fn(),
  list: vi.fn(),
  page: vi.fn(),
  update: vi.fn(),
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

import QuestionsPage from '@/pages/(dashboard)/teacher/questions/index.page.vue';
import BatchTagModal from '@/components/question/BatchTagModal.vue';
import * as api from '@/api/axios';

/** 列表中两道可选题目。 */
function question(id: number, content: string): QuestionResponse {
  return { id, type: 1, content, score: 5, difficulty: 1, tags: [] };
}

/** 三类标签各一：学科/难度/自定义（验证按分类组织）。
 *  标签用 id 与 type 区分，id 与题目 id 不同以免混淆。 */
const TAGS = [
  { id: 201, name: '学科-数学', type: 'SUBJECT' },
  { id: 202, name: '学科-物理', type: 'SUBJECT' },
  { id: 301, name: '难度-简单', type: 'DIFFICULTY' },
  { id: 401, name: '自定义-复习', type: 'CUSTOM' },
];

function mountPage() {
  queryStore.data = {
    questions: { list: [question(101, '题目甲'), question(102, '题目乙')], total: 2 },
    tags: TAGS,
  };
  return mount(QuestionsPage);
}

/** 通过 Table 组件的 rowSelection 勾选指定行。 */
function selectRows(wrapper: ReturnType<typeof mountPage>, ids: number[]) {
  const selection = wrapper.findComponent(Table).props('rowSelection') as {
    onChange: (keys: number[]) => void;
  };
  selection.onChange(ids);
}

/** 弹层 teleport 到 body：在 document.body 中点击模态框确认按钮（按文案匹配）。 */
async function clickModalOk(label: string): Promise<void> {
  const buttons = Array.from(document.body.querySelectorAll('button'));
  const ok = buttons.find((b) => b.textContent?.includes(label));
  expect(ok).not.toBeNull();
  ok!.click();
  await flushPromises();
}

/** antd Modal 弹层 motion 在 jsdom 依赖 requestAnimationFrame，等待一帧以完成渲染。 */
function frame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => resolve(undefined)));
}

/** 在 document.body 中勾选第一个标签 checkbox：设置受控 checked 并触发 change（匹配 antd 读取语义）。 */
async function checkFirstTagCheckbox(): Promise<void> {
  // antd Modal 弹层 teleport 渲染在 jsdom 有轻微时序抖动，轮询等待弹层与 checkbox 都出现
  let raw: HTMLInputElement | null = null;
  for (let attempt = 0; attempt < 40 && !raw; attempt++) {
    await frame();
    await flushPromises();
    raw = document.body.querySelector<HTMLInputElement>(
      '[data-test="batch-tag-group-subject"] input[type="checkbox"]'
    );
  }
  expect(raw).not.toBeNull();
  raw!.checked = true;
  raw!.dispatchEvent(new Event('change', { bubbles: true }));
  await frame();
  await flushPromises();
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('词法护栏：题库页批量打标批量化', () => {
  it('源码含「批量标签」按钮且不再含逐题 for-of 打标循环', () => {
    const source = readFileSync(PAGE_SOURCE, 'utf-8');
    expect(source).toContain('批量标签');
    expect(source).not.toContain('for (const id of');
  });

  it('源码走单次 batchTags 调用与逐题结果信封解析（succeeded/failed）', () => {
    const source = readFileSync(PAGE_SOURCE, 'utf-8');
    expect(source).toMatch(/unwrap\(\s*batchTags\(/);
    expect(source).toContain('succeeded');
    expect(source).toContain('failed');
  });
});

describe('题库页批量标签行为', () => {
  it('未选中时不打开，选中后按钮与模态框开合受选中态驱动', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const button = wrapper.find('[data-test="batch-tags-button"]');
    expect(button.attributes('disabled')).toBeDefined();
    expect(wrapper.text()).toContain('批量标签（0）');
  });

  it('勾选 2 题 → 批量标签按钮带徽标 → 打开模态框（BIND 默认）：提交追加入参并成功刷新', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(api.batchTags).mockResolvedValue({
      succeeded: [101, 102],
      failed: [],
    } as never);

    selectRows(wrapper, [101, 102]);
    await flushPromises();
    expect(wrapper.text()).toContain('批量标签（2）');

    await wrapper.find('[data-test="batch-tags-button"]').trigger('click');
    await flushPromises();
    // 弹层 teleport 到 body：BIND 默认模式 + 按分类组织的标签组
    let groupFound = document.body.querySelector('[data-test="batch-tag-group-subject"]');
    for (let attempt = 0; attempt < 25 && !groupFound; attempt++) {
      await frame();
      await flushPromises();
      groupFound = document.body.querySelector('[data-test="batch-tag-group-subject"]');
    }
    expect(document.body.textContent).toContain('追加标签（BIND）');
    expect(groupFound).not.toBeNull();

    // 勾选一个标签后点确认（确认按钮文案「追加标签」）
    await checkFirstTagCheckbox();
    await clickModalOk('追加标签');

    expect(api.batchTags).toHaveBeenCalledTimes(1);
    expect(api.batchTags).toHaveBeenCalledWith(
      expect.objectContaining({
        body: { questionIds: [101, 102], tagIds: [201], action: 'BIND' },
      })
    );
    expect(message.success).toHaveBeenCalledWith('已为 2 题打上所选标签');
    expect(invalidateQueries).toHaveBeenCalled();
  });

  it('UNBIND 移除模式：提交 action=UNBIND 并提示移除成功', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(api.batchTags).mockResolvedValue({
      succeeded: [101, 102],
      failed: [],
    } as never);

    selectRows(wrapper, [101, 102]);
    await flushPromises();
    // 通过弹窗的 submit 事件驱动页面 onBatchTags（action=UNBIND）
    const modal = wrapper.findComponent(BatchTagModal);
    modal.vm.$emit('submit', { questionIds: [101, 102], tagIds: [201], action: 'UNBIND' });
    await flushPromises();

    expect(api.batchTags).toHaveBeenCalledWith(
      expect.objectContaining({
        body: { action: 'UNBIND', tagIds: [201], questionIds: [101, 102] },
      })
    );
    expect(message.success).toHaveBeenCalledWith('已从 2 题移除所选标签');
  });

  it('部分失败：信封失败项逐条泄露 reason，不伪装成全部成功', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(api.batchTags).mockResolvedValue({
      succeeded: [101],
      failed: [{ id: 102, reason: '无权操作该题目（资源不属于当前用户）' }],
    } as never);

    selectRows(wrapper, [101, 102]);
    await flushPromises();
    const modal = wrapper.findComponent(BatchTagModal);
    modal.vm.$emit('submit', { questionIds: [101, 102], tagIds: [201], action: 'BIND' });
    await flushPromises();

    expect(message.warning).toHaveBeenCalledWith('批量标签完成：成功 1 题，失败 1 题');
    expect(message.error).toHaveBeenCalledWith(
      expect.stringContaining('题目 102：无权操作该题目（资源不属于当前用户）')
    );
  });

  it('请求拦截（400/越权）错误透出，不刷新列表', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(api.batchTags).mockRejectedValueOnce(new Error('部分标签不存在或已删除'));

    selectRows(wrapper, [101]);
    await flushPromises();
    const modal = wrapper.findComponent(BatchTagModal);
    modal.vm.$emit('submit', { questionIds: [101], tagIds: [201], action: 'BIND' });
    await flushPromises();

    expect(api.batchTags).toHaveBeenCalledTimes(1);
    expect(message.error).toHaveBeenCalledWith('部分标签不存在或已删除');
    expect(invalidateQueries).not.toHaveBeenCalled();
  });
});
