/**
 * 作答页设备就绪门控接线（[id].page.vue，add-pre-exam-device-readiness-check 阶段 2 · 先红后绿）。
 *
 * 三条门控红线（变异击红锚点）：
 * 1. enabled 门控：自检未通过前绝不起表（enter 零调用）；
 * 2. 离线整场豁免：navigator.onLine === false 时自检整场跳过直放行；
 * 3. sessionStorage 会话级留存：本场已留存 → 刷新/返回再进不重复自检、不重复门控；
 *    路由复用切换 examId → 视为新考试重新门控。
 *
 * useQuery 按 enabled 语义 mock：页面传的是 computed getter（vue-query 的响应式 options
 * 形态），mock 里 watch 该 getter——enabled=false 时绝不执行 queryFn（必须能证明
 * 「没放行就不起表」）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { computed, reactive, ref, watch } from 'vue';

import type { EnterExamResponse } from '@/api/axios';
import StudentExamTakingPage from '@/pages/(dashboard)/student/exams/[id].page.vue';
import {
  loadReadinessReport,
  readinessStorageKey,
  READINESS_ITEM_LABELS,
  type DeviceReadinessReport,
} from '@/hooks/useDeviceReadiness';
import { createMemoryExamSnapshotStorage } from '@/utils/examSnapshotStorage';
import { createMemoryDraftStorage } from '@/utils/draftStorage';

const mockPush = vi.fn();
/** 响应式路由：`/student/exams/:id` 组件复用切换 examId 时，页面 examId computed 必须随之变化。 */
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

/** 响应式 useQuery mock：尊重 enabled，并按 examId 隔离数据（切换考试不串数据）。 */
const firedExamIds: number[] = [];
const dataByExam = new Map<number, EnterExamResponse>();
const errorByExam = new Map<number, Error>();
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

/** 可控的自检 run（页面仅依赖 run 返回的 report，不碰真实浏览器 API）。 */
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
  examTitle: '门控测试考试',
  submissionId: 999,
  status: 1,
  remainingSeconds: 3600,
  draftVersion: 1,
  questions: [{ number: 1, questionId: 1001, type: 1, content: '门控后题目', score: 10 }],
};

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
const CRITICAL_REPORT = makeReport('critical');

let originalOnLine: boolean;

/** 已挂载但未卸载的 wrapper：跨测试泄漏的响应式 watcher 会响应后续测试的路由变更（组件复用），必须收尾卸载。 */
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

describe('enabled 门控：自检未通过前不起表', () => {
  it('在线且无留存：挂载即呈现自检面板，enter 零调用；检查完成后仍需点「进入考试」才起表', async () => {
    readinessRunMock.mockResolvedValue(PASS_REPORT);
    const wrapper = await mountPage();

    // 自检面板在，enter 未发起
    expect(wrapper.find('[data-test="readiness-panel"]').exists()).toBe(true);
    expect(firedExamIds).toHaveLength(0);
    expect(enterMock).not.toHaveBeenCalled();

    // 检查完成（report 呈现）仍未起表——按钮是知情门控，不是摆设
    await flushPromises();
    expect(firedExamIds).toHaveLength(0);

    // 点「进入考试」才起表（examId 42）
    await wrapper.find('[data-test="readiness-enter"]').trigger('click');
    await flushPromises();
    expect(firedExamIds).toEqual([42]);
  });

  it('门控期间题目区不渲染（快照未到，起表请求被 enabled 拦截）', async () => {
    readinessRunMock.mockResolvedValue(PASS_REPORT);
    const wrapper = await mountPage();
    expect(wrapper.text()).not.toContain('门控后题目');
  });
});

describe('强警示二次确认（页面级接线）', () => {
  it('critical 报告：点「仍要进入考试」→ 先二次确认 → 确认后才起表', async () => {
    readinessRunMock.mockResolvedValue(CRITICAL_REPORT);
    const wrapper = await mountPage();
    await flushPromises();

    await wrapper.find('[data-test="readiness-enter-critical"]').trigger('click');
    expect(wrapper.find('[data-test="critical-confirm"]').exists()).toBe(true);
    expect(firedExamIds).toHaveLength(0);

    await wrapper.find('[data-test="confirm-still-enter"]').trigger('click');
    await flushPromises();
    expect(firedExamIds).toEqual([42]);
  });
});

describe('离线重入整场豁免', () => {
  it('navigator.onLine === false：自检面板不出现、run 零调用、enter 立即起表（直放行）', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    const wrapper = await mountPage();

    expect(wrapper.find('[data-test="readiness-panel"]').exists()).toBe(false);
    expect(readinessRunMock).not.toHaveBeenCalled();
    expect(firedExamIds).toEqual([42]);
  });
});

describe('sessionStorage 会话级留存', () => {
  it('检查完成即留存；刷新重挂载不再重复自检，直接起表', async () => {
    readinessRunMock.mockResolvedValue(PASS_REPORT);
    const first = await mountPage();
    await flushPromises();
    expect(readinessRunMock).toHaveBeenCalledTimes(1);
    expect(first.find('[data-test="readiness-enter"]').exists()).toBe(true);
    // 留存键已写入
    expect(loadReadinessReport(sessionStorage, 42)).toEqual(PASS_REPORT);

    // 模拟刷新：重新挂载（sessionStorage 保留）
    first.unmount();
    readinessRunMock.mockReset();
    const second = await mountPage();
    expect(second.find('[data-test="readiness-panel"]').exists()).toBe(false);
    expect(readinessRunMock).not.toHaveBeenCalled();
    expect(firedExamIds).toEqual([42]);
  });

  it('路由复用切换 examId：视为新考试重新门控（新考试无留存 → 面板重现、不起表）', async () => {
    readinessRunMock.mockResolvedValue(PASS_REPORT);
    const first = await mountPage();
    await flushPromises();
    await first.find('[data-test="readiness-enter"]').trigger('click');
    await flushPromises();
    expect(firedExamIds).toEqual([42]);

    // 复用组件切换到另一场考试（无留存）
    routeState.params.id = '99';
    await flushPromises();
    expect(first.find('[data-test="readiness-panel"]').exists()).toBe(true);
    // 99 号考试未放行 → 不起表
    expect(firedExamIds).toEqual([42]);

    // 完成 99 的自检并放行 → 起表 99
    await flushPromises();
    await first.find('[data-test="readiness-enter"]').trigger('click');
    await flushPromises();
    expect(firedExamIds).toEqual([42, 99]);
  });
});

describe('异常兜底放行（页面级）', () => {
  it('run 返回全部警示的兜底报告 → 无 critical 无二次确认，放行起表', async () => {
    readinessRunMock.mockResolvedValue(makeReport('warning'));
    const wrapper = await mountPage();
    await flushPromises();
    expect(wrapper.find('[data-test="readiness-enter"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-test="readiness-enter-critical"]').exists()).toBe(false);
    await wrapper.find('[data-test="readiness-enter"]').trigger('click');
    await flushPromises();
    expect(firedExamIds).toEqual([42]);
  });
});

describe('留存键格式（页面写入与读取同源）', () => {
  it('readinessStorageKey 与页面读取路径一致', () => {
    expect(readinessStorageKey(42)).toBe('deviceReadiness:report:42');
  });
});
