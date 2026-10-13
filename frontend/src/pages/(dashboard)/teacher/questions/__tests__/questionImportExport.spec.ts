/**
 * 题库批量导入/导出/下载模板（add-question-import-export）前端行为用例。
 *
 * 覆盖 spec-delta frontend「批量导入导出入口与结果呈现」：
 *   - 导入：选择 xlsx → 调 SDK importQuestions（multipart/form-data，body 带 file）→
 *     弹层展示成功数/失败数 + 行号+原因表（与后端响应逐字段一致）→ 成功后刷新列表；
 *   - 非 xlsx 文件被拒、导入失败透出错误、导入不成功不刷新；
 *   - 导出 / 下载模板：走 downloadExport（GET + blob，复用成绩导出同一工具）。
 *
 * 断言有效性纪律（decorativeAssertionGuard 全绿）：无 exists() 守卫包裹、无 || 双通兜底。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { Modal, message } from 'ant-design-vue';

import type { QuestionResponse } from '@/api/axios';

const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));
const invalidateQueries = vi.hoisted(() => vi.fn());
const importQuestions = vi.hoisted(() => vi.fn());
const downloadExport = vi.hoisted(() => vi.fn());

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'question-import-export' },
  apiClient: { get: vi.fn() },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  batchDelete: vi.fn(),
  create1: vi.fn(),
  delete_: vi.fn(),
  importQuestions: importQuestions,
  list: vi.fn(),
  page: vi.fn(),
  update: vi.fn(),
}));

vi.mock('@/utils/exportDownload', () => ({
  downloadExport,
  browserSaveFile: vi.fn(),
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

/** 列表中一道题（101 单选）。 */
function question(id: number, content: string): QuestionResponse {
  return { id, type: 1, content, score: 5, difficulty: 1, tags: [] };
}

function mountPage() {
  queryStore.data = {
    questions: { list: [question(101, '题目甲')], total: 1 },
    tags: [],
  };
  return mount(QuestionsPage);
}

async function chooseFile(wrapper: ReturnType<typeof mountPage>, name: string, content: string) {
  const input = wrapper.find('input[type="file"]');
  const file = new File([content], name);
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
  await input.trigger('change');
}

/** 按按钮文字定位工具栏按钮并点击（antd 两字中文按钮会插入空格，故剥离空白匹配）。 */
async function clickToolbarButton(wrapper: ReturnType<typeof mountPage>, label: string) {
  const btn = wrapper.findAll('button').find((b) => b.text().replace(/\s/g, '').includes(label));
  expect(btn).toBeTruthy();
  await btn!.trigger('click');
}

/** antd Modal 经 teleport 挂到 body，断言弹层内容读 body.innerHTML。 */
function modalBodyHtml(): string {
  return document.body.innerHTML;
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('题库批量导入', () => {
  it('导入：选择 xlsx → SDK 收 file → 弹层成功/失败数 + 行号原因表 → 刷新列表', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(importQuestions).mockResolvedValue({
      imported: 3,
      failed: 2,
      errors: [
        { row: 3, reason: '题型必须为：单选/多选/判断/简答/填空' },
        { row: 4, reason: '图片URL 必须以 /images/ 开头' },
      ],
    } as never);

    await chooseFile(wrapper, '题库.xlsx', 'dummy-xlsx');

    expect(importQuestions).toHaveBeenCalledTimes(1);
    expect(importQuestions).toHaveBeenCalledWith(
      expect.objectContaining({ body: expect.objectContaining({ file: expect.any(File) }) })
    );

    await flushPromises();
    await nextTick();

    const modal = wrapper.findComponent(Modal);
    expect(modal.exists()).toBe(true);
    const body = modalBodyHtml();
    // 弹层内容：成功 3 题、失败 2 题 + 两行错误（行号+原因）与后端响应逐字段一致
    expect(body).toContain('成功');
    expect(body).toContain('3');
    expect(body).toContain('2');
    expect(body).toContain('第 3 行');
    expect(body).toContain('题型必须为：单选/多选/判断/简答/填空');
    expect(body).toContain('第 4 行');
    expect(body).toContain('图片URL 必须以 /images/ 开头');
    // 有成功行 → 刷新列表
    expect(invalidateQueries).toHaveBeenCalled();
  });

  it('非 xlsx 文件被拒绝，不调导入 SDK、不刷新', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await chooseFile(wrapper, '题库.csv', 'a,b,c');

    expect(importQuestions).not.toHaveBeenCalled();
    expect(message.error).toHaveBeenCalledWith('仅支持 .xlsx 格式的题库文件');
    expect(invalidateQueries).not.toHaveBeenCalled();
  });

  it('导入失败透出错误，不弹成功层、不刷新', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(importQuestions).mockRejectedValueOnce(new Error('数据行数超过上限'));

    await chooseFile(wrapper, '题库.xlsx', 'dummy');

    await flushPromises();
    expect(importQuestions).toHaveBeenCalledTimes(1);
    expect(message.error).toHaveBeenCalledWith('数据行数超过上限');
    // 失败不打开结果弹层（Modal 组件常驻但 open=false）
    expect(wrapper.findComponent(Modal).props('open')).toBe(false);
    expect(invalidateQueries).not.toHaveBeenCalled();
  });

  it('全部成功：弹层提示全部导入成功（无错误表）', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(importQuestions).mockResolvedValue({
      imported: 2,
      failed: 0,
      errors: [],
    } as never);

    await chooseFile(wrapper, '题库.xlsx', 'dummy');
    await flushPromises();
    await nextTick();

    const modal = wrapper.findComponent(Modal);
    expect(modal.exists()).toBe(true);
    expect(modalBodyHtml()).toContain('全部行导入成功');
    expect(invalidateQueries).toHaveBeenCalled();
  });
});

describe('题库导出与下载模板', () => {
  it('导出：downloadExport 触发 /api/questions/export 并提示已下载', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(downloadExport).mockResolvedValue('题库导出.xlsx');

    await clickToolbarButton(wrapper, '导出');
    await flushPromises();

    expect(downloadExport).toHaveBeenCalledTimes(1);
    expect(downloadExport).toHaveBeenCalledWith(
      expect.objectContaining({ http: expect.anything() }),
      '/api/questions/export',
      '题库导出.xlsx'
    );
    expect(message.success).toHaveBeenCalledWith('已导出：题库导出.xlsx');
  });

  it('下载模板：downloadExport 触发 /api/questions/import-template 并提示已下载', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(downloadExport).mockResolvedValue('题库导入模板.xlsx');

    await clickToolbarButton(wrapper, '下载模板');
    await flushPromises();

    expect(downloadExport).toHaveBeenCalledTimes(1);
    expect(downloadExport).toHaveBeenCalledWith(
      expect.objectContaining({ http: expect.anything() }),
      '/api/questions/import-template',
      '题库导入模板.xlsx'
    );
    expect(message.success).toHaveBeenCalledWith('已下载：题库导入模板.xlsx');
  });

  it('导出失败：提示原因、不误报成功', async () => {
    const wrapper = mountPage();
    await flushPromises();
    vi.mocked(downloadExport).mockRejectedValueOnce(new Error('系统繁忙'));

    await clickToolbarButton(wrapper, '导出');
    await flushPromises();

    expect(message.error).toHaveBeenCalledWith('系统繁忙');
    expect(message.success).not.toHaveBeenCalled();
  });
});
