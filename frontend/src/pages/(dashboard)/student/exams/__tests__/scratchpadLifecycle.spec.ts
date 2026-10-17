/**
 * 草稿板生命周期接线（[id].page.vue，add-exam-scratchpad-canvas 阶段 2 · 先红后绿）。
 *
 * 页面级三条判据（specs/exam-taking/spec-delta.md 场景）：
 * 1. 作答态（快照在手、未封闭、非结果视图）才出现「📝 草稿板」悬浮开关，可展开/收起；
 * 2. 交卷成功 → `exam_scratchpad_${examId}` 本地草稿被自动移除（自毁）；
 * 3. 后端已封闭（重进 status=2 答卷）→ 开关不出现 + 本地草稿同样清理。
 *
 * 挂载骨架复用 deviceReadinessGate.spec.ts（同一页面的门控 mock 套件）：
 * 路由 / apiClient / api/axios / 双本地存储 / 自检 hook / vue-query 全部替身。
 */
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { computed, reactive, ref, watch } from 'vue';

import type { EnterExamResponse } from '@/api/axios';
import SubmitExamPanel from '@/components/student/SubmitExamPanel.vue';
import StudentExamTakingPage from '@/pages/(dashboard)/student/exams/[id].page.vue';
import { READINESS_ITEM_LABELS, type DeviceReadinessReport } from '@/hooks/useDeviceReadiness';
import { createMemoryDraftStorage } from '@/utils/draftStorage';
import { createMemoryExamSnapshotStorage } from '@/utils/examSnapshotStorage';
import { scratchpadStorageKey, serializeScratchpadDocument } from '@/utils/scratchpad';

const mockPush = vi.fn();
const routeState = reactive({ params: { id: '42' } });
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mockPush }),
  useRoute: () => routeState,
  onBeforeRouteLeave: vi.fn(),
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

const enterMock = vi.fn();
const reportBehaviorMock = vi.fn().mockResolvedValue({});
const saveDraftMock = vi.fn().mockResolvedValue({ version: 1, savedAt: '2026-10-18T10:00:00Z' });
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

const firedExamIds: number[] = [];
// reactive Map：dataByExam.set 必须能让页面的 data computed 失效重算
// （普通 Map 的 .set 不触发响应式，快照永远到不了页面——这正是本骨架要断言的路径）。
const dataByExam = reactive(new Map<number, EnterExamResponse>());
const errorByExam = reactive(new Map<number, Error>());
const latestExamId = ref(0);
const queryIsFetching = ref(false);
const refetchMock = vi.fn();

vi.mock('@tanstack/vue-query', () => ({
  useQuery: (options: {
    value?: { queryKey: readonly unknown[]; enabled: boolean; queryFn: () => unknown };
  }) => {
    const evaluate = () => options?.value;
    const fire = (o: {
      queryKey: readonly unknown[];
      enabled: boolean;
      queryFn: () => unknown;
    }) => {
      const examId = (o.queryKey as readonly unknown[])[2] as number;
      latestExamId.value = examId;
      if (o.enabled === false) return;
      if (dataByExam.has(examId) || errorByExam.has(examId)) return;
      firedExamIds.push(examId);
      Promise.resolve()
        .then(o.queryFn)
        .then((val: unknown) => {
          dataByExam.set(examId, val as EnterExamResponse);
        })
        .catch((err: Error) => {
          errorByExam.set(examId, err);
        });
    };
    const initial = evaluate();
    if (initial) fire(initial);
    watch(evaluate, (o) => {
      if (o) fire(o);
    });
    const data = computed(() => dataByExam.get(latestExamId.value));
    const error = computed(() => errorByExam.get(latestExamId.value) ?? null);
    return { data, error, isFetching: queryIsFetching, refetch: refetchMock };
  },
}));

const readinessRunMock = vi.fn();
vi.mock('@/hooks/useDeviceReadiness', async () => {
  const actual = await vi.importActual('@/hooks/useDeviceReadiness');
  return {
    ...actual,
    useDeviceReadiness: () => ({ run: readinessRunMock }),
  };
});

const SNAPSHOT: EnterExamResponse = {
  examId: 42,
  examTitle: '草稿板生命周期测试考试',
  submissionId: 999,
  status: 1,
  remainingSeconds: 3600,
  draftVersion: 1,
  questions: [{ number: 1, questionId: 1001, type: 1, content: '草稿板测试题目', score: 10 }],
};

/** 后端已封闭：status=2 + submissionId → 重进直接进结果视图。 */
const CLOSED_SNAPSHOT: EnterExamResponse = { ...SNAPSHOT, status: 2, questions: [] };

function makeReport(overall: DeviceReadinessReport['overall']): DeviceReadinessReport {
  return {
    completed: true,
    overall,
    items: [
      { key: 'rtt', level: 'pass', label: READINESS_ITEM_LABELS.rtt, detail: '网络时延正常' },
      {
        key: 'viewport',
        level: 'pass',
        label: READINESS_ITEM_LABELS.viewport,
        detail: '视口满足建议',
      },
      {
        key: 'fullscreen',
        level: 'pass',
        label: READINESS_ITEM_LABELS.fullscreen,
        detail: '支持全屏',
      },
      {
        key: 'compatibility',
        level: overall === 'critical' ? 'critical' : 'pass',
        label: READINESS_ITEM_LABELS.compatibility,
        detail: '兼容',
      },
    ],
  };
}

const PASS_REPORT = makeReport('pass');

let originalOnLine: boolean;
const mountedWrappers: Array<ReturnType<typeof mount>> = [];

beforeEach(() => {
  originalOnLine = navigator.onLine;
  sessionStorage.clear();
  routeState.params.id = '42';
  testSnapshotStorage = createMemoryExamSnapshotStorage();
  testDraftStorage = createMemoryDraftStorage();
  dataByExam.clear();
  errorByExam.clear();
  latestExamId.value = 0;
  queryIsFetching.value = false;
  firedExamIds.length = 0;
  enterMock.mockResolvedValue(SNAPSHOT);
  submitMock.mockResolvedValue({ submissionId: 999, examId: 42, status: 2 });
  readinessRunMock.mockReset();
  vi.clearAllMocks();
});

afterEach(() => {
  Object.defineProperty(navigator, 'onLine', { value: originalOnLine, configurable: true });
  while (mountedWrappers.length) {
    mountedWrappers.pop()?.unmount();
  }
  document.body.innerHTML = '';
});

async function mountPage() {
  const wrapper = mount(StudentExamTakingPage);
  mountedWrappers.push(wrapper);
  await flushPromises();
  return wrapper;
}

/** 正常放行进入作答态（自检通过 + 点「进入考试」+ 快照到达）。 */
async function enterTakingState(wrapper: ReturnType<typeof mount>) {
  await wrapper.find('[data-test="readiness-enter"]').trigger('click');
  await flushPromises();
}

describe('作答态的悬浮开关与面板', () => {
  it('放行进入作答态：出现「草稿板」开关，点击展开面板与画布，收起后回到开关', async () => {
    readinessRunMock.mockResolvedValue(PASS_REPORT);
    const wrapper = await mountPage();
    await enterTakingState(wrapper);
    expect(firedExamIds).toEqual([42]);

    expect(wrapper.find('[data-test="scratchpad-toggle"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="scratchpad-panel"]').exists()).toBe(false);

    await wrapper.find('[data-test="scratchpad-toggle"]').trigger('click');
    expect(wrapper.find('[data-test="scratchpad-panel"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="scratchpad-canvas"]').exists()).toBe(true);

    await wrapper.find('[data-test="scratchpad-minimize"]').trigger('click');
    expect(wrapper.find('[data-test="scratchpad-panel"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="scratchpad-toggle"]').exists()).toBe(true);
  });

  it('设备就绪门控期间（快照未到）开关不出现', async () => {
    readinessRunMock.mockResolvedValue(PASS_REPORT);
    const wrapper = await mountPage();
    expect(firedExamIds).toHaveLength(0);
    expect(wrapper.find('[data-test="scratchpad-toggle"]').exists()).toBe(false);
  });
});

describe('交卷后草稿自毁', () => {
  it('手动交卷成功：exam_scratchpad_42 被移除，结果卡接管、开关消失', async () => {
    sessionStorage.setItem(
      scratchpadStorageKey(42),
      serializeScratchpadDocument({
        version: 1,
        strokes: [{ mode: 'pen', color: '#222222', width: 4, points: [{ x: 1, y: 1 }] }],
      })
    );
    readinessRunMock.mockResolvedValue(PASS_REPORT);
    const wrapper = await mountPage();
    await enterTakingState(wrapper);
    expect(sessionStorage.getItem(scratchpadStorageKey(42))).not.toBeNull();

    await wrapper.findComponent(SubmitExamPanel).vm.$emit('submit');
    await flushPromises();

    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="scratchpad-toggle"]').exists()).toBe(false);
    expect(sessionStorage.getItem(scratchpadStorageKey(42))).toBeNull();
  });

  it('后端已封闭（重进 status=2）：开关不出现，本地草稿同样清理', async () => {
    sessionStorage.setItem(
      scratchpadStorageKey(42),
      serializeScratchpadDocument({
        version: 1,
        strokes: [{ mode: 'pen', color: '#ff4d4f', width: 8, points: [{ x: 2, y: 2 }] }],
      })
    );
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    enterMock.mockResolvedValue(CLOSED_SNAPSHOT);

    const wrapper = await mountPage();
    await flushPromises();

    expect(wrapper.find('[data-test="result-card"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="scratchpad-toggle"]').exists()).toBe(false);
    expect(sessionStorage.getItem(scratchpadStorageKey(42))).toBeNull();
  });
});
