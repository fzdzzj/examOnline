/**
 * 行为日志时间线用例（阶段 21 缺口 4）。
 *
 * 两条必守口径：
 * 1. **分级不在前端判定**——严重度文案优先用后端 `severityName`
 * （`BehaviorLogQueryService.toItem` 由 `SeverityLevel.of().label()` 填），
 *    前端的 `getSeverityConfig` 只负责上色与"后端没给名字"时的兜底；
 * 2. **未知值不得让界面崩**（后端注册表允许未注册事件走 `UnknownEventCollector`，
 *    severity 也可能落历史脏值）。
 */
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { Alert, Tag, TimelineItem } from 'ant-design-vue';

import type { BehaviorLogItem } from '@/api/axios';
import BehaviorTimeline from '@/components/exam/BehaviorTimeline.vue';
import { SEVERITY_LEVEL } from '@/constants/severity';

function log(partial: Partial<BehaviorLogItem>): BehaviorLogItem {
  return {
    id: 1,
    examId: 9,
    studentId: 12,
    eventType: 'SWITCH_SCREEN',
    severity: SEVERITY_LEVEL.LOW,
    severityName: '低',
    eventTime: '2026-09-21T10:00:00',
    ...partial,
  };
}

const LOGS: BehaviorLogItem[] = [
  log({ id: 1, severity: SEVERITY_LEVEL.LOW, severityName: '低', eventType: 'WINDOW_BLUR' }),
  log({ id: 2, severity: SEVERITY_LEVEL.MEDIUM, severityName: '中', eventType: 'SWITCH_SCREEN' }),
  log({
    id: 3,
    severity: SEVERITY_LEVEL.HIGH,
    severityName: '高',
    eventType: 'SUBMIT_ANOMALY',
    eventData: { reason: '交卷消息发送失败', retry: 3 },
  }),
];

describe('BehaviorTimeline', () => {
  it('一条日志一个时间线节点，事件类型原样透出（时间升序由后端保证）', () => {
    const wrapper = mount(BehaviorTimeline, { props: { logs: LOGS } });
    const items = wrapper.findAllComponents(TimelineItem);

    expect(items).toHaveLength(3);
    expect(wrapper.text()).toContain('WINDOW_BLUR');
    expect(wrapper.text()).toContain('SUBMIT_ANOMALY');
  });

  it('圆点色按后端 severity：1=gray、2=orange、3=red', () => {
    const wrapper = mount(BehaviorTimeline, { props: { logs: LOGS } });

    expect(wrapper.findAllComponents(TimelineItem).map((item) => item.props('color'))).toEqual([
      'gray',
      'orange',
      'red',
    ]);
  });

  it('严重度文案取后端 severityName（后端给"提示"就不显示前端映射的"低"）', () => {
    const wrapper = mount(BehaviorTimeline, {
      props: { logs: [log({ id: 1, severity: 1, severityName: '提示' })] },
    });

    expect(wrapper.text()).toContain('提示');
    expect(wrapper.text()).not.toContain('低');
  });

  it('未知 severity（历史脏值 9）：兜底成默认色 + 仍显示后端名字，不抛错', () => {
    const wrapper = mount(BehaviorTimeline, {
      props: { logs: [log({ id: 1, severity: 9, severityName: undefined })] },
    });
    const item = wrapper.findComponent(TimelineItem);

    expect(item.props('color')).toBe('gray');
    expect(wrapper.text()).toContain('低'); // getSeverityConfig 的兜底（与后端 SeverityLevel.of 同口径）
    expect(wrapper.find('[data-severity="9"]').exists()).toBe(true);
  });

  it('severity 缺失（后端给 null）：渲染为 unknown 标记而不是崩', () => {
    const wrapper = mount(BehaviorTimeline, {
      props: { logs: [log({ id: 1, severity: undefined, severityName: undefined })] },
    });

    expect(wrapper.findComponent(TimelineItem).props('color')).toBe('gray');
    expect(wrapper.find('[data-severity="unknown"]').exists()).toBe(true);
  });

  it('未知事件类型（后端兜底策略放行）：原样显示字符串，不套前端枚举', () => {
    const wrapper = mount(BehaviorTimeline, {
      props: { logs: [log({ id: 1, eventType: 'SOMETHING_NEW' })] },
    });

    expect(wrapper.text()).toContain('SOMETHING_NEW');
  });

  it('后端给的严重度分布统计原样显示（低/中/高计数不在前端数）', () => {
    const wrapper = mount(BehaviorTimeline, {
      props: { logs: LOGS, stats: { low: 12, medium: 3, high: 1 }, total: 16 },
    });
    const tags = wrapper.findAllComponents(Tag).map((tag) => tag.text());

    expect(tags).toContain('低 12');
    expect(tags).toContain('中 3');
    expect(tags).toContain('高 1');
    expect(tags).toContain('合计 16');
  });

  it('空数组：说明"后端返回 0 条"，既不画假节点也不算错误', () => {
    const wrapper = mount(BehaviorTimeline, { props: { logs: [] } });

    expect(wrapper.findComponent(TimelineItem).exists()).toBe(false);
    expect(wrapper.text()).toContain('后端返回 0 条');
    expect(wrapper.attributes('data-empty')).toBe('true');
  });

  it('错误态：Alert error，且不渲染时间线', () => {
    const wrapper = mount(BehaviorTimeline, {
      props: { logs: null, error: '没有权限执行该操作（Forbidden）' },
    });

    expect(wrapper.findComponent(Alert).props('type')).toBe('error');
    expect(wrapper.text()).toContain('没有权限执行该操作');
    expect(wrapper.findComponent(TimelineItem).exists()).toBe(false);
  });

  it('eventData 为字符串（历史脏数据被后端 TextNode 兜住）：原样展示', () => {
    const wrapper = mount(BehaviorTimeline, {
      props: { logs: [log({ id: 1, eventData: 'raw-text-payload' })] },
    });

    expect(wrapper.text()).toContain('raw-text-payload');
  });
});
