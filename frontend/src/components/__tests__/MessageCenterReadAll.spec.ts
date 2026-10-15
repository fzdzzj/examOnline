/**
 * 消息中心「全部已读」（add-exam-lifecycle-notifications，消息中心界面）。
 *
 * 断言直断形态：命中真实 `data-test` 属性，禁 if/exists 守卫与 `||` 双通。
 * 校验三件事：未读 > 0 时呈现「全部已读」按钮（未读 0 不呈现）；点击调 `PUT /api/messages/read-all`
 * （markAllRead），成功后联动失效 list 与 unread-count 两查询（清空未读并刷新列表为已读态）；
 * 失败显性 message.error 反馈、不静默。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';
import { message } from 'ant-design-vue';

import MessageCenter from '@/components/MessageCenter.vue';

// 基建适配：本文件 useQuery mock 用模块级共享 ref，多个 mount 会共享同一批 ref；
// 用例结束后必须卸载，否则后续用例改动 ref 会让已卸载残留组件重渲染。
enableAutoUnmount(afterEach);

type MessageRow = { id: number; title: string; content: string; readFlag: number; examId: number };
const UNREAD_A: MessageRow = {
  id: 21,
  title: '考试发布通知',
  content: '已发布',
  readFlag: 0,
  examId: 3,
};
const READ_A: MessageRow = {
  id: 22,
  title: '成绩发布通知',
  content: '成绩已出',
  readFlag: 1,
  examId: 4,
};

const listMock = vi.fn();
const unreadMock = vi.fn();
const markReadMock = vi.fn();
const markAllReadMock = vi.fn();

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

const queryUnread = ref<number | undefined>(undefined);
const queryList = ref<MessageRow[] | undefined>(undefined);
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
    markAllRead: (...a: unknown[]) => markAllReadMock(...a),
  };
});

describe('MessageCenter 全部已读', () => {
  beforeEach(() => {
    listMock.mockResolvedValue([UNREAD_A, READ_A]);
    unreadMock.mockResolvedValue(1);
    markAllReadMock.mockResolvedValue(undefined);
    queryUnread.value = undefined;
    queryList.value = undefined;
    queryUnreadError.value = null;
    queryListError.value = null;
    invalidateQueries.mockClear();
    listMock.mockClear();
    unreadMock.mockClear();
    markAllReadMock.mockClear();
  });

  afterEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
  });

  it('未读 > 0 时头部呈现「全部已读」按钮', async () => {
    unreadMock.mockResolvedValue(2);
    const wrapper = mount(MessageCenter, { attachTo: document.body });
    await flushPromises();
    await flushPromises();
    await wrapper.find('[data-test="message-bell"]').trigger('click');
    await flushPromises();

    const btn = document.body.querySelector('[data-test="mark-all-read-button"]');
    expect(btn).not.toBeNull();
    expect(btn?.textContent).toContain('全部已读');
  });

  it('未读 = 0 时不呈现「全部已读」按钮', async () => {
    unreadMock.mockResolvedValue(0);
    const wrapper = mount(MessageCenter, { attachTo: document.body });
    await flushPromises();
    await flushPromises();
    await wrapper.find('[data-test="message-bell"]').trigger('click');
    await flushPromises();

    expect(document.body.querySelector('[data-test="mark-all-read-button"]')).toBeNull();
  });

  it('点击「全部已读」调用 markAllRead 并联动刷新 list 与 unread-count 两查询', async () => {
    const wrapper = mount(MessageCenter, { attachTo: document.body });
    await flushPromises();
    await wrapper.find('[data-test="message-bell"]').trigger('click');
    await flushPromises();

    const btn = document.body.querySelector(
      '[data-test="mark-all-read-button"]'
    ) as HTMLButtonElement;
    expect(btn).not.toBeNull();
    btn.click();
    await flushPromises();

    expect(markAllReadMock).toHaveBeenCalledTimes(1);
    const refreshCalls = invalidateQueries.mock.calls.filter((c) =>
      JSON.stringify(c[0])?.includes('messages')
    );
    expect(refreshCalls.length).toBeGreaterThanOrEqual(2);
  });

  it('「全部已读」失败 → message.error 显性反馈，不静默', async () => {
    const errorSpy = vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
    markAllReadMock.mockRejectedValueOnce(new Error('全部已读失败'));
    const wrapper = mount(MessageCenter, { attachTo: document.body });
    await flushPromises();
    await wrapper.find('[data-test="message-bell"]').trigger('click');
    await flushPromises();

    const btn = document.body.querySelector(
      '[data-test="mark-all-read-button"]'
    ) as HTMLButtonElement;
    expect(btn).not.toBeNull();
    btn.click();
    await flushPromises();

    expect(errorSpy).toHaveBeenCalledWith('全部已读失败');
  });
});
