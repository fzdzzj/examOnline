/**
 * 离线保护状态条组件单测（阶段 2 · 先红后绿）。
 *
 * 措辞纪律（红线）：
 * 1. 禁用「离线考试 / 离线作答 / offline exam / offline answer」措辞；
 * 2. 明确包含「离线保护中，答案已本地保存」类提示；
 * 3. 恢复后消失（visible=false 时不渲染）。
 */
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import OfflineStatusBanner from '@/components/student/OfflineStatusBanner.vue';

describe('OfflineStatusBanner 离线状态条与措辞纪律', () => {
  it('visible 为 false 时，不渲染离线状态条', () => {
    const wrapper = mount(OfflineStatusBanner, {
      props: { visible: false },
    });
    expect(wrapper.find('[data-test="offline-status-banner"]').exists()).toBe(false);
  });

  it('visible 为 true 时，渲染离线状态条并展示合规文案', () => {
    const wrapper = mount(OfflineStatusBanner, {
      props: { visible: true },
    });
    const banner = wrapper.find('[data-test="offline-status-banner"]');
    expect(banner.exists()).toBe(true);
    expect(banner.text()).toContain('离线保护中，答案已本地保存');
    expect(banner.text()).toContain('恢复');
  });

  it('措辞纪律护栏：严格禁用「离线考试 / 离线作答」', () => {
    const wrapper = mount(OfflineStatusBanner, {
      props: { visible: true },
    });
    const text = wrapper.text();
    expect(text).not.toContain('离线考试');
    expect(text).not.toContain('离线作答');
    expect(text.toLowerCase()).not.toContain('offline exam');
    expect(text.toLowerCase()).not.toContain('offline answer');
  });
});
