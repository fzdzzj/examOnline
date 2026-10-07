/**
 * 邀请码管理页用例（add-frontend-invite-code-admin，前端台账 F-4 最小闭环）。
 *
 * 断言口径与 spec-delta 对齐：
 * - 列表按后端字段渲染（状态 0=有效、1=已作废；作废入口仅有效行出现）；
 * - 查询失败 → 错误 Alert 显性呈现后端 message 且空态不出现（U-1 三态分离开口径）；
 *   成功且无数据 → 空态照常呈现；
 * - 生成：提交 createInviteCode（字段按契约，body 仅 note），成功后弹层内展示新码、
 *   失效邀请码列表；失败 message 呈现后端原文且不失效、不展示伪成功结果；
 * - 作废：知情确认弹窗（形态对齐考试删除）确认后调 invalidateInviteCode（path 携带 id）
 *   并失效列表；失败 message 呈现后端原文且不失效。
 *
 * 页面通过 mock 契约层（@/api/axios）与数据层（@tanstack/vue-query）挂载；useQuery mock
 * 沿用 examEditDelete.spec 的真链路口径（真实调用 queryFn，rejection 落进 error ref）。
 * 弹窗经 portal 渲染，用 document.body 断言。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { Modal, message } from 'ant-design-vue';

import * as api from '@/api/axios';
import { ApiError } from '@/api/types';
import InviteCodesPage from '@/pages/(dashboard)/admin/invite-codes/index.page.vue';
import dashboardPageSource from '@/pages/(dashboard).page.vue?raw';

// ---- 各 mock 模块需要跨 beforeEach 共享的状态（vi.hoisted 提到 mock 之前） ----
const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  listInviteCodes: vi.fn(),
  createInviteCode: vi.fn(),
  invalidateInviteCode: vi.fn(),
}));

/** useQuery mock：真实调用页面 queryFn（真链路口径） */
vi.mock('@tanstack/vue-query', async () => {
  const { ref } = await import('vue');
  return {
    useQuery: (options: { queryFn?: () => unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      if (options.queryFn) {
        Promise.resolve()
          .then(options.queryFn)
          .then((value: unknown) => {
            data.value = value;
          })
          .catch((reason: unknown) => {
            error.value = reason;
          });
      }
      return { data, error, isFetching: ref(false), refetch: vi.fn() };
    },
  };
});

/** 两行邀请码：11=有效（可作废）、10=已作废（不显示作废入口） */
const ACTIVE_ROW = {
  id: 11,
  code: 'ABCD2345',
  note: '给新老师',
  status: 0,
  usedCount: 2,
  createdBy: 1,
  createdTime: '2026-10-01T09:00:00',
};
const INVALIDATED_ROW = {
  id: 10,
  code: 'XYZ98765',
  note: '',
  status: 1,
  usedCount: 0,
  createdBy: 1,
  createdTime: '2026-09-30T18:30:00',
};
const LIST_ROWS = [ACTIVE_ROW, INVALIDATED_ROW];

/** 生成成功的返回：新码只此一次展示 */
const CREATED_ROW = {
  id: 12,
  code: 'NEWCODE1',
  note: '2026 秋新教师',
  status: 0,
  usedCount: 0,
  createdBy: 1,
  createdTime: '2026-10-07T10:00:00',
};

function mountInviteCodesPage() {
  return mount(InviteCodesPage);
}

function bodyText(): string {
  return document.body.textContent ?? '';
}

/** 在指定行文本所在 tr 内按按钮文本定位按钮 */
function findRowButton(wrapper: ReturnType<typeof mount>, rowText: string, buttonText: string) {
  const row = wrapper.findAll('tbody tr').find((tr) => tr.text().includes(rowText));
  return row?.findAll('button').find((b) => b.text() === buttonText);
}

beforeEach(() => {
  vi.mocked(api.listInviteCodes).mockResolvedValue(LIST_ROWS as never);
  vi.mocked(api.createInviteCode).mockResolvedValue(CREATED_ROW as never);
  vi.mocked(api.invalidateInviteCode).mockResolvedValue({} as never);
  // antd message 会往 document.body 渲染提示条，spy 掉避免污染 body 断言
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('邀请码管理页（admin/invite-codes）', () => {
  it('列表按后端字段渲染：状态标签呈现，作废入口仅有效行出现', async () => {
    const wrapper = mountInviteCodesPage();
    await flushPromises();
    await flushPromises();

    expect(wrapper.text()).toContain('ABCD2345');
    expect(wrapper.text()).toContain('XYZ98765');
    expect(wrapper.text()).toContain('给新老师');
    expect(wrapper.text()).toContain('2026-10-01 09:00');
    expect(wrapper.text()).toContain('有效');
    expect(wrapper.text()).toContain('已作废');

    const invalidateButtons = wrapper.findAll('button').filter((b) => b.text() === '作废');
    expect(invalidateButtons).toHaveLength(1);
  });

  it('查询失败：错误 Alert 显性呈现后端 message，空态不出现', async () => {
    vi.mocked(api.listInviteCodes).mockRejectedValue(
      new ApiError(500, '服务器内部错误', undefined, 500)
    );
    const wrapper = mountInviteCodesPage();
    await flushPromises();
    await flushPromises();

    const alert = wrapper.find('[data-test="invite-codes-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('服务器内部错误');
    // 失败不得伪装成空态
    expect(wrapper.text()).not.toContain('暂无数据');
  });

  it('成功且无数据：空态照常呈现，不弹错误 Alert', async () => {
    vi.mocked(api.listInviteCodes).mockResolvedValue([] as never);
    const wrapper = mountInviteCodesPage();
    await flushPromises();
    await flushPromises();

    expect(wrapper.find('[data-test="invite-codes-error"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('暂无数据');
  });

  it('生成成功：提交 createInviteCode（body 携带 note），弹层内展示新码并失效列表查询', async () => {
    const wrapper = mountInviteCodesPage();
    await flushPromises();
    await flushPromises();

    const createButton = wrapper.findAll('button').find((b) => b.text().includes('生成邀请码'));
    expect(createButton).toBeTruthy();
    await createButton?.trigger('click');
    await flushPromises();

    const createModal = wrapper
      .findAllComponents(Modal)
      .find((m) => m.props('title') === '生成邀请码');
    expect(createModal).toBeTruthy();

    (wrapper.vm as unknown as { noteInput: string }).noteInput = '2026 秋新教师';
    createModal?.vm.$emit('ok');
    await flushPromises();

    expect(api.createInviteCode).toHaveBeenCalledWith(
      expect.objectContaining({ throwOnError: true, body: { note: '2026 秋新教师' } })
    );
    await vi.waitFor(() => expect(bodyText()).toContain('NEWCODE1'));
    // spec-delta 场景 2「提供复制入口」：copyable 渲染的复制按钮在弹层内
    expect(document.body.querySelector('.ant-typography-copy')).not.toBeNull();
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['invite-codes'] });
  });

  it('生成失败：message 呈现后端原文，不失效查询、不展示伪成功结果', async () => {
    vi.mocked(api.createInviteCode).mockRejectedValue(
      new ApiError(500, '邀请码生成失败：数据库不可用', undefined, 500)
    );
    const wrapper = mountInviteCodesPage();
    await flushPromises();
    await flushPromises();

    const createButton = wrapper.findAll('button').find((b) => b.text().includes('生成邀请码'));
    await createButton?.trigger('click');
    await flushPromises();

    const createModal = wrapper
      .findAllComponents(Modal)
      .find((m) => m.props('title') === '生成邀请码');
    createModal?.vm.$emit('ok');
    await flushPromises();

    expect(message.error).toHaveBeenCalledWith('邀请码生成失败：数据库不可用');
    expect(message.success).not.toHaveBeenCalled();
    expect(invalidateQueries).not.toHaveBeenCalled();
  });

  it('作废知情确认：确认后调 invalidateInviteCode（path 携带 id）并失效列表查询', async () => {
    const wrapper = mountInviteCodesPage();
    await flushPromises();
    await flushPromises();

    const invalidateButton = findRowButton(wrapper, 'ABCD2345', '作废');
    expect(invalidateButton).toBeTruthy();
    await invalidateButton?.trigger('click');
    await flushPromises();

    await vi.waitFor(() => expect(bodyText()).toContain('不可逆'));

    const confirmModal = wrapper
      .findAllComponents(Modal)
      .find((m) => m.props('title') === '确认作废邀请码');
    expect(confirmModal).toBeTruthy();
    confirmModal?.vm.$emit('ok');
    await flushPromises();

    expect(api.invalidateInviteCode).toHaveBeenCalledWith(
      expect.objectContaining({ throwOnError: true, path: { id: 11 } })
    );
    expect(message.success).toHaveBeenCalledWith('邀请码已作废');
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['invite-codes'] });
  });

  it('作废失败：message 呈现后端原文且不失效查询', async () => {
    vi.mocked(api.invalidateInviteCode).mockRejectedValue(
      new ApiError(404, '邀请码不存在', undefined, 404)
    );
    const wrapper = mountInviteCodesPage();
    await flushPromises();
    await flushPromises();

    const invalidateButton = findRowButton(wrapper, 'ABCD2345', '作废');
    await invalidateButton?.trigger('click');
    await flushPromises();

    await vi.waitFor(() => expect(bodyText()).toContain('不可逆'));
    const confirmModal = wrapper
      .findAllComponents(Modal)
      .find((m) => m.props('title') === '确认作废邀请码');
    confirmModal?.vm.$emit('ok');
    await flushPromises();

    expect(message.error).toHaveBeenCalledWith('邀请码不存在');
    expect(message.success).not.toHaveBeenCalled();
    expect(invalidateQueries).not.toHaveBeenCalled();
  });
});

describe('导航接线护栏（(dashboard).page.vue）', () => {
  it('admin 分组经角色过滤且含「邀请码管理」入口（菜单项 + 可导航路径）', () => {
    expect(dashboardPageSource).toContain("canAccess(role, '/admin/')");
    const pathMatches = dashboardPageSource.match(/\/admin\/invite-codes/g) ?? [];
    // 菜单项 key 与 NAVIGABLE_PATHS 白名单两处
    expect(pathMatches.length).toBeGreaterThanOrEqual(2);
  });
});
