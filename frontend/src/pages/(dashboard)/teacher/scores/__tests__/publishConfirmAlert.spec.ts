/**
 * 发布确认弹窗知情文案用例（阶段 23 复核补齐）。
 *
 * 阶段 23 交付的发布确认弹窗正文写在 antd4 Alert 的默认插槽里被静默丢弃
 * （Alert 只渲染 message/description 具名插槽），三条知情提示在真实浏览器不可见——
 * 阶段 21 验收发现同类缺陷后全仓审计出此处，复核时修复为 #message 具名插槽。
 * 本用例即该修复的红绿取证：修复前此断言必红（正文不存在于 DOM）。
 *
 * 断言口径与 spec-delta 对齐：发布前的知情确认（仅已批改可发布 / 汇总锁定 / 幂等）
 * 必须真实呈现给教师。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { Modal, Select, message } from 'ant-design-vue';

import TeacherScoresPage from '@/pages/(dashboard)/teacher/scores/index.page.vue';

const invalidateQueries = vi.hoisted(() => vi.fn());
/** useQuery 的按 queryKey 首元素分发的数据表 */
const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));

vi.mock('vuex', () => ({
  useStore: () => ({ state: {} }),
}));

vi.mock('@/store', () => ({
  highestRoleOf: () => 'ADMIN',
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
  publish: vi.fn(),
  publishPreview: vi.fn(),
  revoke: vi.fn(),
  summarize: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown }) => {
      const key = unref(options.queryKey) as unknown[];
      return {
        data: ref(queryStore.data[String(key[0])]),
        isFetching: ref(false),
        refetch: vi.fn(),
      };
    },
  };
});

/** 一场 GRADED（=3）考试：resolveScoreActions 据此放行「发布成绩」入口 */
const GRADED_EXAM = { id: 3, title: '已批改的考试', status: 3 };

function bodyText(): string {
  return document.body.textContent ?? '';
}

beforeEach(() => {
  queryStore.data = { exams: [GRADED_EXAM] };
  invalidateQueries.mockClear();
  // antd message 会往 document.body 渲染提示条，spy 掉避免污染 body 断言
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('成绩发布确认弹窗（teacher/scores）', () => {
  it('知情文案真实可见：仅已批改可发布、汇总会锁定、重复发布幂等（Alert 具名插槽）', async () => {
    const wrapper = mount(TeacherScoresPage);
    await flushPromises();

    // 选中 GRADED 考试 → scoreActions 放行发布入口
    wrapper.findComponent(Select).vm.$emit('update:value', 3);
    await flushPromises();

    const publishButton = wrapper.findAll('button').find((b) => b.text() === '发布成绩');
    expect(publishButton).toBeTruthy();
    await publishButton?.trigger('click');
    await flushPromises();

    // Modal 正文经 portal 渲染且带 motion；修复前这些文案在 DOM 里不存在（默认插槽被丢弃）
    await vi.waitFor(() => expect(bodyText()).toContain('仅「已批改」状态的考试允许发布'));
    expect(bodyText()).toContain('汇总会被锁定');
    expect(bodyText()).toContain('幂等操作');

    const publishModal = wrapper
      .findAllComponents(Modal)
      .find((m) => m.props('title') === '确认发布成绩');
    expect(publishModal).toBeTruthy();
  });
});
