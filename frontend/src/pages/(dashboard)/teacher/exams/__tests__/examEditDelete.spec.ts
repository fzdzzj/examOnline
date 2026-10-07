/**
 * 「编辑 / 删除考试」入口用例（add-frontend-exam-edit-delete，前端台账 F-1+F-2）。
 *
 * 断言口径与 spec-delta 对齐：
 * - 操作列显隐只做乐观口径：「编辑」与「发布考试」同源（未开始+未发布），「删除」放宽为
 *   仅未发布——最终裁决在后端 ExamService.assertEditable（未发布且未开始），前端不拦截、
 *   失败 message 呈现后端原文；
 * - 编辑复用创建页：route query 带 examId 时标题「编辑考试」，详情端点（detail2）回填，
 *   提交改调 update2（path 携带 examId），成功跳回列表并失效 exams 查询；
 * - 删除经知情确认弹窗（形态对齐强制结束：warning Alert 明示不可逆），确认后调 delete2
 *   并失效 exams 查询。
 *
 * 页面通过 mock 契约层（@/api/axios）与数据层（@tanstack/vue-query）挂载；useQuery mock
 * 沿用 makeupFinalScore.spec 的真链路口径（真实调用 queryFn，rejection 落进 error ref）。
 * Modal 文案经 portal 渲染，用 document.body 断言。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { Modal, message } from 'ant-design-vue';

import * as api from '@/api/axios';
import ExamsIndexPage from '@/pages/(dashboard)/teacher/exams/index.page.vue';
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
  page2: vi.fn(),
  publish1: vi.fn(),
  forceEnd: vi.fn(),
  create3: vi.fn(),
  page1: vi.fn(),
  page3: vi.fn(),
  detail2: vi.fn(),
  update2: vi.fn(),
  delete2: vi.fn(),
}));

/** useQuery mock：真实调用页面 queryFn（真链路口径）；enabled 为 false 时不发起 */
vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey?: unknown; queryFn?: () => unknown; enabled?: unknown }) => {
      const data = ref<unknown>(undefined);
      const error = ref<unknown>(null);
      const enabled = options.enabled === undefined || unref(options.enabled) !== false;
      if (enabled && options.queryFn) {
        Promise.resolve()
          .then(options.queryFn)
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

/** 四行考试：21=未开始+未发布（可编辑可删可发布）、22=进行中未发布（乐观口径仅删）、
 *  23=已结束未发布（乐观口径仅删）、24=已发布（编辑删除发布皆无） */
const ROW_EDITABLE = {
  id: 21,
  title: '待编辑的考试',
  paperId: 5,
  classId: 1,
  status: 0,
  published: false,
  startTime: '2026-10-01T09:00:00',
  endTime: '2026-10-01T11:00:00',
  durationMinutes: 90,
};
const ROW_IN_PROGRESS_UNPUBLISHED = { ...ROW_EDITABLE, id: 22, title: '进行中未发布', status: 1 };
const ROW_ENDED_UNPUBLISHED = { ...ROW_EDITABLE, id: 23, title: '已结束未发布', status: 2 };
const ROW_PUBLISHED = { ...ROW_EDITABLE, id: 24, title: '已发布考试', published: true };
const LIST_ROWS = [ROW_EDITABLE, ROW_IN_PROGRESS_UNPUBLISHED, ROW_ENDED_UNPUBLISHED, ROW_PUBLISHED];

/** 详情端点回填 fixture：antiCheatConfig 与创建请求同键（switchScreen/forbidCopy） */
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

function mountIndexPage() {
  return mount(ExamsIndexPage);
}

function bodyText(): string {
  return document.body.textContent ?? '';
}

/** 在指定行文本所在 tr 内按按钮文本定位按钮 */
function findRowButton(wrapper: ReturnType<typeof mount>, rowText: string, buttonText: string) {
  const row = wrapper.findAll('tbody tr').find((tr) => tr.text().includes(rowText));
  return row?.findAll('button').find((b) => b.text() === buttonText);
}

beforeEach(() => {
  routeMock.query = {};
  vi.mocked(api.page2).mockResolvedValue(LIST_ROWS as never);
  vi.mocked(api.page1).mockResolvedValue([] as never);
  vi.mocked(api.page3).mockResolvedValue({ list: [] } as never);
  vi.mocked(api.detail2).mockResolvedValue(EXAM_DETAIL as never);
  vi.mocked(api.update2).mockResolvedValue(EXAM_DETAIL as never);
  vi.mocked(api.delete2).mockResolvedValue({} as never);
  // antd message 会往 document.body 渲染提示条，spy 掉避免污染 body 断言
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('考试列表：编辑与删除入口（index.page）', () => {
  it('操作列乐观门控：未开始+未发布→编辑+删除；进行中/已结束未发布→仅删除；已发布→两者皆无', async () => {
    const wrapper = mountIndexPage();
    await flushPromises();
    await flushPromises();

    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts.filter((t) => t === '编辑')).toHaveLength(1);
    expect(buttonTexts.filter((t) => t === '删除')).toHaveLength(3);
    expect(buttonTexts.filter((t) => t === '发布考试')).toHaveLength(1);
    expect(buttonTexts.filter((t) => t === '详情')).toHaveLength(4);
  });

  it('点击「编辑」跳转创建页并携带 examId', async () => {
    const wrapper = mountIndexPage();
    await flushPromises();
    await flushPromises();

    const editButton = findRowButton(wrapper, '待编辑的考试', '编辑');
    expect(editButton).toBeTruthy();
    await editButton?.trigger('click');

    expect(routerMock.push).toHaveBeenCalledWith('/teacher/exams/create?examId=21');
  });

  it('删除确认弹窗明示不可逆；确认后调 delete2 并失效 exams 查询', async () => {
    const wrapper = mountIndexPage();
    await flushPromises();
    await flushPromises();

    const deleteButton = findRowButton(wrapper, '待编辑的考试', '删除');
    expect(deleteButton).toBeTruthy();
    await deleteButton?.trigger('click');
    await flushPromises();

    await vi.waitFor(() => expect(bodyText()).toContain('不可恢复'));

    const deleteModal = wrapper
      .findAllComponents(Modal)
      .find((m) => m.props('title') === '确认删除考试');
    expect(deleteModal).toBeTruthy();
    deleteModal?.vm.$emit('ok');
    await flushPromises();

    expect(api.delete2).toHaveBeenCalledWith(
      expect.objectContaining({ throwOnError: true, path: { id: 21 } })
    );
    expect(message.success).toHaveBeenCalledWith('考试已删除');
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['exams'] });
  });

  it('删除失败呈现后端原文：非未开始考试被拒时不本地编造文案、不失效查询', async () => {
    vi.mocked(api.delete2).mockRejectedValue(new Error('仅未开始的考试允许修改或删除'));
    const wrapper = mountIndexPage();
    await flushPromises();
    await flushPromises();

    // 进行中+未发布的行：入口乐观显示，点击后由后端拒绝
    const deleteButton = findRowButton(wrapper, '进行中未发布', '删除');
    expect(deleteButton).toBeTruthy();
    await deleteButton?.trigger('click');
    await flushPromises();

    await vi.waitFor(() => expect(bodyText()).toContain('不可恢复'));
    const deleteModal = wrapper
      .findAllComponents(Modal)
      .find((m) => m.props('title') === '确认删除考试');
    deleteModal?.vm.$emit('ok');
    await flushPromises();

    expect(api.delete2).toHaveBeenCalledWith(
      expect.objectContaining({ throwOnError: true, path: { id: 22 } })
    );
    expect(message.error).toHaveBeenCalledWith('仅未开始的考试允许修改或删除');
    expect(message.success).not.toHaveBeenCalled();
    expect(invalidateQueries).not.toHaveBeenCalled();
  });
});

describe('考试编辑模式（create.page）', () => {
  function mountEditPage() {
    routeMock.query = { examId: '21' };
    return mount(ExamCreatePage);
  }

  async function mountEditPageAndBackfill() {
    const wrapper = mountEditPage();
    await flushPromises();
    await flushPromises();
    return wrapper;
  }

  it('编辑模式：标题「编辑考试」，详情端点返回值回填全部表单字段（含防作弊两开关）', async () => {
    const wrapper = await mountEditPageAndBackfill();

    expect(api.detail2).toHaveBeenCalledWith(
      expect.objectContaining({ throwOnError: true, path: { id: 21 } })
    );
    expect(wrapper.text()).toContain('编辑考试');

    const vm = wrapper.vm as unknown as {
      form: {
        title: string;
        description: string;
        paperId: number | undefined;
        classId: number | undefined;
        startTime: string | undefined;
        endTime: string | undefined;
        durationMinutes: number;
        allowLateMinutes: number;
      };
      enableSwitchScreen: boolean;
      enableForbidCopy: boolean;
    };
    await vi.waitFor(() => expect(vm.form.title).toBe('待编辑的考试'));
    expect(vm.form.description).toBe('期中测验');
    expect(vm.form.paperId).toBe(5);
    expect(vm.form.classId).toBe(1);
    expect(vm.form.startTime).toBe('2026-10-01T09:00:00');
    expect(vm.form.endTime).toBe('2026-10-01T11:00:00');
    expect(vm.form.durationMinutes).toBe(90);
    expect(vm.form.allowLateMinutes).toBe(5);
    expect(vm.enableSwitchScreen).toBe(true);
    expect(vm.enableForbidCopy).toBe(true);
  });

  it('编辑提交：调 update2（path 携带 examId、请求体对齐 ExamUpdateRequest），成功跳回列表并失效 exams 查询', async () => {
    const wrapper = await mountEditPageAndBackfill();
    const vm = wrapper.vm as unknown as { handleSubmit: () => Promise<void> };
    await vi.waitFor(() =>
      expect((wrapper.vm as unknown as { form: { title: string } }).form.title).toBe('待编辑的考试')
    );

    await vm.handleSubmit();
    await flushPromises();

    expect(api.create3).not.toHaveBeenCalled();
    expect(api.update2).toHaveBeenCalledTimes(1);
    const call = vi.mocked(api.update2).mock.calls[0][0];
    expect(call.path).toEqual({ id: 21 });
    expect(call.body).toMatchObject({
      title: '待编辑的考试',
      description: '期中测验',
      paperId: 5,
      classId: 1,
      startTime: '2026-10-01T09:00:00',
      endTime: '2026-10-01T11:00:00',
      durationMinutes: 90,
      allowLateMinutes: 5,
      antiCheatConfig: { switchScreen: true, forbidCopy: true },
    });
    expect(message.success).toHaveBeenCalledWith('考试已保存');
    expect(routerMock.push).toHaveBeenCalledWith('/teacher/exams');
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['exams'] });
  });

  it('编辑提交失败：message 呈现后端原文且不跳转、不失效查询', async () => {
    vi.mocked(api.update2).mockRejectedValue(new Error('仅未开始的考试允许修改或删除'));
    const wrapper = await mountEditPageAndBackfill();
    const vm = wrapper.vm as unknown as { handleSubmit: () => Promise<void> };
    await vi.waitFor(() =>
      expect((wrapper.vm as unknown as { form: { title: string } }).form.title).toBe('待编辑的考试')
    );

    await vm.handleSubmit();
    await flushPromises();

    expect(message.error).toHaveBeenCalledWith('仅未开始的考试允许修改或删除');
    expect(message.success).not.toHaveBeenCalled();
    expect(routerMock.push).not.toHaveBeenCalledWith('/teacher/exams');
    expect(invalidateQueries).not.toHaveBeenCalled();
  });
});
