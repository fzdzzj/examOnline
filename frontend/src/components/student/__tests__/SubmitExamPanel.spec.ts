/**
 * 交卷面板：未答题时提供「跳转首道未答题」快捷入口（Mark for Review 变更，spec-delta）。
 *
 * 判据（对应 proposal 第二特性与 tasks.json 任务 2）：
 * - unansweredCount > 0 时，确认弹窗内于「确认交卷」旁出现「跳转首道未答题」按钮；
 * - unansweredCount === 0 时该按钮不出现；
 * - 点击跳转按钮：关闭弹窗并回抛 `jump-to-unanswered` 事件（跳转口径由页面用
 *   `navStatesOf` 算出，本组件不自算、不自带题目数据）。
 *
 * antd Modal 内容 teleport 到 document.body，故弹层内元素一律从 body 断言
 * （写法对齐仓库既有 Modal 用例，如 QuestionFormImageUpload / MessageCenter）。
 */
import { mount } from '@vue/test-utils';
import { Modal } from 'ant-design-vue';
import { afterEach, describe, expect, it } from 'vitest';

import SubmitExamPanel from '@/components/student/SubmitExamPanel.vue';

function mountPanel(props: Record<string, unknown> = {}) {
  return mount(SubmitExamPanel, {
    attachTo: document.body,
    props: {
      unansweredCount: 2,
      totalQuestions: 5,
      submitting: false,
      phase: 'idle',
      error: null,
      ...props,
    },
  });
}

async function openModal(wrapper: ReturnType<typeof mountPanel>) {
  await wrapper.find('[data-test="submit-button"]').trigger('click');
  await wrapper.vm.$nextTick();
}

const jumpBtn = () => document.body.querySelector('[data-test="jump-unanswered"]');

afterEach(() => {
  document.body.innerHTML = '';
});

describe('SubmitExamPanel 跳转首道未答题', () => {
  it('未答题数>0 时确认弹窗内出现「跳转首道未答题」按钮', async () => {
    const wrapper = mountPanel();
    await openModal(wrapper);
    expect(jumpBtn()).not.toBeNull();
    wrapper.unmount();
  });

  it('unansweredCount === 0 时不出现跳转按钮', async () => {
    const wrapper = mountPanel({ unansweredCount: 0 });
    await openModal(wrapper);
    expect(jumpBtn()).toBeNull();
    wrapper.unmount();
  });

  it('点击跳转：关闭弹窗并回抛 jump-to-unanswered 事件', async () => {
    const wrapper = mountPanel();
    await openModal(wrapper);
    const modal = wrapper.findComponent(Modal);
    expect(modal.props('open')).toBe(true);

    await (jumpBtn() as HTMLElement).click();
    await wrapper.vm.$nextTick();

    expect(wrapper.emitted('jump-to-unanswered')).toBeTruthy();
    // 弹窗已关闭：open 置 false（antd Modal 默认保留挂载仅隐藏，故从 open 状态断言）
    expect(modal.props('open')).toBe(false);
    wrapper.unmount();
  });

  it('跳转按钮不触发确认交卷（submit 不被回抛）', async () => {
    const wrapper = mountPanel();
    await openModal(wrapper);
    await (jumpBtn() as HTMLElement).click();
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('submit')).toBeUndefined();
    wrapper.unmount();
  });
});
