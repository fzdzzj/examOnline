/**
 * 倒计时展示组件（阶段 22 第 1 片）。
 *
 * 断言的是**措辞与状态**：后端没给时间时必须说"不做本地推算"，
 * 归零时只能说"作答入口已锁定"，不能写成"已超时"——
 * 前者是事实，后者是只有后端才有资格下的结论（`ExamSweepService` / 就地兜底才判超时）。
 */
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { Alert } from 'ant-design-vue';

import ExamCountdown from '@/components/student/ExamCountdown.vue';

function mountView(props: Record<string, unknown>) {
  return mount(ExamCountdown, {
    props: { display: '—', remainingSeconds: null, expired: false, unanchored: false, ...props },
  });
}

describe('ExamCountdown', () => {
  it('正常作答中：只显示后端剩余时长，不弹任何警告', () => {
    const wrapper = mountView({ display: '14:59', remainingSeconds: 899 });
    expect(wrapper.find('[data-test="countdown-value"]').text()).toBe('14:59');
    expect(wrapper.findAllComponents(Alert)).toHaveLength(0);
  });

  it('归零：显示 00:00 并提示锁定，措辞不宣称已超时', () => {
    const wrapper = mountView({ display: '00:00', remainingSeconds: 0, expired: true });
    const alert = wrapper.find('[data-test="countdown-expired"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('锁定');
    expect(alert.text()).not.toContain('已超时');
    expect(wrapper.find('[data-test="countdown-value"]').text()).toBe('00:00');
  });

  it('后端没给时间：明示"不做本地推算"，且不锁作答（没依据就不判定）', () => {
    const wrapper = mountView({ unanchored: true });
    const alert = wrapper.find('[data-test="countdown-unanchored"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('不做本地推算');
    // 未锚定时不给归零 Alert，也不给 00:00
    expect(wrapper.find('[data-test="countdown-expired"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="countdown-value"]').text()).toBe('—');
  });

  it('未锚定与归零互斥：同时传入时优先说明"后端没给时间"', () => {
    const wrapper = mountView({ expired: true, unanchored: true });
    expect(wrapper.find('[data-test="countdown-unanchored"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="countdown-expired"]').exists()).toBe(false);
  });

  it('最后 5 分钟：只提示，不锁作答、不宣称超时', () => {
    const wrapper = mountView({ display: '03:59', remainingSeconds: 239 });
    const alert = wrapper.find('[data-test="countdown-near-end"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('5 分钟');
    // 提示不等于判定：不得出现锁定措辞，也不改变展示出的剩余时间
    expect(wrapper.find('[data-test="countdown-expired"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="countdown-value"]').text()).toBe('03:59');
  });

  it('阈值是边界：301 秒不警告，300 秒警告', () => {
    expect(
      mountView({ display: '05:01', remainingSeconds: 301 })
        .find('[data-test="countdown-near-end"]')
        .exists()
    ).toBe(false);
    expect(
      mountView({ display: '05:00', remainingSeconds: 300 })
        .find('[data-test="countdown-near-end"]')
        .exists()
    ).toBe(true);
  });

  it('归零优先于 5 分钟警告：已锁定就不再重复提示', () => {
    const wrapper = mountView({ display: '00:00', remainingSeconds: 0, expired: true });
    expect(wrapper.find('[data-test="countdown-near-end"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="countdown-expired"]').exists()).toBe(true);
  });
});
