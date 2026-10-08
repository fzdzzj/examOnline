/**
 * 考试编辑回填失败三态 + 创建路径零回归用例（fix-frontend-exam-page-error-states，U-7）。
 *
 * 现状缺陷：编辑模式回填 `useQuery`（detail2）只解构 { data: editDetail }，未消费 error；
 * 失败时 watch 不回填、表单停初始默认值、全文件 0 个 Alert——若用户在空表单点「保存修改」，
 * 存在以默认/缺省值覆盖真实现有考试的写风险（update2 path 携带 examId）。
 *
 * 守住：
 * - 编辑模式 + 详情回填失败 → 错误 Alert 显性呈现后端 message，且「保存修改」按钮禁用、
 *   提交被拦截（不落默认值写真实考试）；
 * - 编辑模式 + 回填成功 → 详情回填全部字段、保存可用、update2 链路零回归；
 * - 创建模式（无 examId）→ 标题「新建考试」、不触发详情回填、无回填失败 Alert、
 *   create3 提交链路零回归。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { message } from 'ant-design-vue';

import * as api from '@/api/axios';
import { ApiError } from '@/api/types';
import ExamCreatePage from '@/pages/(dashboard)/teacher/exams/create.page.vue';

// ---- 各 mock 模块需要跨 beforeEach 共享的状态（vi.hoisted 提到 mock 之前） ----
const routerMock = vi.hoisted(() => ({ push: vi.fn(), back: vi.fn() }));
const routeMock = vi.hoisted(() => ({ query: {} as Record<string, unknown> }));
const invalidateQueries = vi.hoisted(() => vi.fn());

vi.mock('vue-router', () => ({
  useRouter: () => routerMock,
  useRoute: () => routeMock,
}));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  create3: vi.fn(),
  page1: vi.fn(),
  page3: vi.fn(),
  detail2: vi.fn(),
  update2: vi.fn(),
}));

/** useQuery mock：真实调用页面 queryFn（真链路口径）；enabled=false（创建模式）不发起详情查询 */
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

/** 详情端点回填 fixture（与 examEditDelete.spec 同源） */
const EXAM_DETAIL = {
  id: 21,
  title: '待编辑的考试',
  description: '期中测验',
  paperId: 5,
  classId: 1,
  startTime: '2026-10-01T09:00:00',
  endTime: '2026-10-01T11:00:00',
  durationMinutes: 90,
  allowLateMinutes: 5,
  status: 0,
  published: 0,
  antiCheatConfig: { switchScreen: true, forbidCopy: true },
};

beforeEach(() => {
  routeMock.query = {};
  routerMock.push.mockClear();
  invalidateQueries.mockClear();
  vi.mocked(api.page1).mockResolvedValue([] as never);
  vi.mocked(api.page3).mockResolvedValue({ list: [] } as never);
  vi.mocked(api.detail2).mockResolvedValue(EXAM_DETAIL as never);
  vi.mocked(api.update2).mockResolvedValue(EXAM_DETAIL as never);
  vi.mocked(api.create3).mockResolvedValue({} as never);
  // antd message 会往 document.body 渲染提示条，spy 掉避免污染 body 断言
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

/** 定位「保存修改」/「创建考试」按钮的原生 button 元素 */
function findSubmitButton(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((b) => b.text() === text);
}

describe('考试编辑回填失败三态（U-7）', () => {
  it('编辑模式回填失败：错误 Alert 显性呈现后端 message，保存按钮禁用且提交被拦截', async () => {
    routeMock.query = { examId: '21' };
    vi.mocked(api.detail2).mockRejectedValue(new ApiError(500, '考试详情加载失败', undefined, 500));
    const wrapper = mount(ExamCreatePage);
    await flushPromises();
    await flushPromises();

    const alert = wrapper.find('[data-test="exam-backfill-error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('考试详情加载失败');

    const saveButton = findSubmitButton(wrapper, '保存修改');
    expect(saveButton).toBeTruthy();
    expect((saveButton?.element as HTMLButtonElement).disabled).toBe(true);

    // 提交被拦截：即便程序化填出「看似合法」的表单，回填失败时也不落默认值写真实考试
    const vm = wrapper.vm as unknown as {
      form: {
        title: string;
        paperId: number | undefined;
        startTime: string | undefined;
        endTime: string | undefined;
        durationMinutes: number;
      };
      handleSubmit: () => Promise<void>;
    };
    vm.form.title = '伪造的合法表单';
    vm.form.paperId = 5;
    vm.form.startTime = '2026-10-01T09:00:00';
    vm.form.endTime = '2026-10-01T11:00:00';
    vm.form.durationMinutes = 90;
    await vm.handleSubmit();
    await flushPromises();
    expect(api.update2).not.toHaveBeenCalled();
    expect(api.create3).not.toHaveBeenCalled();
    expect(routerMock.push).not.toHaveBeenCalled();
  });

  it('编辑模式回填成功：详情回填全部字段，保存可用且 update2 链路零回归', async () => {
    routeMock.query = { examId: '21' };
    const wrapper = mount(ExamCreatePage);
    await flushPromises();
    await flushPromises();

    expect(wrapper.find('[data-test="exam-backfill-error"]').exists()).toBe(false);
    const vm = wrapper.vm as unknown as {
      form: { title: string };
      handleSubmit: () => Promise<void>;
    };
    await vi.waitFor(() => expect(vm.form.title).toBe('待编辑的考试'));

    const saveButton = findSubmitButton(wrapper, '保存修改');
    expect(saveButton).toBeTruthy();
    expect((saveButton?.element as HTMLButtonElement).disabled).toBe(false);

    await vm.handleSubmit();
    await flushPromises();
    expect(api.update2).toHaveBeenCalledTimes(1);
    expect(api.create3).not.toHaveBeenCalled();
    expect(routerMock.push).toHaveBeenCalledWith('/teacher/exams');
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['exams'] });
  });

  it('创建模式零回归：标题「新建考试」、不触发详情回填、无回填失败 Alert、create3 链路不变', async () => {
    const wrapper = mount(ExamCreatePage);
    await flushPromises();
    await flushPromises();

    expect(wrapper.text()).toContain('新建考试');
    expect(api.detail2).not.toHaveBeenCalled();
    expect(wrapper.find('[data-test="exam-backfill-error"]').exists()).toBe(false);

    // 走创建链路：填充合法表单后提交 → create3
    const vm = wrapper.vm as unknown as {
      form: {
        title: string;
        paperId: number | undefined;
        startTime: string | undefined;
        endTime: string | undefined;
        durationMinutes: number;
      };
      handleSubmit: () => Promise<void>;
    };
    vm.form.title = '新考试';
    vm.form.paperId = 1;
    vm.form.startTime = '2026-10-01T09:00:00';
    vm.form.endTime = '2026-10-01T11:00:00';
    vm.form.durationMinutes = 60;
    await vm.handleSubmit();
    await flushPromises();

    expect(api.create3).toHaveBeenCalledTimes(1);
    expect(api.update2).not.toHaveBeenCalled();
  });
});
