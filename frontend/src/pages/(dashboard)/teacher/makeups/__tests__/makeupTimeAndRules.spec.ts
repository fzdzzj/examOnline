/**
 * 补考创建时间控件与 Form rules 校验用例（fix-frontend-makeup-time-and-landing，U-4）。
 *
 * 断言口径：
 * 1. 时间控件渲染为 DatePicker 类组件，旧 placeholder 自由文本 Input 不存在；
 * 2. 格式等价性：构造 dayjs 选择 -> 提交转换 -> 输出与旧 toIsoLocalDateTime 口径逐字严格等价（带字面量 T）；
 * 3. rules 校验：开始/结束时间必填缺失、起止时间倒置（结束早于等于开始）被拦截，不发起创建请求；
 * 4. 词法护栏：源码不含自由文本 Input 时间绑定与提交时 warning 双轨分支，包含 DatePicker 与 rules。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import dayjs from 'dayjs';

import * as api from '@/api/axios';
import { toIsoLocalDateTime } from '@/utils/dateTime';
import TeacherMakeupsPage from '@/pages/(dashboard)/teacher/makeups/index.page.vue';
import makeupsSource from '@/pages/(dashboard)/teacher/makeups/index.page.vue?raw';

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  createMakeup: vi.fn(),
  makeupEligible: vi.fn(),
  makeupFinalScore: vi.fn(),
  page2: vi.fn(),
}));

/** useQuery mock：提供主考考试与准入学生候选，使测试能顺利进入创建补考表单校验链路 */
vi.mock('@tanstack/vue-query', async () => {
  const { ref } = await import('vue');
  return {
    useQuery: (options: { queryKey?: unknown[] }) => {
      const key = String(options.queryKey?.[0] ?? '');
      if (key === 'exams') {
        return {
          data: ref([{ id: 101, title: '高一期末主考' }]),
          error: ref(null),
          isFetching: ref(false),
          refetch: vi.fn(),
        };
      }
      if (key === 'makeup-eligible') {
        return {
          data: ref([
            { studentId: 1, studentName: '学生甲' },
            { studentId: 2, studentName: '学生乙' },
          ]),
          error: ref(null),
          isFetching: ref(false),
          refetch: vi.fn(),
        };
      }
      return {
        data: ref(undefined),
        error: ref(null),
        isFetching: ref(false),
        refetch: vi.fn(),
      };
    },
  };
});

const mockedCreateMakeup = vi.mocked(api.createMakeup);

interface MakeupsVm {
  selectedExamId?: number;
  selectedIds: number[];
  form: {
    title: string;
    startTime: dayjs.Dayjs | undefined;
    endTime: dayjs.Dayjs | undefined;
    durationMinutes: number;
    allowLateMinutes: number;
    makeupScoreRule: string;
  };
  onCreate: () => Promise<void>;
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedCreateMakeup.mockResolvedValue({
    code: 200,
    message: 'OK',
    data: {
      examId: 202,
      title: '高一期末补考',
      parentExamId: 101,
      makeupScoreRule: 'LATEST',
      candidateCount: 1,
    },
  } as never);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('补考创建时间控件与 rules 校验（U-4）', () => {
  it('控件渲染：时间字段渲染为 DatePicker 组件且旧自由文本 placeholder 不存在', async () => {
    const wrapper = mount(TeacherMakeupsPage);
    await flushPromises();

    // 旧自由文本 Input placeholder 不应再存在
    expect(wrapper.find('input[placeholder="2026-09-20 09:00:00"]').exists()).toBe(false);
    expect(wrapper.find('input[placeholder="2026-09-20 11:00:00"]').exists()).toBe(false);

    // DatePicker 类组件（或 antd 的 .ant-picker）应当存在
    const pickers = wrapper.findAll('.ant-picker');
    expect(pickers.length).toBeGreaterThanOrEqual(2);
  });

  it('格式等价性护栏：dayjs 构造转换输出与旧 toIsoLocalDateTime 逐字严格比对', () => {
    const testCases = [
      '2026-09-20 09:00:00',
      '2026-09-20 11:00:00',
      '2026-12-31 23:59:59',
      '2027-01-01 00:00:00',
      '2026-05-04 08:30:15',
    ];

    for (const timeStr of testCases) {
      const d = dayjs(timeStr);
      const converted = d.format('YYYY-MM-DDTHH:mm:ss');
      const oracle = toIsoLocalDateTime(timeStr);
      expect(converted).toBe(oracle);
      expect(converted).toContain('T');
    }
  });

  it('表单提交：合法语境下 dayjs 转换为等价带 T 字符串提交至后端', async () => {
    const wrapper = mount(TeacherMakeupsPage);
    await flushPromises();

    // 选中主考考试与准入学生
    const vm = wrapper.vm as unknown as MakeupsVm;
    vm.selectedExamId = 101;
    vm.selectedIds = [1];
    vm.form.title = '补考测试场';
    vm.form.startTime = dayjs('2026-09-20 09:00:00');
    vm.form.endTime = dayjs('2026-09-20 11:00:00');
    vm.form.durationMinutes = 120;
    await flushPromises();

    await vm.onCreate();
    await flushPromises();

    expect(mockedCreateMakeup).toHaveBeenCalledTimes(1);
    expect(mockedCreateMakeup).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { id: 101 },
        body: expect.objectContaining({
          title: '补考测试场',
          startTime: toIsoLocalDateTime('2026-09-20 09:00:00'),
          endTime: toIsoLocalDateTime('2026-09-20 11:00:00'),
          durationMinutes: 120,
          studentIds: [1],
        }),
      })
    );
  });

  it('rules 校验拦截：起止时间倒置（结束时间早于等于开始时间）被拦截，不发起创建请求', async () => {
    const wrapper = mount(TeacherMakeupsPage);
    await flushPromises();

    const vm = wrapper.vm as unknown as MakeupsVm;
    vm.selectedExamId = 101;
    vm.selectedIds = [1];
    vm.form.startTime = dayjs('2026-09-20 11:00:00');
    vm.form.endTime = dayjs('2026-09-20 09:00:00'); // 倒置
    await flushPromises();

    await vm.onCreate();
    await flushPromises();

    // 必须被 rules 拦截，绝不调用 createMakeup
    expect(mockedCreateMakeup).not.toHaveBeenCalled();
  });

  it('rules 校验拦截：时间缺失被拦截，不发起创建请求', async () => {
    const wrapper = mount(TeacherMakeupsPage);
    await flushPromises();

    const vm = wrapper.vm as unknown as MakeupsVm;
    vm.selectedExamId = 101;
    vm.selectedIds = [1];
    vm.form.startTime = undefined;
    vm.form.endTime = undefined;
    await flushPromises();

    await vm.onCreate();
    await flushPromises();

    expect(mockedCreateMakeup).not.toHaveBeenCalled();
  });

  it('词法护栏：源码使用 DatePicker 与 Form rules，移除自由文本 Input 与 warning 分支', () => {
    expect(makeupsSource).toContain('DatePicker');
    expect(makeupsSource).not.toContain('placeholder="2026-09-20 09:00:00"');
    expect(makeupsSource).not.toContain('开始 / 结束时间无法解析，请按 2026-09-20 09:00:00 填写');
    expect(makeupsSource).toMatch(/:rules\s*=/);
  });
});
