/**
 * 作答端防作弊守卫的页面级接线用例（add-anti-cheat-restrictions 阶段 3）。
 *
 * 这里验的是**真实 DOM 事件 → 真实 hook → 真实上报契约**这条链，而不是各自单测：
 * - `forbidCopy`：copy/cut/paste/contextmenu 被 `preventDefault()` 拦下，并旁路上报
 *   `COPY_PASTE`（eventData.action 为事件原名），学生看到友好提示；
 * - `fullScreen`：策略开启且尚未全屏 → 引导弹层出现；退出全屏 → 上报 `FULLSCREEN_EXIT`；
 *   进入全屏 → 弹层消失且**不谎报**一次退出；
 * - **未配置即零行为**：antiCheatConfig 缺失时既不拦键鼠也不上报（脏/旧快照不误伤学生）；
 * - **离线降级重入仍生效**：本地快照经 `sanitizeSnapshotPayload` 落盘，配置随之保留；
 * - **只警告不交卷**：任一违规路径都不触碰 submit（结构性断言：submit mock 零调用）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import type { EnterExamResponse } from '@/api/axios';
import StudentExamTakingPage from '@/pages/(dashboard)/student/exams/[id].page.vue';
import {
  createMemoryExamSnapshotStorage,
  type ExamSnapshotStorage,
} from '@/utils/examSnapshotStorage';
import { createMemoryDraftStorage } from '@/utils/draftStorage';
import { ANTI_CHEAT_HINT } from '@/utils/antiCheat';

const mockPush = vi.fn();
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mockPush }),
  useRoute: () => ({ params: { id: '42' } }),
  onBeforeRouteLeave: vi.fn(),
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

const enterMock = vi.fn();
const reportBehaviorMock = vi.fn().mockResolvedValue({});
const saveDraftMock = vi.fn().mockResolvedValue({ version: 1, savedAt: '2026-10-10T10:00:00Z' });
const submitMock = vi.fn().mockResolvedValue({ submissionId: 999, examId: 42, status: 2 });

vi.mock('@/api/axios', async () => {
  const actual = await vi.importActual('@/api/axios');
  return {
    ...actual,
    enter: (...args: unknown[]) => enterMock(...args),
    reportBehavior: (...args: unknown[]) => reportBehaviorMock(...args),
    saveDraft: (...args: unknown[]) => saveDraftMock(...args),
    submit: (...args: unknown[]) => submitMock(...args),
  };
});

let testSnapshotStorage: ExamSnapshotStorage;
vi.mock('@/utils/examSnapshotStorage', async () => {
  const actual = await vi.importActual('@/utils/examSnapshotStorage');
  return {
    ...actual,
    createStudentExamSnapshotStorage: () => testSnapshotStorage,
  };
});

let testDraftStorage: ReturnType<typeof createMemoryDraftStorage>;
vi.mock('@/utils/draftStorage', async () => {
  const actual = await vi.importActual('@/utils/draftStorage');
  return {
    ...actual,
    createStudentDraftStorage: () => testDraftStorage,
  };
});

const queryData = ref<EnterExamResponse | undefined>(undefined);
const queryError = ref<Error | null>(null);
const queryIsFetching = ref(false);

vi.mock('@tanstack/vue-query', () => ({
  useQuery: () => ({
    data: queryData,
    error: queryError,
    isFetching: queryIsFetching,
    refetch: vi.fn(),
  }),
}));

/**
 * jsdom 不实现 Fullscreen API：`document.fullscreenElement` 用可切换的 getter 铺一层，
 * 测试里改 `fullscreenElement` 变量再派发 `fullscreenchange` 即模拟进入/退出全屏。
 */
let fullscreenElement: unknown = null;
function installFullscreenStub(): { request: ReturnType<typeof vi.fn> } {
  const request = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(document, 'fullscreenElement', {
    configurable: true,
    get: () => fullscreenElement,
  });
  Object.defineProperty(document.documentElement, 'requestFullscreen', {
    configurable: true,
    value: request,
  });
  return { request };
}

function snapshotWith(config: unknown, overrides: Partial<EnterExamResponse> = {}) {
  return {
    examId: 42,
    examTitle: '防作弊守卫测试考试',
    submissionId: 999,
    status: 1,
    startTime: '2026-10-10T09:00:00Z',
    deadlineTime: '2026-10-10T11:00:00Z',
    serverTime: '2026-10-10T09:30:00Z',
    remainingSeconds: 3600,
    questions: [{ number: 1, questionId: 1001, type: 1, content: '什么是二叉树？', score: 10 }],
    draftVersion: 1,
    antiCheatConfig: config as never,
    ...overrides,
  } satisfies EnterExamResponse;
}

/** 在作答页上真实派发一个可取消的剪贴板事件，返回该事件（可读 defaultPrevented）。 */
function dispatchClipboard(type: string): Event {
  const event = new Event(type, { cancelable: true, bubbles: true });
  document.dispatchEvent(event);
  return event;
}

describe('作答页防作弊守卫接线（[id].page.vue）', () => {
  let originalOnLine: boolean;

  beforeEach(() => {
    originalOnLine = navigator.onLine;
    testSnapshotStorage = createMemoryExamSnapshotStorage();
    testDraftStorage = createMemoryDraftStorage();
    queryData.value = undefined;
    queryError.value = null;
    queryIsFetching.value = false;
    fullscreenElement = null;
    vi.clearAllMocks();
    reportBehaviorMock.mockResolvedValue({});
  });

  afterEach(() => {
    Object.defineProperty(navigator, 'onLine', { value: originalOnLine, configurable: true });
    fullscreenElement = null;
  });

  it('forbidCopy：四类剪贴板事件被拦下、旁路上报 COPY_PASTE 并给出友好提示', async () => {
    installFullscreenStub();
    queryData.value = snapshotWith({ forbidCopy: true, fullScreen: true });

    const wrapper = mount(StudentExamTakingPage);
    await flushPromises();

    for (const [index, type] of ['copy', 'cut', 'paste', 'contextmenu'].entries()) {
      const event = dispatchClipboard(type);
      expect(event.defaultPrevented).toBe(true);
      await vi.waitFor(() => expect(reportBehaviorMock).toHaveBeenCalledTimes(index + 1));
      expect(reportBehaviorMock).toHaveBeenLastCalledWith(
        expect.objectContaining({
          path: { examId: 42 },
          body: { eventType: 'COPY_PASTE', eventData: { action: type } },
        })
      );
    }

    // 友好提示（文案来自常量，不各处自造）
    const notice = wrapper.find('[data-test="guard-notice"]');
    expect(notice.exists()).toBe(true);
    expect(notice.text()).toContain(ANTI_CHEAT_HINT.COPY_BLOCKED);
    // 只警告不交卷：任何违规路径都不得触碰提交入口
    expect(submitMock).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('fullScreen：未全屏时出现引导弹层，进入全屏后消失（不谎报一次退出）', async () => {
    installFullscreenStub();
    fullscreenElement = null;
    queryData.value = snapshotWith({ fullScreen: true });

    const wrapper = mount(StudentExamTakingPage);
    await flushPromises();

    const overlay = wrapper.find('[data-test="fullscreen-guard-overlay"]');
    expect(overlay.exists()).toBe(true);
    expect(overlay.text()).toContain('全屏');
    // 尚未全屏只是「待进入」，未经退出 → 零上报（不把引导当成违规）
    expect(reportBehaviorMock).not.toHaveBeenCalled();

    // 进入全屏：fullscreenchange + fullscreenElement 非空 → 弹层消失
    fullscreenElement = document.documentElement;
    document.dispatchEvent(new Event('fullscreenchange'));
    await flushPromises();
    expect(wrapper.find('[data-test="fullscreen-guard-overlay"]').exists()).toBe(false);
    expect(reportBehaviorMock).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('fullScreen：退出全屏上报 FULLSCREEN_EXIT 且提示学生，仍不交卷', async () => {
    installFullscreenStub();
    fullscreenElement = document.documentElement;
    queryData.value = snapshotWith({ fullScreen: true });

    const wrapper = mount(StudentExamTakingPage);
    await flushPromises();
    expect(wrapper.find('[data-test="fullscreen-guard-overlay"]').exists()).toBe(false);

    fullscreenElement = null;
    document.dispatchEvent(new Event('fullscreenchange'));

    await vi.waitFor(() => expect(reportBehaviorMock).toHaveBeenCalledTimes(1));
    expect(reportBehaviorMock).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { examId: 42 },
        body: { eventType: 'FULLSCREEN_EXIT', eventData: { action: 'exit' } },
      })
    );
    await flushPromises();
    expect(wrapper.find('[data-test="guard-notice"]').text()).toContain(
      ANTI_CHEAT_HINT.FULLSCREEN_EXIT
    );
    expect(wrapper.find('[data-test="fullscreen-guard-overlay"]').exists()).toBe(true);
    expect(submitMock).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('未配置 antiCheatConfig：不拦键鼠、零上报、无弹层（旧/脏快照零误伤）', async () => {
    installFullscreenStub();
    queryData.value = snapshotWith(undefined);

    const wrapper = mount(StudentExamTakingPage);
    await flushPromises();

    const event = dispatchClipboard('copy');
    expect(event.defaultPrevented).toBe(false);
    document.dispatchEvent(new Event('fullscreenchange'));
    await flushPromises();

    expect(reportBehaviorMock).not.toHaveBeenCalled();
    expect(wrapper.find('[data-test="fullscreen-guard-overlay"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="guard-notice"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('答卷已由后端封闭（status=2）：守卫不复活（已交卷不再要求全屏、不再上报）', async () => {
    installFullscreenStub();
    queryData.value = snapshotWith(
      { forbidCopy: true, fullScreen: true },
      { status: 2, remainingSeconds: 0 }
    );

    const wrapper = mount(StudentExamTakingPage);
    await flushPromises();

    document.dispatchEvent(new Event('fullscreenchange'));
    await flushPromises();

    expect(reportBehaviorMock).not.toHaveBeenCalled();
    expect(wrapper.find('[data-test="fullscreen-guard-overlay"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('离线降级重入：本地快照保留 antiCheatConfig，守卫在断线重入后仍生效', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    installFullscreenStub();
    // 本地快照经 sanitizeSnapshotPayload 落盘：抗脱敏白名单必须放行 antiCheatConfig
    await testSnapshotStorage.save(42, snapshotWith({ forbidCopy: true, fullScreen: true }));

    queryData.value = undefined;
    queryError.value = new Error('Network Error');

    const wrapper = mount(StudentExamTakingPage);
    await flushPromises();

    expect(wrapper.find('[data-test="offline-reentry-banner"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="fullscreen-guard-overlay"]').exists()).toBe(true);

    const event = dispatchClipboard('copy');
    expect(event.defaultPrevented).toBe(true);
    await vi.waitFor(() => expect(reportBehaviorMock).toHaveBeenCalledTimes(1));
    expect(reportBehaviorMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ body: { eventType: 'COPY_PASTE', eventData: { action: 'copy' } } })
    );
    wrapper.unmount();
  });
});
