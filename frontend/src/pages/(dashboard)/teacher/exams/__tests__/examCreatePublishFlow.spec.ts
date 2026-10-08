/**
 * 「建考试 → 发布 / 强制结束」关键流程用例（阶段 21 任务 #4 第 1 step 的补齐）。
 *
 * 阶段 21 缺口补齐（33860ad）覆盖了五个缺口域（监考/时间线/快照预览/花名册/Grafana），
 * 但原始交付的核心链路——创建表单提交、发布与 force-end 的二次确认——一直没有用例。
 * 断言口径与 spec-delta 对齐：
 * - 状态与可用操作一律按后端返回渲染（前端不推算状态机）；
 * - 发布确认弹窗明示「生成快照 + 锁定」；
 * - force-end 确认弹窗明示「触发缺考标记 + 不可逆」；
 * - 创建请求体字段与后端 ExamCreateRequest 对齐（时间字符串带字面量 T）。
 *
 * 页面通过 mock 契约层（@/api/axios）与数据层（@tanstack/vue-query）挂载，
 * Modal 文案经 portal 渲染，用 document.body 断言。
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { Modal, message } from 'ant-design-vue';

import * as api from '@/api/axios';
import ExamsIndexPage from '@/pages/(dashboard)/teacher/exams/index.page.vue';
import ExamCreatePage from '@/pages/(dashboard)/teacher/exams/create.page.vue';

// ---- 各 mock 模块需要跨 beforeEach 共享的状态（vi.hoisted 提到 mock 之前） ----
const routerMock = vi.hoisted(() => ({ push: vi.fn(), back: vi.fn() }));
// add-frontend-exam-edit-delete：create.page 改用 useRoute 读编辑模式 examId，
// 测试基建补 useRoute（query 为空 = 新建模式），断言零改动
const routeMock = vi.hoisted(() => ({ query: {} as Record<string, unknown> }));
const invalidateQueries = vi.hoisted(() => vi.fn());
/** useQuery 的按 queryKey 首元素分发的数据表 */
const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));

vi.mock('vue-router', () => ({ useRouter: () => routerMock, useRoute: () => routeMock }));

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
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown }) => {
      const key = unref(options.queryKey) as unknown[];
      return {
        data: ref(queryStore.data[String(key[0])]),
        // fix-frontend-exam-page-error-states（U-6/U-7）：页面消费 useQuery 的 error，
        // 基建补 error ref（rejection 落尽走 queryFn mock，此处恒 null），断言零改动
        error: ref<unknown>(null),
        isFetching: ref(false),
        refetch: vi.fn(),
      };
    },
  };
});

/** 三行考试：9=未开始+未发布（可发布）、10=进行中（可强制结束）、11=未开始+已发布（无动作） */
const ROW_PUBLISHABLE = {
  id: 9,
  title: '期中考试',
  paperId: 5,
  classId: 1,
  status: 0,
  published: false,
  startTime: '2026-10-01T09:00:00',
  endTime: '2026-10-01T11:00:00',
  durationMinutes: 90,
};
const ROW_IN_PROGRESS = { ...ROW_PUBLISHABLE, id: 10, title: '进行中的考试', status: 1 };
const ROW_ALREADY_PUBLISHED = {
  ...ROW_PUBLISHABLE,
  id: 11,
  title: '已发布的考试',
  published: true,
};

function mountIndexPage() {
  queryStore.data['exams'] = [ROW_PUBLISHABLE, ROW_IN_PROGRESS, ROW_ALREADY_PUBLISHED];
  return mount(ExamsIndexPage);
}

function bodyText(): string {
  return document.body.textContent ?? '';
}

beforeEach(() => {
  queryStore.data = {};
  routerMock.push.mockClear();
  invalidateQueries.mockClear();
  vi.mocked(api.publish1).mockResolvedValue({} as never);
  vi.mocked(api.forceEnd).mockResolvedValue({} as never);
  vi.mocked(api.create3).mockResolvedValue({} as never);
  // antd message 会往 document.body 渲染提示条，spy 掉避免污染 body 断言
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('考试列表：发布与强制结束入口（index.page）', () => {
  it('动作入口按后端返回的 status/published 渲染：仅「未开始+未发布」可发布、仅「进行中」可强制结束', async () => {
    const wrapper = mountIndexPage();
    await flushPromises();

    const buttonTexts = wrapper.findAll('button').map((b) => b.text());
    expect(buttonTexts.filter((t) => t === '发布考试')).toHaveLength(1);
    expect(buttonTexts.filter((t) => t === '强制结束')).toHaveLength(1);
    // 已发布的行（id=11）两个动作都不出现——前端不推算，只按返回值渲染
    expect(buttonTexts.filter((t) => t === '详情')).toHaveLength(3);
  });

  it('发布确认弹窗明示生成快照与锁定；确认后调 publish 并失效列表缓存', async () => {
    const wrapper = mountIndexPage();
    await flushPromises();

    await wrapper
      .findAll('button')
      .find((b) => b.text() === '发布考试')
      ?.trigger('click');
    await flushPromises();

    // Modal 正文经 portal 渲染且带 motion，用 waitFor 等它出现
    await vi.waitFor(() => expect(bodyText()).toContain('生成试卷快照'));
    expect(bodyText()).toContain('锁定');

    const publishModal = wrapper
      .findAllComponents(Modal)
      .find((m) => m.props('title') === '确认发布考试');
    expect(publishModal).toBeTruthy();
    publishModal?.vm.$emit('ok');
    await flushPromises();

    expect(api.publish1).toHaveBeenCalledWith(
      expect.objectContaining({ throwOnError: true, path: { id: 9 } })
    );
    expect(message.success).toHaveBeenCalledWith('考试已发布，试卷快照已生成');
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['exams'] });
  });

  it('force-end 确认弹窗明示触发缺考标记且不可逆；确认后调 force-end', async () => {
    const wrapper = mountIndexPage();
    await flushPromises();

    await wrapper
      .findAll('button')
      .find((b) => b.text() === '强制结束')
      ?.trigger('click');
    await flushPromises();

    await vi.waitFor(() => expect(bodyText()).toContain('触发缺考标记'));
    expect(bodyText()).toContain('不可逆');

    const forceEndModal = wrapper
      .findAllComponents(Modal)
      .find((m) => m.props('title') === '确认强制结束');
    expect(forceEndModal).toBeTruthy();
    forceEndModal?.vm.$emit('ok');
    await flushPromises();

    expect(api.forceEnd).toHaveBeenCalledWith(
      expect.objectContaining({ throwOnError: true, path: { id: 10 } })
    );
    expect(message.success).toHaveBeenCalledWith('考试已强制结束，缺考标记已触发');
  });
});

describe('考试创建表单（create.page）', () => {
  function mountCreatePage() {
    // 该 useQuery mock 直接按 queryKey 首元素投喂数据、不走 queryFn，
    // 因此两个下拉的值是「累加器拼全后的数组」（U-2 起班级不再是单页信封）
    queryStore.data['papers'] = [{ id: 5, title: '期中卷' }];
    queryStore.data['classes'] = [{ id: 1, name: '一班' }];
    return mount(ExamCreatePage);
  }

  it('必填缺失被拦：不发起创建请求', async () => {
    const wrapper = mountCreatePage();
    await flushPromises();

    await (wrapper.vm as unknown as { handleSubmit: () => Promise<void> }).handleSubmit();

    expect(message.warning).toHaveBeenCalled();
    expect(api.create3).not.toHaveBeenCalled();
  });

  it('合法表单提交：请求体对齐后端契约（时间带字面量 T、防作弊开关、迟到分钟、空描述不携带）', async () => {
    const wrapper = mountCreatePage();
    await flushPromises();

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
      handleSubmit: () => Promise<void>;
    };
    vm.form.title = '期中考试';
    vm.form.description = '';
    vm.form.paperId = 5;
    vm.form.classId = 1;
    vm.form.startTime = '2026-10-01T09:00:00';
    vm.form.endTime = '2026-10-01T11:00:00';
    vm.form.durationMinutes = 90;
    vm.form.allowLateMinutes = 5;
    vm.enableSwitchScreen = true;
    vm.enableForbidCopy = true;

    await vm.handleSubmit();
    await flushPromises();

    expect(api.create3).toHaveBeenCalledTimes(1);
    const body = vi.mocked(api.create3).mock.calls[0][0].body;
    expect(body).toMatchObject({
      title: '期中考试',
      paperId: 5,
      classId: 1,
      startTime: '2026-10-01T09:00:00',
      endTime: '2026-10-01T11:00:00',
      durationMinutes: 90,
      allowLateMinutes: 5,
      antiCheatConfig: { switchScreen: true, forbidCopy: true },
    });
    // 空描述不携带（可选字段的展开式写法）
    expect(body).not.toHaveProperty('description');
    expect(routerMock.push).toHaveBeenCalledWith('/teacher/exams');
    expect(message.success).toHaveBeenCalledWith('考试已创建（未发布状态）');
  });
});
