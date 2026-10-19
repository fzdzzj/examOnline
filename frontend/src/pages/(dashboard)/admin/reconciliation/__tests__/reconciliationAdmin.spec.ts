/**
 * 成绩链对账页用例（管理端 /admin/reconciliation）。
 *
 * 断言口径：
 * - 运行历史与差异票按后端字段直渲染（triggerSource/status/counts/errorMessage、
 *   diffType/status/expectedValue/actualValue/deviation 不本地推算）；时间统一 'YYYY-MM-DD HH:mm'；
 * - 查询失败 → 错误 Alert 显性呈现后端 message 且「暂无数据」不出现（失败与空态三态分离）；
 *   成功且无数据 → 空态照常呈现；
 * - 响应是页内数组、信封无 total：分页按「满页即至少还有下一页」做下界推断，第 2 页必须可达
 *   且真实带 page:2,size:20 请求；
 * - 触发对账：examId 透传 body（空值不携带 examId），成功后 message.success 呈现三类计数并失效
 *   两个查询键；失败 message.error 呈现后端原文；
 * - 人工关闭：入口仅 status === 'OPEN' 行出现，弹窗明示「仅处置、不改分」，确认后按 path.id 调
 *   closeReconciliationDiff 并失效两个查询；失败 message.error 原文且不失效；
 * - 筛选：diffType/status 透传服务端、空值不携带该参数、300ms 防抖、变更回第 1 页；
 * - 导航接线词法护栏：/admin/reconciliation 在菜单项与 NAVIGABLE_PATHS 两处出现。
 *
 * 页面通过 mock 契约层（@/api/axios）与数据层（@tanstack/vue-query）挂载；useQuery mock
 * 沿用 auditLogsKick.spec 的响应式口径（queryKey 变化即重新调用 queryFn，refetch 真跑）。
 * 弹窗经 portal 渲染到 body，用 document.body 文本断言。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { Modal, Select, Table, message } from 'ant-design-vue';

import * as api from '@/api/axios';
import { ApiError } from '@/api/types';
import ReconciliationPage from '@/pages/(dashboard)/admin/reconciliation/index.page.vue';
import dashboardPageSource from '@/pages/(dashboard).page.vue?raw';

const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  runReconciliation: vi.fn(),
  reconciliationRuns: vi.fn(),
  reconciliationDiffs: vi.fn(),
  closeReconciliationDiff: vi.fn(),
}));

/** useQuery mock：queryKey 变化即真实重跑 queryFn（真链路口径） */
vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref, watch } = await import('vue');
  return {
    useQuery: (options: { queryKey?: unknown; queryFn?: () => unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      const isFetching = ref(false);

      const runQuery = async () => {
        if (!options.queryFn) return;
        isFetching.value = true;
        try {
          data.value = await options.queryFn();
        } catch (reason) {
          error.value = reason;
        } finally {
          isFetching.value = false;
        }
      };

      if (options.queryKey) {
        watch(
          () => unref(options.queryKey),
          () => {
            void runQuery();
          },
          { deep: true }
        );
      }

      void runQuery();
      return { data, error, isFetching, refetch: vi.fn(runQuery) };
    },
  };
});

/** 成功运行：携带三类计数 */
const SUCCESS_RUN = {
  id: 31,
  examId: 7,
  triggerSource: 'MANUAL',
  status: 'SUCCESS',
  scannedCount: 128,
  ungradedCount: 3,
  mismatchCount: 2,
  autoFixedCount: 1,
  errorMessage: '',
  startedTime: '2026-10-08T09:00:00',
  finishedTime: '2026-10-08T09:00:05',
};

/** 失败运行：后端原文错误信息，结束时间为空（渲染 —） */
const FAILED_RUN = {
  id: 30,
  examId: 12,
  triggerSource: 'SCHEDULED',
  status: 'FAILED',
  scannedCount: 40,
  ungradedCount: 0,
  mismatchCount: 0,
  autoFixedCount: 0,
  errorMessage: '成绩链读取超时',
  startedTime: '2026-10-07T22:00:00',
  finishedTime: undefined,
};

/** 未处置差异票：OPEN → 有「人工关闭」入口 */
const OPEN_DIFF = {
  id: 501,
  runId: 31,
  examId: 7,
  submissionId: 9001,
  questionId: 12,
  diffType: 'SUBMISSION_UNGRADED',
  detail: '答卷 9001 未判分',
  expectedValue: 86,
  actualValue: 0,
  deviation: 86,
  status: 'OPEN',
  createdTime: '2026-10-08T09:00:06',
};

/** 已自动补齐差异票：AUTO_FIXED → 无人工关闭入口 */
const FIXED_DIFF = {
  id: 502,
  runId: 31,
  examId: 7,
  submissionId: 9002,
  questionId: 13,
  diffType: 'DETAIL_TOTAL_MISMATCH',
  detail: '明细与总分不一致',
  expectedValue: 60,
  actualValue: 55,
  deviation: 5,
  status: 'AUTO_FIXED',
  createdTime: '2026-10-08T09:00:07',
};

/** 45 条差异票：供 mock 端按页切片，还原后端分页的真实形状 */
const ALL_DIFFS = Array.from({ length: 45 }, (_, i) => ({
  id: 700 + i,
  runId: 31,
  examId: 7,
  submissionId: 9000 + i,
  questionId: 1,
  diffType: 'SUBMISSION_UNGRADED',
  detail: `第 ${i + 1} 条差异`,
  expectedValue: 100,
  actualValue: 0,
  deviation: 100,
  status: 'OPEN',
  createdTime: '2026-10-08T09:00:00',
}));

/** mock 端按 query 做服务端切片，并按 runId/diffType/status 精确过滤（与后端 eq 同口径） */
function serveDiffSlice(
  arg: { query?: Record<string, unknown> } | undefined
): Promise<typeof ALL_DIFFS> {
  const query = arg?.query ?? {};
  const page = Number(query.page ?? 1);
  const size = Number(query.size ?? 20);
  let rows = ALL_DIFFS;
  if (query.runId !== undefined) {
    rows = rows.filter((r) => r.runId === Number(query.runId));
  }
  if (query.diffType) {
    rows = rows.filter((r) => r.diffType === query.diffType);
  }
  if (query.status) {
    rows = rows.filter((r) => r.status === query.status);
  }
  const start = (page - 1) * size;
  return Promise.resolve(rows.slice(start, start + size));
}

function mountReconciliationPage() {
  return mount(ReconciliationPage);
}

type PageWrapper = ReturnType<typeof mountReconciliationPage>;

/** 差异票表是页面里第 2 个 Table（第 1 个是运行历史） */
function diffTable(wrapper: PageWrapper) {
  return wrapper.findAllComponents(Table)[1];
}

function diffRows(wrapper: PageWrapper) {
  return diffTable(wrapper)
    .findAll('tbody tr')
    .filter((tr) => tr.text().trim().length > 0);
}

/** 已发出的运行历史查询参数序列 */
function servedRunQueries(): Record<string, unknown>[] {
  return vi
    .mocked(api.reconciliationRuns)
    .mock.calls.map((call) => (call[0]?.query ?? {}) as unknown as Record<string, unknown>);
}

function lastRunQuery(): Record<string, unknown> {
  const all = servedRunQueries();
  return all[all.length - 1] ?? {};
}

/** 已发出的差异票查询参数序列 */
function servedDiffQueries(): Record<string, unknown>[] {
  return vi
    .mocked(api.reconciliationDiffs)
    .mock.calls.map((call) => (call[0]?.query ?? {}) as unknown as Record<string, unknown>);
}

function lastDiffQuery(): Record<string, unknown> {
  const all = servedDiffQueries();
  return all[all.length - 1] ?? {};
}

function closeButtons(wrapper: PageWrapper) {
  return wrapper.findAll('button').filter((b) => b.text() === '人工关闭');
}

function findSelect(wrapper: PageWrapper, placeholder: string) {
  return wrapper.findAllComponents(Select).find((s) => s.props('placeholder') === placeholder);
}

function findCloseModal(wrapper: PageWrapper) {
  return wrapper.findAllComponents(Modal).find((m) => m.props('title') === '关闭差异票');
}

beforeEach(() => {
  invalidateQueries.mockClear();
  vi.mocked(api.runReconciliation).mockReset();
  vi.mocked(api.runReconciliation).mockResolvedValue(SUCCESS_RUN as never);
  vi.mocked(api.reconciliationRuns).mockReset();
  vi.mocked(api.reconciliationRuns).mockResolvedValue([SUCCESS_RUN, FAILED_RUN] as never);
  vi.mocked(api.reconciliationDiffs).mockReset();
  vi.mocked(api.reconciliationDiffs).mockResolvedValue([OPEN_DIFF, FIXED_DIFF] as never);
  vi.mocked(api.closeReconciliationDiff).mockReset();
  vi.mocked(api.closeReconciliationDiff).mockResolvedValue({
    ...OPEN_DIFF,
    status: 'MANUAL_CLOSED',
  } as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('成绩链对账页（admin/reconciliation）', () => {
  it('运行历史按后端字段直渲染：触发来源/状态/三类计数/错误信息/时间', async () => {
    const wrapper = mountReconciliationPage();
    await flushPromises();
    await flushPromises();

    const text = wrapper.text();
    expect(text).toContain('MANUAL');
    expect(text).toContain('SCHEDULED');
    expect(text).toContain('SUCCESS');
    expect(text).toContain('FAILED');
    expect(text).toContain('128');
    expect(text).toContain('成绩链读取超时');
    // 时间统一 'YYYY-MM-DD HH:mm'，空值渲染 —
    expect(text).toContain('2026-10-08 09:00');
    expect(text).toContain('2026-10-07 22:00');
    expect(text).toContain('—');
  });

  it('差异票按后端字段直渲染：差异类型/状态/期望值/实际值/偏差', async () => {
    const wrapper = mountReconciliationPage();
    await flushPromises();
    await flushPromises();

    const text = wrapper.text();
    expect(text).toContain('SUBMISSION_UNGRADED');
    expect(text).toContain('DETAIL_TOTAL_MISMATCH');
    expect(text).toContain('OPEN');
    expect(text).toContain('AUTO_FIXED');
    expect(text).toContain('86');
    expect(text).toContain('60');
    expect(text).toContain('55');
    expect(text).toContain('2026-10-08 09:00');
  });

  it('查询失败：错误 Alert 显性呈现后端 message，表格隐藏且空态不出现', async () => {
    vi.mocked(api.reconciliationRuns).mockRejectedValue(
      new ApiError(500, '成绩链对账服务不可用', undefined, 500)
    );
    const wrapper = mountReconciliationPage();
    await flushPromises();
    await flushPromises();

    const alert = wrapper.find('[data-test="reconciliation-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('成绩链对账服务不可用');
    expect(wrapper.text()).not.toContain('暂无数据');
    expect(wrapper.findAll('tbody tr')).toHaveLength(0);
  });

  it('成功且无数据：空态照常呈现，不弹错误 Alert', async () => {
    vi.mocked(api.reconciliationRuns).mockResolvedValue([] as never);
    vi.mocked(api.reconciliationDiffs).mockResolvedValue([] as never);
    const wrapper = mountReconciliationPage();
    await flushPromises();
    await flushPromises();

    expect(wrapper.find('[data-test="reconciliation-error"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('暂无数据');
  });

  it('触发对账：examId 透传 body（空值不携带），成功后呈现三类计数并失效两个查询', async () => {
    const wrapper = mountReconciliationPage();
    await flushPromises();
    await flushPromises();

    const runButton = wrapper.findAll('button').find((b) => b.text() === '触发对账');
    expect(runButton).toBeTruthy();

    // 空考试 ID：body 不携带 examId
    await runButton?.trigger('click');
    await flushPromises();
    expect(api.runReconciliation).toHaveBeenCalledTimes(1);
    expect(vi.mocked(api.runReconciliation).mock.calls[0]?.[0]).toEqual(
      expect.objectContaining({ throwOnError: true, body: {} })
    );

    await wrapper.find('input[placeholder*="考试 ID"]').setValue('7');
    await runButton?.trigger('click');
    await flushPromises();

    expect(vi.mocked(api.runReconciliation).mock.calls[1]?.[0]).toEqual(
      expect.objectContaining({ throwOnError: true, body: { examId: 7 } })
    );
    // 三类计数（未判分 / 明细不一致 / 自动补齐）在成功提示里呈现
    expect(message.success).toHaveBeenCalledTimes(2);
    const successText = String(vi.mocked(message.success).mock.calls[1]?.[0] ?? '');
    expect(successText).toContain('未判分 3');
    expect(successText).toContain('明细不一致 2');
    expect(successText).toContain('自动补齐 1');
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['reconciliation-runs'] });
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['reconciliation-diffs'] });
  });

  it('触发对账失败：message.error 呈现后端原文，不失效查询', async () => {
    vi.mocked(api.runReconciliation).mockRejectedValue(
      new ApiError(500, '对账任务启动失败', undefined, 500)
    );
    const wrapper = mountReconciliationPage();
    await flushPromises();
    await flushPromises();

    const runButton = wrapper.findAll('button').find((b) => b.text() === '触发对账');
    await runButton?.trigger('click');
    await flushPromises();

    expect(message.error).toHaveBeenCalledWith('对账任务启动失败');
    expect(message.success).not.toHaveBeenCalled();
    expect(invalidateQueries).not.toHaveBeenCalled();
  });

  it('人工关闭入口 fail-closed：仅 OPEN 行出现，AUTO_FIXED 行没有', async () => {
    const wrapper = mountReconciliationPage();
    await flushPromises();
    await flushPromises();

    expect(closeButtons(wrapper)).toHaveLength(1);

    const openRow = wrapper
      .findAll('tbody tr')
      .find((tr) => tr.text().includes('答卷 9001 未判分'));
    expect(openRow).toBeTruthy();
    expect(openRow?.findAll('button').some((b) => b.text() === '人工关闭')).toBe(true);

    const fixedRow = wrapper
      .findAll('tbody tr')
      .find((tr) => tr.text().includes('明细与总分不一致'));
    expect(fixedRow).toBeTruthy();
    expect(fixedRow?.findAll('button').some((b) => b.text() === '人工关闭')).toBe(false);
  });

  it('确认关闭：弹窗明示仅处置不改分，确认后按 path.id 调用并失效两个查询', async () => {
    const wrapper = mountReconciliationPage();
    await flushPromises();
    await flushPromises();

    await closeButtons(wrapper)[0]?.trigger('click');
    await flushPromises();

    await vi.waitFor(() => expect(document.body.textContent ?? '').toContain('人工处置完成'));
    const modalText = document.body.textContent ?? '';
    expect(modalText).toContain('不会改动任何分数');

    const modal = findCloseModal(wrapper);
    expect(modal).toBeTruthy();
    modal?.vm.$emit('ok');
    await flushPromises();

    expect(api.closeReconciliationDiff).toHaveBeenCalledWith(
      expect.objectContaining({ throwOnError: true, path: { id: 501 } })
    );
    expect(message.success).toHaveBeenCalled();
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['reconciliation-runs'] });
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['reconciliation-diffs'] });
  });

  it('关闭失败：message.error 呈现后端原文，不失效查询、不展示伪成功', async () => {
    vi.mocked(api.closeReconciliationDiff).mockRejectedValue(
      new ApiError(403, '无权关闭该差异票', undefined, 403)
    );
    const wrapper = mountReconciliationPage();
    await flushPromises();
    await flushPromises();

    await closeButtons(wrapper)[0]?.trigger('click');
    await flushPromises();

    const modal = findCloseModal(wrapper);
    modal?.vm.$emit('ok');
    await flushPromises();

    expect(message.error).toHaveBeenCalledWith('无权关闭该差异票');
    expect(message.success).not.toHaveBeenCalled();
    expect(invalidateQueries).not.toHaveBeenCalled();
  });
});

describe('下界分页（差异票 page 2 可达）', () => {
  beforeEach(() => {
    vi.mocked(api.reconciliationDiffs).mockReset();
    vi.mocked(api.reconciliationDiffs).mockImplementation(((
      arg: { query?: Record<string, unknown> } | undefined
    ) => serveDiffSlice(arg)) as never);
  });

  it('满页时第 2 页可达并真实带 page:2,size:20 请求', async () => {
    const wrapper = mountReconciliationPage();
    await flushPromises();
    await flushPromises();

    expect(lastRunQuery()).toEqual({ page: 1, size: 20 });
    expect(lastDiffQuery()).toEqual({ page: 1, size: 20 });
    await vi.waitFor(() => expect(diffRows(wrapper)).toHaveLength(20));
    // 满页 → 下一页存在（字面 total=当页条数 会让第 2 页永远点不到）
    expect(diffTable(wrapper).find('.ant-pagination-item-2').exists()).toBe(true);

    diffTable(wrapper).vm.$emit('change', { current: 2, pageSize: 20 });
    await flushPromises();

    await vi.waitFor(() => expect(lastDiffQuery()).toEqual({ page: 2, size: 20 }));
    await vi.waitFor(() => expect(diffRows(wrapper)[0]?.text()).toContain('第 21 条差异'));
  });
});

describe('差异票筛选（runId / diffType / status）', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(api.reconciliationDiffs).mockReset();
    vi.mocked(api.reconciliationDiffs).mockImplementation(((
      arg: { query?: Record<string, unknown> } | undefined
    ) => serveDiffSlice(arg)) as never);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('空筛选值不携带参数：首屏只带 page/size', async () => {
    mountReconciliationPage();
    await flushPromises();

    expect(Object.keys(lastDiffQuery()).sort()).toEqual(['page', 'size']);
  });

  it('diffType 与 status 透传服务端：300ms 防抖窗口内不请求', async () => {
    const wrapper = mountReconciliationPage();
    await flushPromises();
    expect(api.reconciliationDiffs).toHaveBeenCalledTimes(1);

    findSelect(wrapper, '差异类型')?.vm.$emit('update:value', 'SUBMISSION_UNGRADED');
    findSelect(wrapper, '处置状态')?.vm.$emit('update:value', 'OPEN');
    // 防抖定时器是在 watch 回调（微任务）里装上的：先让微任务跑完再拨表，否则拨的是空表
    await flushPromises();
    expect(api.reconciliationDiffs).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(100);
    await flushPromises();
    expect(api.reconciliationDiffs).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(200);
    await flushPromises();
    expect(api.reconciliationDiffs).toHaveBeenCalledTimes(2);
    expect(lastDiffQuery()).toEqual({
      page: 1,
      size: 20,
      diffType: 'SUBMISSION_UNGRADED',
      status: 'OPEN',
    });
  });

  it('运行 ID 筛选按数值透传 runId', async () => {
    const wrapper = mountReconciliationPage();
    await flushPromises();

    await wrapper.find('input[placeholder*="运行 ID"]').setValue(' 31 ');
    vi.advanceTimersByTime(300);
    await flushPromises();

    expect(lastDiffQuery()).toEqual({ page: 1, size: 20, runId: 31 });
  });

  it('切到第 2 页后修改筛选自动回第 1 页', async () => {
    const wrapper = mountReconciliationPage();
    await flushPromises();
    await flushPromises();

    diffTable(wrapper).vm.$emit('change', { current: 2, pageSize: 20 });
    await flushPromises();
    expect(lastDiffQuery()).toEqual({ page: 2, size: 20 });

    findSelect(wrapper, '差异类型')?.vm.$emit('update:value', 'DETAIL_TOTAL_MISMATCH');
    await flushPromises();
    vi.advanceTimersByTime(300);
    await flushPromises();

    expect(lastDiffQuery()).toEqual({ page: 1, size: 20, diffType: 'DETAIL_TOTAL_MISMATCH' });
  });
});

describe('导航接线护栏（(dashboard).page.vue）', () => {
  it('admin 分组含「成绩链对账」入口（菜单项 + 可导航路径）', () => {
    expect(dashboardPageSource).toContain("canAccess(role, '/admin/')");
    expect(dashboardPageSource).toContain("label: '成绩链对账'");
    const pathMatches = dashboardPageSource.match(/\/admin\/reconciliation/g) ?? [];
    expect(pathMatches.length).toBeGreaterThanOrEqual(2);
  });
});
