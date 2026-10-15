/**
 * 试卷分值智能配平与标准题序重排（add-batch-tagging-and-score-balancing）前端用例。
 *
 * 呈现护栏：详情页（草稿态）提供「⚡ 分值智能配平」与「按标准题序重排」两个入口，
 * 且代码复用现有 updateOrder 端点做重排（不走新端点）。
 * 行为用例：配平弹窗双 Tab 预览与保和校验、提交正确入参并刷新消除不一致 Alert；
 * 重排按 单选(1)→多选(2)→判断(3)→填空(5)→简答(4) 计算目标顺序并一次性 PUT 持久化。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { message } from 'ant-design-vue';

import type { PaperDetailResponse } from '@/api/axios';

const PAGE_SOURCE = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '[id].page.vue');

const routeMock = vi.hoisted(() => ({ params: { id: '42' } }));
const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));

vi.mock('vue-router', () => ({ useRoute: () => routeMock }));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: vi.fn() },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'paper-rebalance-standard' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  addQuestions: vi.fn(),
  commitRandomDraw: vi.fn(),
  detail1: vi.fn(),
  list: vi.fn(),
  previewDraw: vi.fn(),
  rebalanceScores: vi.fn(),
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
import * as api from '@/api/axios';

/** 乱序题目表：简答(4)->填空(5)->单选(1)->多选(2)。 */
const QUESTIONS = [
  {
    questionId: 101,
    number: 1,
    score: 5,
    defaultScore: 5,
    questionType: 4,
    content: '简答题',
    questionDeleted: false,
  },
  {
    questionId: 102,
    number: 2,
    score: 5,
    defaultScore: 5,
    questionType: 5,
    content: '填空题',
    questionDeleted: false,
  },
  {
    questionId: 103,
    number: 3,
    score: 5,
    defaultScore: 5,
    questionType: 1,
    content: '单选题',
    questionDeleted: false,
  },
  {
    questionId: 104,
    number: 4,
    score: 5,
    defaultScore: 5,
    questionType: 2,
    content: '多选题',
    questionDeleted: false,
  },
];

function paper(status: number): PaperDetailResponse {
  return {
    id: 42,
    title: '配平重排试卷',
    description: '',
    totalScore: 20,
    questionCount: QUESTIONS.length,
    status,
    questions: QUESTIONS,
  };
}

function mountPage(detail: PaperDetailResponse) {
  queryStore.data = { paper: detail, tags: [] };
  return mount(PaperDetailPage);
}

async function clickBodyButton(label: string): Promise<void> {
  const buttons = Array.from(document.body.querySelectorAll('button'));
  const target = buttons.find((b) => b.textContent?.includes(label));
  expect(target).not.toBeNull();
  target!.click();
  await flushPromises();
}

/** 弹层 teleport 到 body：在 document.body 中定位 teleport 的确认弹层内容里的步长数值输入框并设值。 */
async function setBodyInput(dataTest: string, value: string | number): Promise<void> {
  let el = document.body.querySelector<HTMLInputElement>(`[data-test="${dataTest}"] input`);
  if (!el) {
    // antd InputNumber 可能未透传 data-test 到内层 input，回退按类定位弹层内第一个数值输入框
    el = document.body.querySelector<HTMLInputElement>('.ant-modal .ant-input-number-input');
  }
  expect(el).not.toBeNull();
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
  setter?.call(el, String(value));
  el!.dispatchEvent(new Event('input', { bubbles: true }));
  el!.dispatchEvent(new Event('change', { bubbles: true }));
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

describe('词法护栏：试卷详情页提供配平与标准重排入口', () => {
  it('源码含「分值智能配平」与「按标准题序重排」，并复用现有 updateOrder 端点', () => {
    const source = readFileSync(PAGE_SOURCE, 'utf-8');
    expect(source).toContain('⚡ 分值智能配平');
    expect(source).toContain('按标准题序重排');
    // 标准题型顺序：单选1→多选2→判断3→填空5→简答4
    expect(source).toMatch(/STANDARD_TYPE_ORDER\s*=\s*\[1,\s*2,\s*3,\s*5,\s*4\]/);
    // 重排走 updateOrder，而非新增专用端点
    expect(source).toContain('updateOrder');
  });
});

describe('试卷分值智能配平弹窗', () => {
  it('配平按钮限于草稿态（题数>0），打开后呈现一键均分/按题型配比双 Tab 与保和预览', async () => {
    const wrapper = mountPage(paper(0));
    await flushPromises();
    const button = wrapper.find('[data-test="rebalance-open"]');
    expect(button.exists()).toBe(true);

    await button.trigger('click');
    await flushPromises();
    // 双 Tab 与预览 teleport 到 body
    expect(document.body.textContent).toContain('一键均分');
    expect(document.body.textContent).toContain('按题型配比');
    expect(document.body.textContent).toContain('配平预览');
  });

  it('EVEN 提交：入参含 mode=EVEN/targetScore/step，成功后关闭并刷新', async () => {
    const wrapper = mountPage(paper(0));
    await flushPromises();
    vi.mocked(api.rebalanceScores).mockResolvedValue(paper(0) as never);

    await wrapper.find('[data-test="rebalance-open"]').trigger('click');
    await flushPromises();
    await setBodyInput('rebalance-target', 20);
    await clickBodyButton('一键应用');

    expect(api.rebalanceScores).toHaveBeenCalledTimes(1);
    const call = vi.mocked(api.rebalanceScores).mock.calls[0][0] as {
      body: Record<string, unknown>;
    };
    expect(call.body.mode).toBe('EVEN');
    expect(call.body.targetScore).toBe(20);
    expect(call.body.step).toBe(0.5);
    expect(message.success).toHaveBeenCalled();
  });

  it('BY_TYPE 提交：含 fixedScores 且 mode=BY_TYPE', async () => {
    const wrapper = mountPage(paper(0));
    await flushPromises();
    vi.mocked(api.rebalanceScores).mockResolvedValue(paper(0) as never);

    await wrapper.find('[data-test="rebalance-open"]').trigger('click');
    await flushPromises();
    // 切到「按题型配比」Tab（teleport 到 body）
    const byTypeTab = Array.from(document.body.querySelectorAll('div[role="tab"]')).find((t) =>
      t.textContent?.includes('按题型配比')
    );
    expect(byTypeTab).not.toBeNull();
    byTypeTab!.dispatchEvent(new Event('click', { bubbles: true }));
    await flushPromises();
    // 为单选(1)题型设置固定 2 分：定位「单选」标签所在行的数值输入框
    const fixedRows = Array.from(
      document.body.querySelectorAll('[data-test="rebalance-bytype-fixed"] .flex')
    );
    const singleRow = fixedRows.find((row) => row.textContent?.includes('单选'));
    expect(singleRow).not.toBeNull();
    const fixedInput = singleRow!.querySelector<HTMLInputElement>('input');
    expect(fixedInput).not.toBeNull();
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
    setter?.call(fixedInput, '2');
    fixedInput!.dispatchEvent(new Event('input', { bubbles: true }));
    fixedInput!.dispatchEvent(new Event('change', { bubbles: true }));
    await flushPromises();

    await clickBodyButton('一键应用');

    const call = vi.mocked(api.rebalanceScores).mock.calls[0][0] as {
      body: Record<string, unknown>;
    };
    expect(call.body.mode).toBe('BY_TYPE');
    expect(call.body.fixedScores).toEqual({ '1': 2 });
  });

  it('目标非步长整数倍给出保和错误提示，阻止应用', async () => {
    const wrapper = mountPage(paper(0));
    await flushPromises();
    await wrapper.find('[data-test="rebalance-open"]').trigger('click');
    await flushPromises();
    await setBodyInput('rebalance-target', 10.3);
    // 预览与错误提示也 teleport 到 body
    expect(document.body.querySelector('[data-test="rebalance-sum-ok"]')).toBeNull();
    expect(document.body.querySelector('[data-test="rebalance-error"]')).not.toBeNull();

    await clickBodyButton('一键应用');
    expect(api.rebalanceScores).not.toHaveBeenCalled();
  });
});

describe('试卷按标准题型一键重排', () => {
  it('重排按钮计算 单选→多选→填空→简答 顺序并一次性 PUT updateOrder 持久化', async () => {
    const wrapper = mountPage(paper(0));
    await flushPromises();

    await wrapper.find('[data-test="reorder-standard"]').trigger('click');
    await flushPromises();
    await clickBodyButton('确认重排');

    expect(api.updateOrder).toHaveBeenCalledTimes(1);
    const call = vi.mocked(api.updateOrder).mock.calls[0][0] as {
      path: { id: number };
      body: { questionIds: number[] };
    };
    expect(call.path.id).toBe(42);
    // 标准题序：单选(103) → 多选(104) → 填空(102) → 简答(101)
    expect(call.body.questionIds).toEqual([103, 104, 102, 101]);
    expect(message.success).toHaveBeenCalled();
  });

  it('锁定态不呈现配平与重排入口', async () => {
    const wrapper = mountPage(paper(1));
    await flushPromises();
    expect(wrapper.find('[data-test="rebalance-open"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="reorder-standard"]').exists()).toBe(false);
  });
});
