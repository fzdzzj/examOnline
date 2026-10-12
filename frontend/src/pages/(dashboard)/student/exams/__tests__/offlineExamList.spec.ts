import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import type { ExamListItem } from '@/api/axios';
import StudentExamsPage from '@/pages/(dashboard)/student/exams/index.page.vue';
import {
  createMemoryExamListCacheStorage,
  type ExamListCacheStorage,
} from '@/utils/examListCacheStorage';
import * as swRegisterModule from '@/utils/swRegister';

// 路由 mock
const mockPush = vi.fn();
let beforeRouteLeaveHandler: ((to: { path: string }) => void) | null = null;

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mockPush }),
  onBeforeRouteLeave: (fn: (to: { path: string }) => void) => {
    beforeRouteLeaveHandler = fn;
  },
}));

// Vuex store mock
const currentUserId = ref<number | undefined>(1001);
vi.mock('vuex', () => ({
  useStore: () => ({
    state: {
      user: { id: currentUserId.value },
    },
  }),
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

// Mock myExams 合同
const myExamsMock = vi.fn();
vi.mock('@/api/axios', async () => {
  const actual = await vi.importActual('@/api/axios');
  return {
    ...actual,
    myExams: (...args: unknown[]) => myExamsMock(...args),
  };
});

// 可控 storage 实例注入
let testListCacheStorage: ExamListCacheStorage;
vi.mock('@/utils/examListCacheStorage', async () => {
  const actual = await vi.importActual('@/utils/examListCacheStorage');
  return {
    ...actual,
    createStudentExamListCacheStorage: () => testListCacheStorage,
  };
});

// 响应式 useQuery 控制
const queryData = ref<ExamListItem[] | undefined>(undefined);
const queryError = ref<Error | null>(null);
const queryIsFetching = ref(false);
const refetchMock = vi.fn();

vi.mock('@tanstack/vue-query', () => ({
  useQuery: (options: { queryFn?: () => unknown }) => {
    if (options?.queryFn && !queryData.value && !queryError.value) {
      Promise.resolve()
        .then(options.queryFn)
        .then((val: unknown) => {
          queryData.value = val as ExamListItem[];
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
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));

describe('学生考试列表离线降级与 SW 注册域 (index.page.vue)', () => {
  const SAMPLE_ITEMS: ExamListItem[] = [
    {
      examId: 10,
      title: '高数期中（已领取）',
      group: 'ONGOING',
      canEnter: true,
      remainingSeconds: 3600,
      startTime: '2026-10-10T09:00:00Z',
      endTime: '2026-10-10T11:00:00Z',
    },
    {
      examId: 11,
      title: '大学英语（待考）',
      group: 'UPCOMING',
      canEnter: false,
      remainingSeconds: undefined,
      startTime: '2026-10-11T09:00:00Z',
      endTime: '2026-10-11T11:00:00Z',
    },
  ];

  let originalOnLine: boolean;

  beforeEach(() => {
    originalOnLine = navigator.onLine;
    testListCacheStorage = createMemoryExamListCacheStorage();
    currentUserId.value = 1001;
    queryData.value = undefined;
    queryError.value = null;
    queryIsFetching.value = false;
    beforeRouteLeaveHandler = null;
    vi.clearAllMocks();
  });

  afterEach(() => {
    Object.defineProperty(navigator, 'onLine', { value: originalOnLine, configurable: true });
  });

  it('① 断网降级渲染缓存列表：error && !onLine && 缓存匹配 → 渲染缓存项并展示「离线缓存」显性标注', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    await testListCacheStorage.save(1001, SAMPLE_ITEMS);

    queryData.value = undefined;
    queryError.value = new Error('Network Error');

    const wrapper = mount(StudentExamsPage);
    await flushPromises();

    // 显性离线缓存标注
    const offlineBanner = wrapper.find('[data-test="offline-list-banner"]');
    expect(offlineBanner.exists()).toBe(true);
    expect(offlineBanner.text()).toContain('离线');
    expect(offlineBanner.text()).toContain('系统收卷');

    // 渲染缓存中的考试条目
    expect(wrapper.text()).toContain('高数期中（已领取）');
    expect(wrapper.find('[data-test="list-error"]').exists()).toBe(false);

    // canEnter 为 true 的项进入按钮可用
    const enterButtons = wrapper.findAll('[data-test="enter-button"]');
    expect(enterButtons.length).toBeGreaterThanOrEqual(1);
    expect(enterButtons[0].attributes('disabled')).toBeUndefined();
  });

  it('② 换号不串缓存：本地缓存用户为 1001，当前登录 1002 断网访问 → 不降级，维持错误态', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    // 为 1001 写入缓存
    await testListCacheStorage.save(1001, SAMPLE_ITEMS);

    // 当前登录切为 1002
    currentUserId.value = 1002;
    queryData.value = undefined;
    queryError.value = new Error('Network Error');

    const wrapper = mount(StudentExamsPage);
    await flushPromises();

    // 维持错误态，不出现缓存内容
    expect(wrapper.find('[data-test="list-error"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="offline-list-banner"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('高数期中（已领取）');
  });

  it('③ 在线失败不伪装降级：navigator.onLine === true 但后端失败 → 维持错误态，不降级', async () => {
    Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
    await testListCacheStorage.save(1001, SAMPLE_ITEMS);

    queryData.value = undefined;
    queryError.value = new Error('500 Internal Error');

    const wrapper = mount(StudentExamsPage);
    await flushPromises();

    expect(wrapper.find('[data-test="list-error"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="offline-list-banner"]').exists()).toBe(false);
  });

  it('④ 在线成功写入缓存：联网请求成功时自动更新本地列表缓存', async () => {
    Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
    queryData.value = SAMPLE_ITEMS;
    queryError.value = null;

    mount(StudentExamsPage);
    await flushPromises();

    // 缓存应已写入
    const loaded = await testListCacheStorage.load(1001);
    expect(loaded).not.toBeNull();
    expect(loaded?.items).toHaveLength(2);
    expect(loaded?.items[0].title).toBe('高数期中（已领取）');
  });

  it('⑥ 会话中途断网刷新激活离线降级：在线成功 → 断网事件 + 刷新失败 → 降级激活（响应式离线判定）', async () => {
    // 在线加载成功（首求值即在线，直查 navigator.onLine 的非响应式实现此刻也会得 true）
    Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
    await testListCacheStorage.save(1001, SAMPLE_ITEMS);
    queryData.value = SAMPLE_ITEMS;
    queryError.value = null;

    const wrapper = mount(StudentExamsPage);
    await flushPromises();

    // 在线成功态：无降级横幅、无错误
    expect(wrapper.find('[data-test="offline-list-banner"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="list-error"]').exists()).toBe(false);

    // 会话中途断网：navigator.onLine=false + window 派发 offline 事件；随后刷新请求失败
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    window.dispatchEvent(new Event('offline'));
    queryData.value = undefined;
    queryError.value = new Error('Network Error');
    await flushPromises();

    // 离线降级激活：缓存标注 + 降级横幅在场、错误 Alert 不在场（降级优先于报错）、缓存行渲染
    expect(wrapper.find('[data-test="offline-list-banner"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="offline-cache-badge"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="list-error"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('高数期中（已领取）');
  });

  it('⑦ 离线禁用预约与取消预约：降级态两按钮禁用、进入按钮不受影响，恢复在线复原', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });
    const offlineItems: ExamListItem[] = [
      {
        ...SAMPLE_ITEMS[1],
        examId: 21,
        title: '需预约未预约',
        group: 'UPCOMING',
        canEnter: false,
        reservationRequired: true,
        reserved: false,
      },
      {
        ...SAMPLE_ITEMS[1],
        examId: 22,
        title: '需预约已预约',
        group: 'UPCOMING',
        canEnter: true,
        reservationRequired: true,
        reserved: true,
      },
    ];
    await testListCacheStorage.save(1001, offlineItems);
    queryData.value = undefined;
    queryError.value = new Error('Network Error');

    const wrapper = mount(StudentExamsPage);
    await flushPromises();

    // 离线降级态：缓存行渲染，预约/取消入口呈禁用态
    expect(wrapper.text()).toContain('需预约未预约');
    expect(wrapper.find('[data-test="reserve-button"]').attributes('disabled')).toBeDefined();
    expect(
      wrapper.find('[data-test="cancel-reservation-button"]').attributes('disabled')
    ).toBeDefined();

    // 「进入」按钮不因离线禁用：仍只认后端 canEnter（红线）
    const enterButtons = wrapper.findAll('[data-test="enter-button"]');
    expect(enterButtons[0].attributes('disabled')).toBeDefined();
    expect(enterButtons[1].attributes('disabled')).toBeUndefined();

    // 网络恢复在线 + 刷新成功 → 预约/取消入口自动复原
    Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
    window.dispatchEvent(new Event('online'));
    queryData.value = offlineItems;
    queryError.value = null;
    await flushPromises();

    expect(wrapper.find('[data-test="reserve-button"]').attributes('disabled')).toBeUndefined();
    expect(
      wrapper.find('[data-test="cancel-reservation-button"]').attributes('disabled')
    ).toBeUndefined();
  });

  it('⑤ SW 学生考试域化生命周期：挂载注册 SW，离开学生考试域注销，域内跳转不注销', async () => {
    const registerSpy = vi
      .spyOn(swRegisterModule, 'registerExamServiceWorker')
      .mockResolvedValue(null);
    const unregisterSpy = vi
      .spyOn(swRegisterModule, 'unregisterExamServiceWorker')
      .mockResolvedValue(false);

    mount(StudentExamsPage);
    await flushPromises();

    // 挂载时注册 SW
    expect(registerSpy).toHaveBeenCalled();

    expect(beforeRouteLeaveHandler).not.toBeNull();
    // 域内跳转（列表 -> 作答页）：不注销
    beforeRouteLeaveHandler!({ path: '/student/exams/10' });
    expect(unregisterSpy).not.toHaveBeenCalled();

    // 离开学生考试域（列表 -> 个人成绩）：注销
    beforeRouteLeaveHandler!({ path: '/student/scores' });
    expect(unregisterSpy).toHaveBeenCalledTimes(1);
  });
});
