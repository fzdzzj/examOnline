/**
 * 复核申请弹窗提示文案用例（阶段 23 复核补齐）。
 *
 * 复核申请弹窗的 Alert 正文原写在默认插槽里被 antd4 静默丢弃（只渲染
 * message/description 具名插槽），「限次限时由后端校验、前端不预展资格计数」的
 * 说明在真实浏览器不可见——阶段 21 验收发现同类缺陷后全仓审计出此处，复核时修复。
 * 本用例即该修复的红绿取证：修复前此断言必红（正文不存在于 DOM）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { Modal, message } from 'ant-design-vue';

import StudentScoresPage from '@/pages/(dashboard)/student/scores/index.page.vue';

const invalidateQueries = vi.hoisted(() => vi.fn());
/** useQuery 的按 queryKey 首元素分发的数据表 */
const queryStore = vi.hoisted(() => ({ data: {} as Record<string, unknown> }));

vi.mock('@/api/queryClient', () => ({
  queryClient: { invalidateQueries: invalidateQueries },
}));

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'mock-client' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  apply: vi.fn(),
  myExams: vi.fn(),
  myScore: vi.fn(),
}));

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown }) => {
      const key = unref(options.queryKey) as unknown[];
      return {
        data: ref(queryStore.data[String(key[0])]),
        // fix-frontend-query-failure-states：页面解构 error 做三态分离，mock 须提供该字段
        error: ref(null),
        isFetching: ref(false),
        refetch: vi.fn(),
      };
    },
  };
});

beforeEach(() => {
  queryStore.data = {
    'my-exams': [{ examId: 3, title: '期末考试' }],
    // reviewing 缺省（非 true）→ mapMyScoreToView 按已发布渲染，申请入口可见
    'my-score': { examTitle: '期末考试', objectiveScore: 40, subjectiveScore: 48, totalScore: 88 },
  };
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('复核申请弹窗（student/scores）', () => {
  it('后端校验说明真实可见：限次限时由后端裁决、前端不预展资格计数（Alert 具名插槽）', async () => {
    const wrapper = mount(StudentScoresPage);
    await flushPromises();

    // 已发布成绩 → 申请入口可见（可见性由后端返回决定，前端不本地推断）
    const applyButton = wrapper.findAll('button').find((b) => b.text() === '申请成绩复核');
    expect(applyButton).toBeTruthy();
    await applyButton?.trigger('click');
    await flushPromises();

    // 修复前这段正文在 DOM 里不存在（默认插槽被 antd4 Alert 丢弃）
    await vi.waitFor(() =>
      expect(document.body.textContent ?? '').toContain('复核次数与时间窗限制由后端在提交时校验')
    );
    expect(document.body.textContent ?? '').toContain('前端不预先展示资格计数');

    const applyModal = wrapper
      .findAllComponents(Modal)
      .find((m) => m.props('title') === '申请成绩复核');
    expect(applyModal).toBeTruthy();
  });
});
