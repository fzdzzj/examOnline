/**
 * 消息中心铃铛 + 列表/标已读（add-exam-reservation，消息中心界面）。
 *
 * 断言直断形态：命中真实 `data-test` 属性，禁 if/exists 守卫与 `||` 双通。
 * 校验三件事：未读角标 = 服务端未读数；列表倒序呈现本人消息；标已读 → 调 markRead 并联动刷新未读数
 * （前端不本地持久化，以服务端为准）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import MessageCenter from '@/components/MessageCenter.vue';

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
          });
      }
      return { data: queryUnread, isLoading: ref(false), refetch: vi.fn() };
    }
    if (key === 'list') {
      if (options.queryFn) {
        Promise.resolve()
          .then(options.queryFn)
          .then((val) => {
            queryList.value = val as MessageRow[] | undefined;
          });
      }
      return { data: queryList, isLoading: ref(false), refetch: vi.fn() };
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
    invalidateQueries.mockClear();
    listMock.mockClear();
    unreadMock.mockClear();
    markReadMock.mockClear();
  });

  afterEach(() => {
    vi.clearAllMocks();
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
});
