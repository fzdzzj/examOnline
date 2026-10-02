/**
 * 题库批量删除（add-question-batch-delete）前端护栏与行为用例。
 *
 * 词法护栏：题库页源码不得再出现串行 for-of 删除循环（N 次单题请求的根源——
 * 逐题串行慢且失败静默计数），必须走单次批量调用并解析逐题结果信封；变异还原
 * （改回 for (const id of ids) 逐题 await unwrap(deleteQuestion(...)) 循环）应红。
 * 行为用例：部分成功不伪装成全部成功（信封解析提示「成功 N 题，失败 M 题」+
 * 失败项逐条 reason）、全部成功路径、全部失败零成功提示、请求校验失败透出错误。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Popconfirm, Table, message } from 'ant-design-vue';

import type { QuestionResponse } from '@/api/axios';

// 文件名含 (dashboard) 圆括号，new URL() 语义无碍，但沿用 path 拼接与既有 spec 保持一致
const PAGE_SOURCE = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'index.page.vue');

const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));
const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'question-batch-delete' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  batchDelete: vi.fn(),
  create1: vi.fn(),
  delete_: vi.fn(),
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
import * as api from '@/api/axios';

/** 列表中两道可选题目（101 单选、102 判断） */
function question(id: number, content: string): QuestionResponse {
  return { id, type: 1, content, score: 5, difficulty: 1, tags: [] };
}

function mountPage() {
  queryStore.data = {
    questions: { list: [question(101, '题目甲'), question(102, '题目乙')], total: 2 },
    tags: [],
  };
  return mount(QuestionsPage);
}

/** 勾选表格两行（直接调用 Table rowSelection.onChange，等价 antd 全选交互）。 */
function selectRows(wrapper: ReturnType<typeof mountPage>, ids: number[]) {
  const selection = wrapper.findComponent(Table).props('rowSelection') as {
    onChange: (keys: number[]) => void;
  };
  selection.onChange(ids);
}

/** 触发工具栏「批量删除」Popconfirm 的确认（第一个 Popconfirm，DOM 顺序在表格之前）。 */
function confirmBatchDelete(wrapper: ReturnType<typeof mountPage>) {
  wrapper.findAllComponents(Popconfirm)[0].vm.$emit('confirm');
}

beforeEach(() => {
  // 模块 mock（vi.fn()）的调用记录不随 restoreAllMocks 重置，须逐用例清零
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

describe('词法护栏：题库页 onBatchDelete 批量化', () => {
  it('源码不再含串行 for-of 删除循环（for (const id of ids)）', () => {
    const source = readFileSync(PAGE_SOURCE, 'utf-8');
    expect(source).not.toContain('for (const id of');
  });

  it('源码含单次批量调用（unwrap(batchDelete(）与逐题结果信封解析（succeeded/failed）', () => {
    const source = readFileSync(PAGE_SOURCE, 'utf-8');
    expect(source).toMatch(/unwrap\(\s*batchDelete\(/);
    expect(source).toContain('succeeded');
    expect(source).toContain('failed');
  });
});

describe('题库页批量删除行为', () => {
  it('部分成功：单次批量调用 + 信封解析提示成功 N 失败 M + 失败项逐条 reason + 刷新选中态', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(api.batchDelete).mockResolvedValue({
      succeeded: [101],
      failed: [{ id: 102, reason: '考试进行中，试卷已锁定，不允许修改' }],
    } as never);

    selectRows(wrapper, [101, 102]);
    confirmBatchDelete(wrapper);
    await flushPromises();

    expect(api.batchDelete).toHaveBeenCalledTimes(1);
    expect(api.batchDelete).toHaveBeenCalledWith(
      expect.objectContaining({ body: { ids: [101, 102] } })
    );
    expect(api.delete_).not.toHaveBeenCalled();
    expect(message.warning).toHaveBeenCalledWith('删除完成：成功 1 题，失败 1 题');
    expect(message.error).toHaveBeenCalledWith(
      expect.stringContaining('题目 102：考试进行中，试卷已锁定，不允许修改')
    );
    expect(message.success).not.toHaveBeenCalled();
    // 成功后列表与选中态刷新
    expect(invalidateQueries).toHaveBeenCalled();
    const selection = wrapper.findComponent(Table).props('rowSelection') as {
      selectedRowKeys: number[];
    };
    expect(selection.selectedRowKeys).toEqual([]);
  });

  it('全部成功：批量调用 + 成功提示，单题端点零调用', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(api.batchDelete).mockResolvedValue({
      succeeded: [101, 102],
      failed: [],
    } as never);

    selectRows(wrapper, [101, 102]);
    confirmBatchDelete(wrapper);
    await flushPromises();

    expect(api.batchDelete).toHaveBeenCalledTimes(1);
    expect(api.delete_).not.toHaveBeenCalled();
    expect(message.success).toHaveBeenCalledWith('已删除 2 道题目');
    expect(message.warning).not.toHaveBeenCalled();
    expect(invalidateQueries).toHaveBeenCalled();
  });

  it('全部失败：零删除提示「成功 0 题，失败 2 题」，不伪装成全部成功', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(api.batchDelete).mockResolvedValue({
      succeeded: [],
      failed: [
        { id: 101, reason: '题目不存在' },
        { id: 102, reason: '无权操作该题目（资源不属于当前用户）' },
      ],
    } as never);

    selectRows(wrapper, [101, 102]);
    confirmBatchDelete(wrapper);
    await flushPromises();

    expect(api.batchDelete).toHaveBeenCalledTimes(1);
    expect(message.success).not.toHaveBeenCalled();
    expect(message.warning).toHaveBeenCalledWith('删除完成：成功 0 题，失败 2 题');
    expect(message.error).toHaveBeenCalledWith(expect.stringContaining('题目不存在'));
  });

  it('请求校验失败（重复/空/超限 400）：错误透出，不刷新列表', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(api.batchDelete).mockRejectedValueOnce(new Error('请求内存在重复题目'));

    selectRows(wrapper, [101, 101]);
    confirmBatchDelete(wrapper);
    await flushPromises();

    expect(api.batchDelete).toHaveBeenCalledTimes(1);
    expect(message.error).toHaveBeenCalledWith('请求内存在重复题目');
    expect(message.success).not.toHaveBeenCalled();
    expect(invalidateQueries).not.toHaveBeenCalled();
  });
});
