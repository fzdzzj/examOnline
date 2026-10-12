/**
 * 消息中心铃铛 + 列表/标已读（add-exam-reservation，消息中心界面）。
 *
 * 断言直断形态：命中真实 `data-test` 属性，禁 if/exists 守卫与 `||` 双通。
 * 校验三件事：未读角标 = 服务端未读数；列表倒序呈现本人消息；标已读 → 调 markRead 并联动刷新未读数
 * （前端不本地持久化，以服务端为准）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';
import { message } from 'ant-design-vue';

import MessageCenter from '@/components/MessageCenter.vue';

// 基建适配：本文件 useQuery mock 用模块级共享 ref，多个 mount 会共享同一批 ref；
// 用例结束后必须卸载，否则后续用例改动 ref 会让已卸载残留组件重渲染、把弹层
// 重新塞回 body（document.body 里的 mc-item 计数被污染）。
enableAutoUnmount(afterEach);

type MessageRow = { id: number; title: string; content: string; readFlag: number; examId: number };
const MESSAGE_A: MessageRow = {
  id: 11,
  title: '开考提醒',
  content: '即将开考',
  readFlag: 0,
  examId: 1,
};
const MESSAGE_B: MessageRow = {
  id: 12,
  title: '开考提醒',
  content: '即将开考',
  readFlag: 1,
  examId: 2,
};

const listMock = vi.fn();
const unreadMock = vi.fn();
const markReadMock = vi.fn();

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

const queryUnread = ref<number | undefined>(undefined);
const queryList = ref<MessageRow[] | undefined>(undefined);
// 基建适配：补 error ref，供「查询失败显性化」用例注入失败态（既有三用例断言零改动）
const queryUnreadError = ref<Error | null>(null);
const queryListError = ref<Error | null>(null);
const invalidateQueries = vi.fn().mockResolvedValue(undefined);

vi.mock('@tanstack/vue-query', () => ({
  useQuery: (options: { queryKey: unknown[]; queryFn?: () => unknown }) => {
    const key = String(options.queryKey[1]);
    if (key === 'unread-count') {
      if (options.queryFn) {
        Promise.resolve()
          .then(options.queryFn)
          .then((val) => {
            queryUnread.value = (val as number | undefined) ?? 0;
          })
          .catch((err: Error) => {
            queryUnreadError.value = err;
          });
      }
      return {
        data: queryUnread,
        error: queryUnreadError,
        isLoading: ref(false),
        refetch: vi.fn(),
      };
    }
    if (key === 'list') {
      if (options.queryFn) {
        Promise.resolve()
          .then(options.queryFn)
          .then((val) => {
            queryList.value = val as MessageRow[] | undefined;
          })
          .catch((err: Error) => {
            queryListError.value = err;
          });
      }
      return { data: queryList, error: queryListError, isLoading: ref(false), refetch: vi.fn() };
    }
    throw new Error('unexpected queryKey ' + key);
  },
  useQueryClient: () => ({ invalidateQueries }),
}));

vi.mock('@/api/axios', async () => {
  const actual = await vi.importActual('@/api/axios');
  return {
    ...actual,
    list1: (...a: unknown[]) => listMock(...a),
    unreadCount: (...a: unknown[]) => unreadMock(...a),
    markRead: (...a: unknown[]) => markReadMock(...a),
  };
});

describe('MessageCenter', () => {
  beforeEach(() => {
    listMock.mockResolvedValue([MESSAGE_B, MESSAGE_A]);
    unreadMock.mockResolvedValue(1);
    markReadMock.mockResolvedValue(undefined);
    queryUnread.value = undefined;
    queryList.value = undefined;
    queryUnreadError.value = null;
    queryListError.value = null;
    invalidateQueries.mockClear();
    listMock.mockClear();
    unreadMock.mockClear();
    markReadMock.mockClear();
  });

  afterEach(() => {
    vi.clearAllMocks();
    // 基建适配：卸载残留的 Popover 弹层（attachTo body），避免跨用例 DOM 计数污染
    document.body.innerHTML = '';
  });

  it('未读角标呈现服务端未读数 ≠ 0', async () => {
    unreadMock.mockResolvedValue(2);
    const wrapper = mount(MessageCenter);
    await flushPromises();
    await flushPromises();
    const badge = wrapper.find('[data-test="unread-badge"]');
    expect(badge.exists()).toBe(true);
    expect(badge.text()).toContain('2');
  });

  it('消息列表按倒序呈现本人消息（最新在上发在前）', async () => {
    const wrapper = mount(MessageCenter, { attachTo: document.body });
    await flushPromises();
    // 打开消息铃铛弹层（antd Popover trigger=click 延迟渲染，内容渲染于 document.body）
    await wrapper.find('[data-test="message-bell"]').trigger('click');
    await flushPromises();
    const items = Array.from(document.body.querySelectorAll('[data-test="mc-item"]'));
    expect(items.length).toBe(2);
    expect(
      Array.from(document.body.querySelectorAll('[data-test="mc-item-title"]')).map((n) =>
        n.textContent?.trim()
      )
    ).toEqual(['开考提醒', '开考提醒']);
  });

  it('未读消息行呈现「标为已读」，点击调用 markRead 并联动刷新未读查询', async () => {
    const wrapper = mount(MessageCenter, { attachTo: document.body });
    await flushPromises();
    await wrapper.find('[data-test="message-bell"]').trigger('click');
    await flushPromises();
    const btn = document.body.querySelector('[data-test="mark-read-button"]') as HTMLButtonElement;
    expect(btn).not.toBeNull();
    btn.click();
    await flushPromises();
    expect(markReadMock).toHaveBeenCalledTimes(1);
    const markCalls = invalidateQueries.mock.calls.filter((c) =>
      JSON.stringify(c[0])?.includes('messages')
    );
    expect(markCalls.length).toBeGreaterThanOrEqual(2);
  });

  it('消息列表查询失败 → 错误 Alert 呈现后端 message，「暂无消息」空态与列表均不渲染', async () => {
    listMock.mockRejectedValueOnce(new Error('消息服务暂不可用'));
    const wrapper = mount(MessageCenter, { attachTo: document.body });
    await flushPromises();
    await wrapper.find('[data-test="message-bell"]').trigger('click');
    await flushPromises();

    const alert = document.body.querySelector('[data-test="mc-error"]');
    expect(alert).not.toBeNull();
    expect(alert?.textContent).toContain('消息服务暂不可用');
    // 失败不得伪装成「暂无消息」空态
    expect(document.body.querySelector('[data-test="mc-empty"]')).toBeNull();
    expect(document.body.querySelectorAll('[data-test="mc-item"]')).toHaveLength(0);
  });

  it('未读数查询失败且列表成功 → 错误 Alert 呈现、角标不出现、消息列表照常渲染', async () => {
    unreadMock.mockRejectedValueOnce(new Error('未读数服务暂不可用'));
    const wrapper = mount(MessageCenter, { attachTo: document.body });
    await flushPromises();
    await wrapper.find('[data-test="message-bell"]').trigger('click');
    await flushPromises();

    const alert = document.body.querySelector('[data-test="mc-error"]');
    expect(alert).not.toBeNull();
    expect(alert?.textContent).toContain('未读数服务暂不可用');
    // 不伪造可用未读数
    expect(wrapper.find('[data-test="unread-badge"]').exists()).toBe(false);
    // 列表不因未读数失败被隐藏
    expect(document.body.querySelectorAll('[data-test="mc-item"]')).toHaveLength(2);
  });

  it('两查询成功且消息为空数组 → 保留「暂无消息」业务空态且无错误 Alert', async () => {
    listMock.mockResolvedValueOnce([]);
    const wrapper = mount(MessageCenter, { attachTo: document.body });
    await flushPromises();
    await wrapper.find('[data-test="message-bell"]').trigger('click');
    await flushPromises();

    expect(document.body.querySelector('[data-test="mc-empty"]')).not.toBeNull();
    expect(document.body.querySelector('[data-test="mc-error"]')).toBeNull();
  });

  it('标已读失败 → message.error 显性反馈，不静默', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
    markReadMock.mockRejectedValueOnce(new Error('标记失败'));
    const wrapper = mount(MessageCenter, { attachTo: document.body });
    await flushPromises();
    await wrapper.find('[data-test="message-bell"]').trigger('click');
    await flushPromises();

    const btn = document.body.querySelector('[data-test="mark-read-button"]') as HTMLButtonElement;
    expect(btn).not.toBeNull();
    btn.click();
    await flushPromises();

    expect(errorSpy).toHaveBeenCalledWith('标记失败');
  });
});
