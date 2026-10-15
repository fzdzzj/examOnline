/**
 * 成绩复核处理弹层防呆用例（harden-review-flow-and-resource-limits M-05）：
 *
 * - 调分不可逆警示：选中「同意」且填入调整后总分时，弹层 SHALL 动态呈现
 *   warning Alert（data-test="review-adjust-warning"）明示「覆写最终得分且不可逆」；
 *   未调分（留空）或驳回时不出现该警示（防呆只在真正覆写成绩的路径出现）；
 * - 二次知情确认：「同意且调分」提交前 SHALL 经 Modal.confirm 二次确认，
 *   确认后才发 handle 请求；取消确认不发起请求；驳回路径无需确认直接提交。
 *
 * 变异 m1：抽掉调分 warning Alert 渲染 → 「AGREE+调分 → 出现警示」用例变红。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { Modal, message } from 'ant-design-vue';

import * as api from '@/api/axios';
import type { ScoreReview } from '@/api/axios';
import ReviewHandleModal from '../ReviewHandleModal.vue';

vi.mock('@/api/apiClient', () => ({
  client: { __test: 'review-handle-modal' },
  unwrap: async (value: unknown) => value,
}));

vi.mock('@/api/axios', () => ({
  handle: vi.fn(),
}));

const MOCK_REVIEW: ScoreReview = {
  id: 7,
  examId: 1,
  studentId: 9,
  status: 1,
  reason: '客观题得分有误',
};

function mountModal() {
  return mount(ReviewHandleModal, {
    props: { open: true, review: MOCK_REVIEW },
  });
}

/** 弹层内 script-setup 顶层 ref（action/adjustedTotalScore/reason），经 vm 代理读写。 */
function vmOf(wrapper: ReturnType<typeof mountModal>) {
  return wrapper.vm as unknown as {
    action: 'AGREE' | 'REJECT';
    adjustedTotalScore: number | null;
    reason: string;
  };
}

/** 弹层内容由 antd Modal 传送到 document.body，从 body 查警示 Alert。 */
function warningAlert(): Element | null {
  return document.body.querySelector('[data-test="review-adjust-warning"]');
}

function modalOk(wrapper: ReturnType<typeof mountModal>): void {
  wrapper.findComponent(Modal).vm.$emit('ok');
}

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  vi.spyOn(message, 'warning').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'success').mockImplementation(() => ({}) as never);
  vi.spyOn(message, 'error').mockImplementation(() => ({}) as never);
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.clearAllMocks();
  document.body.innerHTML = '';
});

describe('调分不可逆警示（M-05）', () => {
  it('AGREE + 已填调分：显性呈现 warning Alert，文案强调不可逆', async () => {
    const wrapper = mountModal();
    vmOf(wrapper).adjustedTotalScore = 88;
    await flushPromises();

    const alert = warningAlert();
    expect(alert).not.toBeNull();
    expect(alert?.textContent).toContain('不可逆');
    expect(alert?.textContent).toContain('最终得分');
  });

  it('AGREE + 留空调分：仅认可复核不改分，不出现不可逆警示', async () => {
    mountModal();
    await flushPromises();

    expect(warningAlert()).toBeNull();
  });

  it('REJECT（驳回维持原成绩）：即使误填调分也不出现不可逆警示', async () => {
    const wrapper = mountModal();
    vmOf(wrapper).action = 'REJECT';
    vmOf(wrapper).adjustedTotalScore = 88;
    await flushPromises();

    expect(warningAlert()).toBeNull();
  });
});

describe('二次知情确认（M-05）', () => {
  /** 捕获 Modal.confirm 的配置；confirmSpy 不渲染真实确认弹层，onOk 由测试显式触发。 */
  function captureConfirm() {
    const captured: { onOk?: () => unknown } = {};
    const confirmSpy = vi.spyOn(Modal, 'confirm').mockImplementation((options) => {
      Object.assign(captured, options);
      return { destroy: vi.fn(), update: vi.fn() } as never;
    });
    return { confirmSpy, captured };
  }

  it('AGREE + 调分：提交先弹二次确认，确认（onOk）后才发起 handle 请求', async () => {
    const { confirmSpy, captured } = captureConfirm();
    const wrapper = mountModal();
    vmOf(wrapper).reason = '分数录入有误，更正为 88';
    vmOf(wrapper).adjustedTotalScore = 88;

    modalOk(wrapper);
    await flushPromises();

    // 触发提交 → 先出确认弹层；确认按钮被点击前不发起任何请求
    expect(confirmSpy).toHaveBeenCalledTimes(1);
    expect(api.handle).not.toHaveBeenCalled();

    // 模拟用户点击确认按钮（onOk）
    expect(captured.onOk).toBeDefined();
    await captured.onOk?.();
    await flushPromises();

    expect(api.handle).toHaveBeenCalledTimes(1);
    expect(api.handle).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { reviewId: 7 },
        body: expect.objectContaining({ action: 'AGREE', adjustedTotalScore: 88 }),
      })
    );
  });

  it('AGREE + 调分但用户取消/关闭确认弹层：不发起任何请求', async () => {
    const { confirmSpy } = captureConfirm();
    const wrapper = mountModal();
    vmOf(wrapper).reason = '拟调整分数';
    vmOf(wrapper).adjustedTotalScore = 88;

    modalOk(wrapper);
    await flushPromises();

    expect(confirmSpy).toHaveBeenCalledTimes(1);
    expect(api.handle).not.toHaveBeenCalled();
  });

  it('REJECT（不改分）：无需二次确认，直接提交 handle 请求', async () => {
    const { confirmSpy } = captureConfirm();
    const wrapper = mountModal();
    vmOf(wrapper).action = 'REJECT';
    vmOf(wrapper).reason = '复核证据不足，维持原成绩';

    modalOk(wrapper);
    await flushPromises();

    expect(confirmSpy).not.toHaveBeenCalled();
    expect(api.handle).toHaveBeenCalledTimes(1);
    expect(api.handle).toHaveBeenCalledWith(
      expect.objectContaining({
        body: expect.objectContaining({ action: 'REJECT', adjustedTotalScore: undefined }),
      })
    );
  });

  it('AGREE + 调分留空（仅认可复核）：无需二次确认直接提交', async () => {
    const { confirmSpy } = captureConfirm();
    const wrapper = mountModal();
    vmOf(wrapper).reason = '认可复核结论';

    modalOk(wrapper);
    await flushPromises();

    expect(confirmSpy).not.toHaveBeenCalled();
    expect(api.handle).toHaveBeenCalledTimes(1);
  });
});
