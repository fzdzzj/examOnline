/**
 * 交卷「跳转首道未答题」端到端接线（[id].page.vue，polish 变更 spec-delta）。
 *
 * 核心红线：跳转口径**必须**复用 `navStatesOf` 状态数组的第一个未答项，绝不另算一套
 * 下标——这是变异 m2（跳转另算口径/跳错题）的击红锚点。
 *
 * 场景：3 题试卷，第 1 题未答、第 2 题已答、第 3 题未答 → 点「跳转首道未答题」
 * 应落在第 1 题（第一个未答项），而不是第 3 题。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import type { EnterExamResponse } from '@/api/axios';
import StudentExamTakingPage from '@/pages/(dashboard)/student/exams/[id].page.vue';
import { createMemoryDraftStorage } from '@/utils/draftStorage';
import { createMemoryExamSnapshotStorage } from '@/utils/examSnapshotStorage';

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

let testSnapshotStorage: ReturnType<typeof createMemoryExamSnapshotStorage>;
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
const refetchMock = vi.fn();

vi.mock('@tanstack/vue-query', () => ({
  useQuery: (options: { queryFn?: () => unknown }) => {
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

const SNAPSHOT: EnterExamResponse = {
  examId: 42,
  examTitle: '跳转测试考试',
  submissionId: 999,
  status: 1,
  remainingSeconds: 3600,
  draftVersion: 1,
  questions: [
    { number: 1, questionId: 101, type: 1, content: '第一题未答', score: 5, choices: ['甲', '乙'] },
    { number: 2, questionId: 102, type: 1, content: '第二题已答', score: 5, choices: ['甲', '乙'] },
    { number: 3, questionId: 103, type: 1, content: '第三题未答', score: 5, choices: ['甲', '乙'] },
  ],
};

describe('交卷跳转首道未答题（[id].page.vue 接线）', () => {
  beforeEach(() => {
    testSnapshotStorage = createMemoryExamSnapshotStorage();
    testDraftStorage = createMemoryDraftStorage();
    queryData.value = undefined;
    queryError.value = null;
    queryIsFetching.value = false;
    vi.clearAllMocks();
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('点「跳转首道未答题」落在 navStatesOf 的第一个未答项（第 1 题），不跳第 3 题', async () => {
    // 服务端快照：第 2 题已有答案（1002），第 1/3 题未答
    const draft = createMemoryDraftStorage();
    draft.save(42, { version: 1, savedAt: null, answers: { '102': 'A' }, marked: [] });
    testDraftStorage = draft;
    queryData.value = SNAPSHOT;

    const wrapper = mount(StudentExamTakingPage, { attachTo: document.body });
    await flushPromises();

    // 打开交卷确认弹窗
    await wrapper.find('[data-test="submit-button"]').trigger('click');
    await wrapper.vm.$nextTick();

    const jumpBtn = document.body.querySelector('[data-test="jump-unanswered"]');
    expect(jumpBtn).not.toBeNull();
    await (jumpBtn as HTMLElement).click();
    await wrapper.vm.$nextTick();

    // 当前题切到第一个未答项（第 1 题），而不是第 3 题
    const current = wrapper.find('[data-test="question-content"]').text();
    expect(current).toContain('第一题未答');
    wrapper.unmount();
  });
});
