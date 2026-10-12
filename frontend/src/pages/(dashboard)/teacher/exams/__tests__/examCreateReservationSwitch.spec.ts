/**
 * 教师建考表单「需预约」开关（add-exam-reservation，frontend spec-delta 开关回填）。
 *
 * 断言直断形态：命中真实 `data-test="reservation-required-switch"`，禁 if/exists 守卫与 || 双通。
 * 校验三件事：
 * - 创建模式开关默认关闭，`reservationRequired=true` 由表单状态写入 create3 请求体；
 * - 编辑模式详情回填时开关值随详情回填（true → 开、false → 关）；
 * - 请求体始终携带 reservationRequired 布尔（不落 undefined）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { message } from 'ant-design-vue';

import * as api from '@/api/axios';
import ExamCreatePage from '@/pages/(dashboard)/teacher/exams/create.page.vue';

const routerMock = vi.hoisted(() => ({ push: vi.fn(), back: vi.fn() }));
const routeMock = vi.hoisted(() => ({ query: {} as Record<string, unknown> }));
const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => routeMock }));
vi.mock('@/api/queryClient', () => ({ queryClient: { invalidateQueries } }));
vi.mock('@/api/apiClient', () => ({ client: { __test: 'mock' }, unwrap: async (v: unknown) => v }));
vi.mock('@/api/axios', () => ({
  create3: vi.fn(),
  page1: vi.fn(),
  page3: vi.fn(),
  detail2: vi.fn(),
  update2: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey?: unknown; queryFn?: () => unknown; enabled?: unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      const enabled = options.enabled === undefined || unref(options.enabled) !== false;
      if (enabled && options.queryFn) {
        Promise.resolve()
          .then(options.queryFn as () => unknown)
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

function baseDetail(overrides: Record<string, unknown> = {}) {
  return {
    id: 9,
    title: '考试',
    paperId: 3,
    classId: 1,
    startTime: '2026-10-01T09:00:00',
    endTime: '2026-10-01T11:00:00',
    durationMinutes: 60,
    allowLateMinutes: 0,
    status: 0,
    published: 0,
    antiCheatConfig: { switchScreen: true, forbidCopy: false },
    ...overrides,
  };
}

beforeEach(() => {
  routeMock.query = {};
  routerMock.push.mockClear();
  invalidateQueries.mockClear();
  vi.mocked(api.page1).mockResolvedValue([] as never);
  vi.mocked(api.page3).mockResolvedValue({ list: [] } as never);
  vi.mocked(api.detail2).mockResolvedValue(baseDetail() as never);
  vi.mocked(api.update2).mockResolvedValue(baseDetail() as never);
  vi.mocked(api.create3).mockResolvedValue({} as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

/** 命中真实 data-test 的开关存在性（直断形态：断言元素在场）。 */
function switchShown(wrapper: ReturnType<typeof mount>): boolean {
  return wrapper.find('[data-test="reservation-required-switch"]').exists();
}

/** 直断表单态（Switch 回填真实写入 form.reservationRequired）。 */
function formReservationRequired(wrapper: ReturnType<typeof mount>): unknown {
  return (wrapper.vm as unknown as { form: { reservationRequired: unknown } }).form
    .reservationRequired;
}

/** 设好必填项后触发创建提交（创建模式走 create3）。 */
async function submitCreate(wrapper: ReturnType<typeof mount>): Promise<void> {
  const vm = wrapper.vm as unknown as {
    handleSubmit: () => Promise<void>;
    form: Record<string, unknown>;
  };
  vm.form.paperId = 3;
  vm.form.title = '需预约考试';
  vm.form.startTime = '2026-10-01T09:00:00';
  vm.form.endTime = '2026-10-01T11:00:00';
  vm.form.durationMinutes = 60;
  await vm.handleSubmit();
}

describe('教师建考「需预约」开关', () => {
  it('创建模式默认关闭，reservationRequired 以表单状态写入 create3 请求体', async () => {
    const wrapper = mount(ExamCreatePage, { attachTo: document.body });
    await flushPromises();
    expect(switchShown(wrapper)).toBe(true);
    expect(formReservationRequired(wrapper)).toBe(false);

    const vm = wrapper.vm as unknown as { form: { reservationRequired: boolean } };
    vm.form.reservationRequired = true;
    await submitCreate(wrapper);
    await flushPromises();

    expect(api.create3).toHaveBeenCalledTimes(1);
    const body = vi.mocked(api.create3).mock.calls[0][0] as {
      body: { reservationRequired: boolean };
    };
    expect(body.body.reservationRequired).toBe(true);
  });

  it('编辑模式回填：详情 reservationRequired=1 时开关为开', async () => {
    vi.mocked(api.detail2).mockResolvedValue(baseDetail({ reservationRequired: 1 }) as never);
    routeMock.query = { examId: '9' };
    const wrapper = mount(ExamCreatePage, { attachTo: document.body });
    await flushPromises();
    expect(formReservationRequired(wrapper)).toBe(true);
  });

  it('编辑模式回填：详情 reservationRequired=0 时开关为关', async () => {
    vi.mocked(api.detail2).mockResolvedValue(baseDetail({ reservationRequired: 0 }) as never);
    routeMock.query = { examId: '9' };
    const wrapper = mount(ExamCreatePage, { attachTo: document.body });
    await flushPromises();
    expect(formReservationRequired(wrapper)).toBe(false);
  });

  it('请求体始终携带 reservationRequired 布尔（不落 undefined）', async () => {
    const wrapper = mount(ExamCreatePage, { attachTo: document.body });
    await flushPromises();
    await submitCreate(wrapper);
    await flushPromises();
    const calls = vi.mocked(api.create3).mock.calls;
    expect(calls.length).toBeGreaterThanOrEqual(1);
    const body = calls[0][0] as { body: { reservationRequired: boolean } };
    expect(typeof body.body.reservationRequired).toBe('boolean');
  });
});
