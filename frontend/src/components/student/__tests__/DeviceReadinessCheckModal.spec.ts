/**
 * DeviceReadinessCheckModal 组件单测（add-pre-exam-device-readiness-check 阶段 2 · 先红后绿）。
 *
 * 门控语义（知情门控，非绝对阻断）：
 * - 检查完成前「进入考试」按钮禁用（硬门控）；
 * - 逐项呈现四类检查的执行进度（进行中 / 待执行）与结果徽标（通过 / 警示 / 强警示）；
 * - 强警示（critical）触发「仍要进入」二次确认，确认前绝不 emit enter；
 * - 警示（warning）仅呈现徽标不阻断；
 * - 自检异常兜底报告（全部警示）按普通警示放行（无 critical → 无二次确认）。
 */
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';

import DeviceReadinessCheckModal from '@/components/student/DeviceReadinessCheckModal.vue';
import { READINESS_ITEM_LABELS, type DeviceReadinessReport } from '@/hooks/useDeviceReadiness';

function reportOf(
  overall: DeviceReadinessReport['overall'],
  statuses: Array<
    [
      'rtt' | 'viewport' | 'fullscreen' | 'compatibility',
      DeviceReadinessReport['items'][number]['level'],
    ]
  >
): DeviceReadinessReport {
  return {
    completed: true,
    overall,
    items: statuses.map(([key, level]) => ({
      key,
      level,
      label: READINESS_ITEM_LABELS[key],
      detail: `${READINESS_ITEM_LABELS[key]} 结果详情`,
    })),
  };
}

const PASS_REPORT = reportOf('pass', [
  ['rtt', 'pass'],
  ['viewport', 'pass'],
  ['fullscreen', 'pass'],
  ['compatibility', 'pass'],
]);

const WARNING_REPORT = reportOf('warning', [
  ['rtt', 'warning'],
  ['viewport', 'warning'],
  ['fullscreen', 'pass'],
  ['compatibility', 'pass'],
]);

const CRITICAL_REPORT = reportOf('critical', [
  ['rtt', 'warning'],
  ['viewport', 'pass'],
  ['fullscreen', 'warning'],
  ['compatibility', 'critical'],
]);

/** 异常兜底形态：全部警示（自检故障不得制造 critical 二次确认去挡考）。 */
const FALLBACK_REPORT = reportOf('warning', [
  ['rtt', 'warning'],
  ['viewport', 'warning'],
  ['fullscreen', 'warning'],
  ['compatibility', 'warning'],
]);

function mountModal(props: Record<string, unknown> = {}) {
  return mount(DeviceReadinessCheckModal, {
    props: {
      open: true,
      running: false,
      runningItem: null,
      report: null,
      ...props,
    },
  });
}

describe('检查完成前「进入考试」禁用（硬门控）', () => {
  it('report 为空 → 进入考试按钮禁用', () => {
    const wrapper = mountModal();
    const button = wrapper.find('[data-test="readiness-enter"]');
    expect(button.exists()).toBe(true);
    expect(button.attributes('disabled')).toBeDefined();
  });

  it('report 呈现（通过）→ 进入考试按钮可用', () => {
    const wrapper = mountModal({ report: PASS_REPORT });
    expect(wrapper.find('[data-test="readiness-enter"]').attributes('disabled')).toBeUndefined();
  });
});

describe('逐项进度呈现', () => {
  it('运行中：当前检查项显示「进行中」，其余「待执行」，按钮仍禁用', () => {
    const wrapper = mountModal({ running: true, runningItem: 'viewport' });
    const viewportState = wrapper.find('[data-test="readiness-state-viewport"]');
    expect(viewportState.text()).toContain('进行中');
    expect(wrapper.find('[data-test="readiness-state-rtt"]').text()).toContain('待执行');
    expect(wrapper.find('[data-test="readiness-enter"]').attributes('disabled')).toBeDefined();
  });
});

describe('结果徽标（通过 / 警示 / 强警示）', () => {
  it('四类检查项各呈现对应徽标文案', () => {
    const wrapper = mountModal({ report: CRITICAL_REPORT });
    expect(wrapper.find('[data-test="readiness-badge-rtt"]').text()).toContain('警示');
    expect(wrapper.find('[data-test="readiness-badge-viewport"]').text()).toContain('通过');
    expect(wrapper.find('[data-test="readiness-badge-fullscreen"]').text()).toContain('警示');
    expect(wrapper.find('[data-test="readiness-badge-compatibility"]').text()).toContain('强警示');
  });

  it('警示项仅呈现徽标，不出现强警示按钮文案', () => {
    const wrapper = mountModal({ report: WARNING_REPORT });
    expect(wrapper.find('[data-test="readiness-enter"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="readiness-enter-critical"]').exists()).toBe(false);
  });
});

describe('强警示二次确认', () => {
  it('critical 项存在 → 按钮为「仍要进入考试」，点击后先出现二次确认，确认前不 emit enter', async () => {
    const wrapper = mountModal({ report: CRITICAL_REPORT });
    expect(wrapper.find('[data-test="readiness-enter-critical"]').exists()).toBe(true);

    await wrapper.find('[data-test="readiness-enter-critical"]').trigger('click');
    expect(wrapper.find('[data-test="critical-confirm"]').exists()).toBe(true);
    expect(wrapper.emitted('enter')).toBeUndefined();

    // 确认「仍要进入」→ 才 emit enter
    await wrapper.find('[data-test="confirm-still-enter"]').trigger('click');
    expect(wrapper.emitted('enter')).toHaveLength(1);
  });

  it('二次确认里选「再想想」→ 确认面板收起且不 emit enter', async () => {
    const wrapper = mountModal({ report: CRITICAL_REPORT });
    await wrapper.find('[data-test="readiness-enter-critical"]').trigger('click');
    expect(wrapper.find('[data-test="critical-confirm"]').exists()).toBe(true);

    await wrapper.find('[data-test="cancel-still-enter"]').trigger('click');
    expect(wrapper.find('[data-test="critical-confirm"]').exists()).toBe(false);
    expect(wrapper.emitted('enter')).toBeUndefined();
  });
});

describe('无强警示直接放行与异常兜底', () => {
  it('无 critical → 点「进入考试」直接 emit enter，无二次确认', async () => {
    const wrapper = mountModal({ report: PASS_REPORT });
    await wrapper.find('[data-test="readiness-enter"]').trigger('click');
    expect(wrapper.find('[data-test="critical-confirm"]').exists()).toBe(false);
    expect(wrapper.emitted('enter')).toHaveLength(1);
  });

  it('异常兜底报告（全部警示）→ 按警示放行：无 critical 无二次确认，按钮可用', async () => {
    const wrapper = mountModal({ report: FALLBACK_REPORT });
    expect(wrapper.find('[data-test="readiness-enter"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-test="readiness-enter-critical"]').exists()).toBe(false);
    await wrapper.find('[data-test="readiness-enter"]').trigger('click');
    expect(wrapper.emitted('enter')).toHaveLength(1);
  });
});
