/**
 * 审计日志与强制下线用例（add-frontend-audit-logs-kick，前端台账 F-4 第二阶段）。
 *
 * 断言口径与 spec-delta 对齐：
 * - 列表按后端字段直渲染（action/status 不本地推算），「强制下线」入口仅携带 userId 的行出现
 *   ——账户锁定一类系统事件行 user_id 为 NULL，没有可下线的对象；
 * - 查询失败 → 错误 Alert 显性呈现后端 message 且空态不出现（对齐「查询失败与业务态分离」）；
 *   成功且无数据 → 空态照常呈现；
 * - auditLogs 响应是页内数组、信封无 total：分页按「满页即至少还有下一页」做下界推断，
 *   第 2 页必须可达且真实带 page 请求，末页（未满页）不预留后续页码；
 * - username/action 筛选透传服务端（空值不携带该参数、值去首尾空白），300ms 防抖，
 *   筛选变更回第 1 页；
 * - 强制下线：危险确认弹窗明示「会话立即失效、需重新登录」，确认后调 kickUser（path 携带 userId）
 *   并失效列表查询；失败 message 呈现后端原文且不失效、不展示伪成功结果；
 * - 导航接线词法护栏：/admin/audit-logs 在菜单项与 NAVIGABLE_PATHS 两处出现。
 *
 * 页面通过 mock 契约层（@/api/axios）与数据层（@tanstack/vue-query）挂载；useQuery mock
 * 沿用 examListFiltering.spec 的响应式口径（queryKey 变化即重新调用 queryFn，refetch 真跑）。
 * 弹窗经 portal 渲染到 body，用 document.body 文本断言。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { Modal, Table, message } from 'ant-design-vue';

import * as api from '@/api/axios';
import { ApiError } from '@/api/types';
import AuditLogsPage from '@/pages/(dashboard)/admin/audit-logs/index.page.vue';
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
  auditLogs: vi.fn(),
  kickUser: vi.fn(),
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

/** 登录成功行：携带 userId，可下线 */
const LOGIN_ROW = {
  id: 91,
  traceId: '19ae919553844069af3096643f2154f4',
  userId: 7,
  username: 'alice',
  action: 'LOGIN',
  ipAddress: '10.1.2.3',
  status: 'SUCCESS',
  details: '登录成功',
  createdTime: '2026-10-08T09:12:00',
};
/** 账户锁定行：系统事件，user_id 为 NULL——没有可下线的对象 */
const LOCK_ROW = {
  id: 90,
  traceId: '2f6c1d0e3b4a5968778899aabbccddeeff00',
  userId: undefined,
  username: 'bob',
  action: 'ACCOUNT_LOCKED',
  ipAddress: '10.1.2.9',
  status: 'WARNING',
  details: '连续登录失败 5 次',
  createdTime: '2026-10-07T22:41:00',
};

/** 45 条审计记录：供 mock 端按页切片，还原后端分页的真实形状 */
const ALL_ROWS = Array.from({ length: 45 }, (_, i) => ({
  id: 100 + i,
  traceId: `trace-${i}`,
  userId: 500 + i,
  username: `user_${i + 1}`,
  action: 'LOGIN',
  ipAddress: '10.0.0.1',
  status: 'SUCCESS',
  details: `第 ${i + 1} 条`,
  createdTime: '2026-10-08T09:00:00',
}));

/** mock 端按 query 做服务端切片，并按 username/action 精确过滤（与后端 eq 同口径） */
function serveSlice(arg: { query?: Record<string, unknown> } | undefined) {
  const query = arg?.query ?? {};
  const page = Number(query.page ?? 1);
  const size = Number(query.size ?? 20);
  let rows = ALL_ROWS;
  if (query.username) {
    rows = rows.filter((r) => r.username === query.username);
  }
  if (query.action) {
    rows = rows.filter((r) => r.action === query.action);
  }
  const start = (page - 1) * size;
  return Promise.resolve(rows.slice(start, start + size));
}

function mountAuditLogsPage() {
  return mount(AuditLogsPage);
}

function bodyText(): string {
  return document.body.textContent ?? '';
}

/** 已发出的 auditLogs 查询参数序列 */
function servedQueries(): Record<string, unknown>[] {
  return vi
    .mocked(api.auditLogs)
    .mock.calls.map((call) => (call[0]?.query ?? {}) as unknown as Record<string, unknown>);
}

function lastServedQuery(): Record<string, unknown> {
  const all = servedQueries();
  return all[all.length - 1] ?? {};
}

/** 用户名列（第 2 列，第 1 列为 ID）逐行文本 */
function userCells(wrapper: ReturnType<typeof mount>) {
  return wrapper
    .findAll('tbody tr')
    .filter((tr) => tr.text().trim().length > 0)
    .map((tr) => tr.findAll('td')[1]?.text() ?? '');
}

/** 在指定行文本所在 tr 内按按钮文本定位按钮 */
function findRowButton(wrapper: ReturnType<typeof mount>, rowText: string, buttonText: string) {
  const row = wrapper.findAll('tbody tr').find((tr) => tr.text().includes(rowText));
  return row?.findAll('button').find((b) => b.text() === buttonText);
}

function kickButtons(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('button').filter((b) => b.text() === '强制下线');
}

beforeEach(() => {
  invalidateQueries.mockClear();
  vi.mocked(api.auditLogs).mockReset();
  vi.mocked(api.auditLogs).mockResolvedValue([LOGIN_ROW, LOCK_ROW] as never);
  vi.mocked(api.kickUser).mockReset();
  vi.mocked(api.kickUser).mockResolvedValue({} as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('审计日志页（admin/audit-logs）', () => {
  it('列表按后端字段渲染：动作/状态/IP/时间/详情直读，下线入口仅 userId 行出现', async () => {
    const wrapper = mountAuditLogsPage();
    await flushPromises();
    await flushPromises();

    expect(wrapper.text()).toContain('alice');
    expect(wrapper.text()).toContain('bob');
    expect(wrapper.text()).toContain('LOGIN');
    expect(wrapper.text()).toContain('ACCOUNT_LOCKED');
    expect(wrapper.text()).toContain('SUCCESS');
    expect(wrapper.text()).toContain('WARNING');
    expect(wrapper.text()).toContain('10.1.2.3');
    expect(wrapper.text()).toContain('2026-10-08 09:12');
    expect(wrapper.text()).toContain('连续登录失败 5 次');

    // fail-closed：无 userId 的系统事件行不呈现下线入口
    expect(kickButtons(wrapper)).toHaveLength(1);
    const kickableRow = wrapper
      .findAll('tbody tr')
      .find((tr) => tr.findAll('button').some((b) => b.text() === '强制下线'));
    expect(kickableRow?.text()).toContain('alice');
    expect(kickableRow?.text()).not.toContain('bob');
  });

  it('查询失败：错误 Alert 显性呈现后端 message，空态不出现', async () => {
    vi.mocked(api.auditLogs).mockRejectedValue(new ApiError(500, '服务器内部错误', undefined, 500));
    const wrapper = mountAuditLogsPage();
    await flushPromises();
    await flushPromises();

    const alert = wrapper.find('[data-test="audit-logs-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('服务器内部错误');
    expect(wrapper.text()).not.toContain('暂无数据');
  });

  it('成功且无数据：空态照常呈现，不弹错误 Alert', async () => {
    vi.mocked(api.auditLogs).mockResolvedValue([] as never);
    const wrapper = mountAuditLogsPage();
    await flushPromises();
    await flushPromises();

    expect(wrapper.find('[data-test="audit-logs-error"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('暂无数据');
  });

  it('无 total 信封的下界分页：满页时第 2 页可达且真实带 page 请求，末页不预留', async () => {
    vi.mocked(api.auditLogs).mockImplementation(((arg: { query?: Record<string, unknown> }) =>
      serveSlice(arg)) as never);
    const wrapper = mountAuditLogsPage();
    await flushPromises();
    await flushPromises();

    expect(lastServedQuery()).toEqual({ page: 1, size: 20 });
    await vi.waitFor(() => expect(userCells(wrapper)).toHaveLength(20));
    // 满页 → 下一页存在（字面 total=当页条数 会让第 2 页永远点不到）
    expect(wrapper.find('.ant-pagination-item-2').exists()).toBe(true);

    wrapper.findComponent(Table).vm.$emit('change', { current: 2, pageSize: 20 });
    await flushPromises();
    await vi.waitFor(() => expect(lastServedQuery()).toEqual({ page: 2, size: 20 }));
    await vi.waitFor(() => expect(userCells(wrapper)[0]).toBe('user_21'));

    // 末页：45 条的第 3 页只有 5 条，未满页 → 不预留第 4 页
    wrapper.findComponent(Table).vm.$emit('change', { current: 3, pageSize: 20 });
    await flushPromises();
    await vi.waitFor(() => expect(userCells(wrapper)).toHaveLength(5));
    expect(wrapper.find('.ant-pagination-item-4').exists()).toBe(false);
  });

  it('强制下线知情确认：弹窗明示会话立即失效需重新登录，确认后调 kickUser（path 携带 userId）', async () => {
    const wrapper = mountAuditLogsPage();
    await flushPromises();
    await flushPromises();

    const kickButton = findRowButton(wrapper, 'alice', '强制下线');
    expect(kickButton).toBeTruthy();
    await kickButton?.trigger('click');
    await flushPromises();

    await vi.waitFor(() => expect(bodyText()).toContain('会话'));
    expect(bodyText()).toContain('重新登录');

    const confirmModal = wrapper
      .findAllComponents(Modal)
      .find((m) => m.props('title') === '确认强制下线');
    expect(confirmModal).toBeTruthy();
    confirmModal?.vm.$emit('ok');
    await flushPromises();

    expect(api.kickUser).toHaveBeenCalledWith(
      expect.objectContaining({ throwOnError: true, path: { userId: 7 } })
    );
    expect(message.success).toHaveBeenCalled();
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['audit-logs'] });
  });

  it('强制下线失败：message 呈现后端原文，不失效查询、不展示伪成功结果', async () => {
    vi.mocked(api.kickUser).mockRejectedValue(new ApiError(403, '无权管理该用户', undefined, 403));
    const wrapper = mountAuditLogsPage();
    await flushPromises();
    await flushPromises();

    const kickButton = findRowButton(wrapper, 'alice', '强制下线');
    await kickButton?.trigger('click');
    await flushPromises();

    const confirmModal = wrapper
      .findAllComponents(Modal)
      .find((m) => m.props('title') === '确认强制下线');
    confirmModal?.vm.$emit('ok');
    await flushPromises();

    expect(message.error).toHaveBeenCalledWith('无权管理该用户');
    expect(message.success).not.toHaveBeenCalled();
    expect(invalidateQueries).not.toHaveBeenCalled();
  });
});

describe('筛选联动（username / action）', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(api.auditLogs).mockReset();
    vi.mocked(api.auditLogs).mockImplementation(((arg: { query?: Record<string, unknown> }) =>
      serveSlice(arg)) as never);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('空筛选值不携带参数：首屏只带 page/size', async () => {
    mountAuditLogsPage();
    await flushPromises();

    expect(Object.keys(lastServedQuery()).sort()).toEqual(['page', 'size']);
  });

  it('用户名与动作筛选：防抖窗口内不请求，300ms 后以去空白的值带参请求', async () => {
    const wrapper = mountAuditLogsPage();
    await flushPromises();
    expect(api.auditLogs).toHaveBeenCalledTimes(1);

    await wrapper.find('input[placeholder*="用户名"]').setValue('  user_1  ');
    await wrapper.find('input[placeholder*="动作"]').setValue('LOGIN');

    vi.advanceTimersByTime(100);
    await flushPromises();
    expect(api.auditLogs).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(200);
    await flushPromises();
    expect(api.auditLogs).toHaveBeenCalledTimes(2);
    expect(lastServedQuery()).toEqual({ page: 1, size: 20, username: 'user_1', action: 'LOGIN' });
  });

  it('切到第 2 页后修改筛选自动回第 1 页', async () => {
    const wrapper = mountAuditLogsPage();
    await flushPromises();

    wrapper.findComponent(Table).vm.$emit('change', { current: 2, pageSize: 20 });
    await flushPromises();
    expect(lastServedQuery()).toEqual({ page: 2, size: 20 });

    await wrapper.find('input[placeholder*="用户名"]').setValue('nomatch');
    vi.advanceTimersByTime(300);
    await flushPromises();

    expect(lastServedQuery()).toEqual({ page: 1, size: 20, username: 'nomatch' });
  });
});

describe('导航接线护栏（(dashboard).page.vue）', () => {
  it('admin 分组含「审计日志」入口（菜单项 + 可导航路径）', () => {
    expect(dashboardPageSource).toContain("canAccess(role, '/admin/')");
    const pathMatches = dashboardPageSource.match(/\/admin\/audit-logs/g) ?? [];
    expect(pathMatches.length).toBeGreaterThanOrEqual(2);
  });
});
