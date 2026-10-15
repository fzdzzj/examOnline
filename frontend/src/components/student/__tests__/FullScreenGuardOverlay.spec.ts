/**
 * 全屏作答引导弹层（add-anti-cheat-restrictions 阶段 3）。
 *
 * 它只是一层**引导**：说明本场考试要求全屏、给出进入全屏的按钮、声明「只警告不交卷」。
 * 弹层不阻塞作答（`:closable=false` 但不是强制出口——全屏可否成功由浏览器决定），
 * 且不含任何交卷入口。visible=false 时连内容都不渲染。
 */
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';

import FullScreenGuardOverlay from '@/components/student/FullScreenGuardOverlay.vue';
import { ANTI_CHEAT_HINT } from '@/utils/antiCheat';

function mountOverlay(props: Record<string, unknown> = {}) {
  return mount(FullScreenGuardOverlay, {
    props: { visible: true, ...props },
    attachTo: document.body,
  });
}

describe('FullScreenGuardOverlay：引导而非强制', () => {
  it('visible=false：不渲染引导内容', () => {
    const wrapper = mountOverlay({ visible: false });
    expect(wrapper.find('[data-test="fullscreen-guard-overlay"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('visible=true：呈现要求全屏的说明与「进入全屏作答」入口', () => {
    const wrapper = mountOverlay();
    const panel = wrapper.find('[data-test="fullscreen-guard-overlay"]');
    expect(panel.exists()).toBe(true);
    expect(panel.text()).toContain(ANTI_CHEAT_HINT.FULLSCREEN_REQUIRED);
    expect(wrapper.find('[data-test="enter-fullscreen"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it('点击入口只上抛 enter 事件（由页面调真实全屏请求，弹层不自己碰浏览器 API）', async () => {
    const wrapper = mountOverlay();
    await wrapper.find('[data-test="enter-fullscreen"]').trigger('click');
    expect(wrapper.emitted('enter')).toHaveLength(1);
    wrapper.unmount();
  });

  it('文案明确「只警告不交卷、处置由教师依行为日志判定」（不暗示自动交卷）', () => {
    const wrapper = mountOverlay();
    const text = wrapper.find('[data-test="fullscreen-guard-overlay"]').text();
    expect(text).toContain('不交卷');
    expect(text).toContain('行为日志');
    expect(text).not.toContain('自动交卷');
    wrapper.unmount();
  });
});
