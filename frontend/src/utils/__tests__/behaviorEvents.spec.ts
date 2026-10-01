/**
 * 切屏 / 失焦归并状态机单测（阶段 22 第 3 片 + 切屏时长返修，硬约定 9）。
 *
 * 覆盖返修三条裁决：
 * - **暂存**：离开信号只存 `{类型, 离开时刻}`，不产生上报；
 * - **回归带时长**：回归上报一条，`durationMs = 回归时刻 − 离开时刻`（注入单调时钟值断言）；
 * - **flush**：暂存被异常终结时取出上报，`durationMs = null`（时长未知，上层标 incomplete）。
 * 以及归并不变式：一次离开仍只一条（blur→hidden 升级 SWITCH_SCREEN、保留原离开时刻）。
 * 纯函数直调，无 DOM、无计时器，时刻全部显式注入。
 */
import { describe, expect, it } from 'vitest';

import {
  INITIAL_BEHAVIOR_STATE,
  flushPendingEpisode,
  reduceBehaviorSignal,
} from '@/utils/behaviorEvents';

describe('reduceBehaviorSignal：离开只暂存不发', () => {
  it('blur 到达：暂存 WINDOW_BLUR + 离开时刻，不产生上报事件', () => {
    const r = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'window-blur', 1000);
    expect(r.event).toBeNull();
    expect(r.durationMs).toBeNull();
    expect(r.state.pending).toEqual({ event: 'WINDOW_BLUR', leaveAt: 1000 });
  });

  it('hidden 到达：暂存 SWITCH_SCREEN + 离开时刻，不产生上报事件', () => {
    const r = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'visibilitychange-hidden', 1000);
    expect(r.event).toBeNull();
    expect(r.state.pending).toEqual({ event: 'SWITCH_SCREEN', leaveAt: 1000 });
  });

  it('同轮 blur→hidden（切标签页）：升级为 SWITCH_SCREEN 且保留原离开时刻，仍不上报', () => {
    const first = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'window-blur', 1000);
    const second = reduceBehaviorSignal(first.state, 'visibilitychange-hidden', 1200);
    expect(second.event).toBeNull();
    expect(second.state.pending).toEqual({ event: 'SWITCH_SCREEN', leaveAt: 1000 });
  });

  it('同轮 hidden→blur：已是更确证的 SWITCH_SCREEN，blur 不改不改时刻', () => {
    const first = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'visibilitychange-hidden', 1000);
    const second = reduceBehaviorSignal(first.state, 'window-blur', 1200);
    expect(second.event).toBeNull();
    expect(second.state.pending).toEqual({ event: 'SWITCH_SCREEN', leaveAt: 1000 });
  });
});

describe('reduceBehaviorSignal：回归上报一条并带时长', () => {
  it('回归上报：durationMs = 回归时刻 − 离开时刻（blur 先暂存再升级的路径同样成立）', () => {
    const leave = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'window-blur', 1000);
    const upgrade = reduceBehaviorSignal(leave.state, 'visibilitychange-hidden', 1000);
    const back = reduceBehaviorSignal(upgrade.state, 'visibilitychange-visible', 47500);
    expect(back.event).toBe('SWITCH_SCREEN');
    expect(back.durationMs).toBe(46500); // 47500 - 1000，单调时钟差
    expect(back.state.pending).toBeNull();
  });

  it('focus 也是回归信号：blur（单独失焦）→ focus 报 WINDOW_BLUR', () => {
    const leave = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'window-blur', 2000);
    const back = reduceBehaviorSignal(leave.state, 'window-focus', 9000);
    expect(back.event).toBe('WINDOW_BLUR');
    expect(back.durationMs).toBe(7000);
  });

  it('回归信号本身无暂存时不产生事件，只复位状态', () => {
    const r = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'visibilitychange-visible', 500);
    expect(r.event).toBeNull();
    expect(r.durationMs).toBeNull();
    expect(r.state.pending).toBeNull();
  });

  it('回归后再次离开：新一轮重新暂存（两条离开两条，不合并也不拆分）', () => {
    const l1 = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'visibilitychange-hidden', 1000);
    const back = reduceBehaviorSignal(l1.state, 'visibilitychange-visible', 6000);
    expect(back.event).toBe('SWITCH_SCREEN');

    const l2 = reduceBehaviorSignal(back.state, 'window-blur', 7000);
    expect(l2.event).toBeNull();
    expect(l2.state.pending).toEqual({ event: 'WINDOW_BLUR', leaveAt: 7000 });
  });

  it('纯函数性：传入状态对象不被原地修改', () => {
    const original: { pending: { event: 'WINDOW_BLUR'; leaveAt: number } | null } = {
      pending: null,
    };
    reduceBehaviorSignal(original as never, 'visibilitychange-hidden', 1000);
    expect(original.pending).toBeNull();
  });
});

describe('flushPendingEpisode：异常终结的离开（时长未知）', () => {
  it('有暂存：取出上报（durationMs=null）并复位状态', () => {
    const leave = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'window-blur', 1000);
    const flushed = flushPendingEpisode(leave.state);
    expect(flushed.event).toBe('WINDOW_BLUR');
    expect(flushed.durationMs).toBeNull(); // 没有回归就不谎报时长，上层标 incomplete
    expect(flushed.state.pending).toBeNull();
  });

  it('无暂存：无事发生', () => {
    const flushed = flushPendingEpisode(INITIAL_BEHAVIOR_STATE);
    expect(flushed.event).toBeNull();
    expect(flushed.state).toBe(INITIAL_BEHAVIOR_STATE);
  });
});
