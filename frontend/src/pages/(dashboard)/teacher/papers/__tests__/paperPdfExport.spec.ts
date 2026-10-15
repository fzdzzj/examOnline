/**
 * 试卷 PDF 导出交互（add-paper-pdf-export，spec-delta frontend）：
 * 详情页操作栏与列表页操作列提供「导出试卷 PDF」入口，点击后提供
 * 「学生作答卷 / 含答案解析卷」两个选项，确认后走 downloadExport 流式下载，
 * 在途呈现 loading，失败给出原因提示。
 *
 * 断言纪律：dropdown 覆盖层由 ant-design-vue teleport 到 body，
 * 菜单项以 data-test 经 document.body.querySelector 定位；downloadExport 为
 * 唯一网络边界 mock（页面只传 URL，真实 blob 下载由既有工具承担，此处不重复测）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { message } from 'ant-design-vue';

import type { PaperDetailResponse } from '@/api/axios';

const routeMock = vi.hoisted(() => ({ params: { id: '42' } }));
const routerMock = vi.hoisted(() => ({ push: vi.fn() }));
const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));
const downloadExport = vi.hoisted(() => vi.fn());

vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
  useRouter: () => routerMock,
}));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: vi.fn() },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'paper-pdf-export' },
  apiClient: { get: vi.fn() },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  addQuestions: vi.fn(),
  clone: vi.fn(),
  commitRandomDraw: vi.fn(),
  delete1: vi.fn(),
  detail1: vi.fn(),
  list: vi.fn(),
  page1: vi.fn(),
  previewDraw: vi.fn(),
  removeQuestion: vi.fn(),
  saveAsTemplate: vi.fn(),
  update1: vi.fn(),
  updateOrder: vi.fn(),
  updateQuestionScore: vi.fn(),
}));

vi.mock('@/utils/exportDownload', () => ({
  downloadExport: downloadExport,
  browserSaveFile: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown }) => {
      const key = unref(options.queryKey) as unknown[];
      return {
        data: ref(queryStore.data[String(key[0])]),
        error: ref(null),
        isFetching: ref(false),
        refetch: vi.fn(),
      };
    },
  };
});

import PaperDetailPage from '@/pages/(dashboard)/teacher/papers/[id].page.vue';
import PapersIndexPage from '@/pages/(dashboard)/teacher/papers/index.page.vue';

const QUESTIONS = [
  {
    questionId: 101,
    number: 1,
    score: 5,
    defaultScore: 5,
    questionType: 1,
    content: '函数 f(x) 的最小值是多少？',
    questionDeleted: false,
  },
];

function paperDetail(status: number): PaperDetailResponse {
  return {
    id: 42,
    title: '高等数学期中试卷',
    description: '函数与导数',
    totalScore: 5,
    questionCount: QUESTIONS.length,
    status,
    questions: QUESTIONS,
  };
}

function mountDetail(detail: PaperDetailResponse) {
  queryStore.data = { paper: detail, tags: [] };
  return mount(PaperDetailPage);
}

function mountIndex() {
  queryStore.data = { papers: [{ id: 7, title: '列表试卷', totalScore: 100 }] };
  return mount(PapersIndexPage);
}

/** 打开详情页导出下拉并点击某个选项（dropdown 覆盖层 teleport 到 body）。 */
async function clickExportOption(wrapper: ReturnType<typeof mountDetail>, testId: string) {
  const trigger = wrapper.find('[data-test="paper-export"]');
  expect(trigger.exists()).toBe(true);
  await trigger.trigger('click');
  await nextTick();
  await flushPromises();
  const item = document.body.querySelector(`[data-test="${testId}"]`);
  expect(item, `菜单项 ${testId} 应出现`).not.toBeNull();
  (item as HTMLElement).click();
  await flushPromises();
}

beforeEach(() => {
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
  downloadExport.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('试卷详情页：导出试卷 PDF 交互', () => {
  it('草稿态点击「学生作答卷」触发下载（withAnswer=false）并提示成功', async () => {
    downloadExport.mockResolvedValue('学生作答-高等数学期中试卷.pdf');
    const wrapper = mountDetail(paperDetail(0));
    await flushPromises();

    await clickExportOption(wrapper, 'paper-export-student');

    expect(downloadExport).toHaveBeenCalledTimes(1);
    const args = vi.mocked(downloadExport).mock.calls[0] as unknown[];
    expect(args[1] as string).toContain('/api/papers/42/export-pdf?withAnswer=false');
    expect(message.success).toHaveBeenCalledWith('已下载：学生作答-高等数学期中试卷.pdf');
  });

  it('点击「含答案解析卷」触发 withAnswer=true 下载', async () => {
    downloadExport.mockResolvedValue('含答案解析-高等数学期中试卷.pdf');
    const wrapper = mountDetail(paperDetail(0));
    await flushPromises();

    await clickExportOption(wrapper, 'paper-export-answer');

    expect(downloadExport).toHaveBeenCalledTimes(1);
    const args = vi.mocked(downloadExport).mock.calls[0] as unknown[];
    expect(args[1] as string).toContain('/api/papers/42/export-pdf?withAnswer=true');
    expect(message.success).toHaveBeenCalledWith('已下载：含答案解析-高等数学期中试卷.pdf');
  });

  it('下载失败透出原因且不误导成功', async () => {
    downloadExport.mockRejectedValue(new Error('403 Forbidden'));
    const wrapper = mountDetail(paperDetail(0));
    await flushPromises();

    await clickExportOption(wrapper, 'paper-export-student');

    expect(message.error).toHaveBeenCalledWith('403 Forbidden');
    expect(message.success).not.toHaveBeenCalled();
  });

  it('锁定态仍提供导出入口（只读不锁导出）', async () => {
    const wrapper = mountDetail(paperDetail(1));
    await flushPromises();

    expect(wrapper.find('[data-test="paper-export"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('编辑信息');
  });

  it('下载在途时按钮呈现 loading', async () => {
    let resolveDownload: ((name: string) => void) | undefined;
    downloadExport.mockImplementation(
      () => new Promise<string>((resolve) => (resolveDownload = resolve))
    );
    const wrapper = mountDetail(paperDetail(0));
    await flushPromises();

    const trigger = wrapper.find('[data-test="paper-export"]');
    await trigger.trigger('click');
    await nextTick();
    await flushPromises();
    const item = document.body.querySelector('[data-test="paper-export-student"]') as HTMLElement;
    item.click();
    await flushPromises();

    expect(wrapper.find('[data-test="paper-export"]').classes()).toContain('ant-btn-loading');
    resolveDownload?.('文件.pdf');
    await flushPromises();
    expect(wrapper.find('[data-test="paper-export"]').classes()).not.toContain('ant-btn-loading');
  });
});

describe('试卷列表页：操作列导出 PDF 入口', () => {
  it('点击「导出 PDF」→ 选学生作答卷触发下载', async () => {
    downloadExport.mockResolvedValue('学生作答-列表试卷.pdf');
    const wrapper = mountIndex();
    await flushPromises();
    await flushPromises();

    const trigger = wrapper.find('[data-test="paper-export"]');
    expect(trigger.exists()).toBe(true);
    await trigger.trigger('click');
    await nextTick();
    await flushPromises();
    const item = document.body.querySelector('[data-test="paper-export-student"]') as HTMLElement;
    expect(item, '列表下拉菜单应提供学生作答卷').not.toBeNull();
    item.click();
    await flushPromises();

    expect(downloadExport).toHaveBeenCalledTimes(1);
    const args = vi.mocked(downloadExport).mock.calls[0] as unknown[];
    expect(args[1] as string).toContain('/api/papers/7/export-pdf?withAnswer=false');
    expect(message.success).toHaveBeenCalledWith('已下载：学生作答-列表试卷.pdf');
  });
});
