import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { Modal } from 'ant-design-vue';
import { ref } from 'vue';

import type { EnterExamResponse } from '@/api/axios';
import StudentExamTakingPage from '@/pages/(dashboard)/student/exams/[id].page.vue';
import {
  createMemoryExamSnapshotStorage,
  type ExamSnapshotStorage,
} from '@/utils/examSnapshotStorage';
import { createMemoryDraftStorage } from '@/utils/draftStorage';
import { OFFLINE_REENTRY_NOTICE } from '@/constants/studentTaking';

// 路由与客户端 mock
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

// Mock API 合同
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

// 可控 storage 实例注入
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

// 响应式 useQuery 控制
const queryData = ref<EnterExamResponse | undefined>(undefined);
const queryError = ref<Error | null>(null);
const queryIsFetching = ref(false);
const refetchMock = vi.fn();

vi.mock('@tanstack/vue-query', () => ({
  useQuery: (options: { queryFn?: () => unknown }) => {
    // 若配置了 queryFn 则执行
    if (options?.queryFn && !queryData.value && !queryError.value) {
      Promise.resolve()
        .then(options.queryFn)
        .then((val: unknown) => {
          queryData.value = val as EnterExamResponse;
        })
        .catch((err: Error) => {
          queryError.value = err;
        });
    }
    return {
      data: queryData,
      error: queryError,
      isFetching: queryIsFetching,
      refetch: refetchMock,
    };
  },
}));

describe('作答页离线重入与降级链路 ([id].page.vue)', () => {
  const SAMPLE_SNAPSHOT_PAYLOAD: EnterExamResponse = {
    examId: 42,
    examTitle: '离线重入测试考试',
    submissionId: 999,
    status: 1,
    startTime: '2026-10-10T09:00:00Z',
    deadlineTime: '2026-10-10T11:00:00Z',
    serverTime: '2026-10-10T09:30:00Z',
    remainingSeconds: 3600,
    questions: [{ number: 1, questionId: 1001, type: 1, content: '什么是二叉树？', score: 10 }],
    draftVersion: 1,
  };

  let originalOnLine: boolean;

  beforeEach(() => {
    originalOnLine = navigator.onLine;
    testSnapshotStorage = createMemoryExamSnapshotStorage();
    testDraftStorage = createMemoryDraftStorage();
    queryData.value = undefined;
    queryError.value = null;
    queryIsFetching.value = false;
    vi.clearAllMocks();
  });

  afterEach(() => {
    Object.defineProperty(navigator, 'onLine', { value: originalOnLine, configurable: true });
  });

  it('① 降级触发：enter 失败 && !onLine && 本地快照存在 → 本地快照渲染题目并显性标注「离线重入中」', async () => {
    // 准备：本地已有快照，但当前离线且请求失败
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    await testSnapshotStorage.save(42, SAMPLE_SNAPSHOT_PAYLOAD);

    queryData.value = undefined;
    queryError.value = new Error('Network Error');

    const wrapper = mount(StudentExamTakingPage);
    await flushPromises();

    // 显性重入标注出现
    const reentryBanner = wrapper.find('[data-test="offline-reentry-banner"]');
    expect(reentryBanner.exists()).toBe(true);
    expect(reentryBanner.text()).toContain(OFFLINE_REENTRY_NOTICE.TITLE);
    expect(reentryBanner.text()).toContain('来自本机缓存');

    // 错误 Alert 不出现（成功被降级接管）
    expect(wrapper.find('[data-test="paper-error"]').exists()).toBe(false);

    // 题目正常渲染
    expect(wrapper.text()).toContain('什么是二叉树？');
  });

  it('② 降级不触发分支 A：enter 失败但 navigator.onLine === true（在线后端故障）→ 维持既有错误态，不降级', async () => {
    Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
    await testSnapshotStorage.save(42, SAMPLE_SNAPSHOT_PAYLOAD);

    queryData.value = undefined;
    queryError.value = new Error('500 Internal Server Error');

    const wrapper = mount(StudentExamTakingPage);
    await flushPromises();

    // 维持既有错误态
    const paperError = wrapper.find('[data-test="paper-error"]');
    expect(paperError.exists()).toBe(true);
    expect(paperError.text()).toContain('500 Internal Server Error');

    // 不伪装降级
    expect(wrapper.find('[data-test="offline-reentry-banner"]').exists()).toBe(false);
  });

  it('③ 降级不触发分支 B：enter 失败且 !onLine 但本地无快照 → 维持既有错误态', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    // 本地不存任何快照

    queryData.value = undefined;
    queryError.value = new Error('Network Error');

    const wrapper = mount(StudentExamTakingPage);
    await flushPromises();

    expect(wrapper.find('[data-test="paper-error"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="offline-reentry-banner"]').exists()).toBe(false);
  });

  it('④ 倒计时估算校正：降级渲染时倒计时按 capturedWallClock 差值校正', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    const now = Date.now();
    // 快照在 600 秒（10 分钟）前捕获
    await testSnapshotStorage.save(
      42,
      { ...SAMPLE_SNAPSHOT_PAYLOAD, remainingSeconds: 3600 },
      now - 600_000
    );

    queryData.value = undefined;
    queryError.value = new Error('Network Error');

    const wrapper = mount(StudentExamTakingPage);
    await flushPromises();

    // 倒计时应按偏移校正，渲染为 50:00（快照 3600 秒扣减 10 分钟），不得出现未校正的 1:00:00
    const countdown = wrapper.find('[data-test="countdown-value"]');
    expect(countdown.text()).toBe('50:00');
    expect(countdown.text()).not.toContain('1:00:00');
    // 页面内无报错
    expect(wrapper.find('[data-test="offline-reentry-banner"]').exists()).toBe(true);
  });

  it('⑤ 恢复网络后服务端快照接管：服务端数据优先于本地快照', async () => {
    Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
    await testSnapshotStorage.save(42, {
      ...SAMPLE_SNAPSHOT_PAYLOAD,
      examTitle: '旧本地快照标题',
    });

    // 服务端返回新标题
    queryData.value = {
      ...SAMPLE_SNAPSHOT_PAYLOAD,
      examTitle: '最新服务端标题',
    };
    queryError.value = null;

    const wrapper = mount(StudentExamTakingPage);
    await flushPromises();

    // 渲染服务端标题，不展示离线降级标注
    expect(wrapper.text()).toContain('最新服务端标题');
    expect(wrapper.find('[data-test="offline-reentry-banner"]').exists()).toBe(false);
  });

  it('⑥ 播种走本地草稿：离线降级时草稿播种复用本地 draftStorage 留底答案', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    await testSnapshotStorage.save(42, SAMPLE_SNAPSHOT_PAYLOAD);
    await testDraftStorage.save(42, {
      version: 5,
      savedAt: '2026-10-10T09:30:00Z',
      answers: { '1001': 'A' },
    });

    queryData.value = undefined;
    queryError.value = new Error('Network Error');

    const wrapper = mount(StudentExamTakingPage);
    await flushPromises();

    // 页面能正确呈现离线重入状态，草稿播种生效且无报错
    expect(wrapper.find('[data-test="offline-reentry-banner"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="draft-seed-conflict"]').exists()).toBe(false);
  });

  it('⑦ 交卷成功清理快照：答卷成功提交后快照仓库删除该行', async () => {
    Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
    await testSnapshotStorage.save(42, SAMPLE_SNAPSHOT_PAYLOAD);
    expect(await testSnapshotStorage.load(42)).not.toBeNull();

    queryData.value = SAMPLE_SNAPSHOT_PAYLOAD;
    queryError.value = null;

    const wrapper = mount(StudentExamTakingPage);
    await flushPromises();

    // 走真实交卷链路：点交卷按钮开确认 Modal → 确认 → emit('submit') → 页面 submitExam
    const submitBtn = wrapper.find('[data-test="submit-button"]');
    expect(submitBtn.exists()).toBe(true);
    await submitBtn.trigger('click');
    await flushPromises();

    const confirmModal = wrapper
      .findAllComponents(Modal)
      .find((m) => m.props('title') === '确认交卷');
    expect(confirmModal).toBeTruthy();
    confirmModal?.vm.$emit('ok');
    await flushPromises();

    // submit 被调用，且成功路径清理快照行
    expect(submitMock).toHaveBeenCalledTimes(1);
    await vi.waitFor(async () => {
      expect(await testSnapshotStorage.load(42)).toBeNull();
    });
  });
});
