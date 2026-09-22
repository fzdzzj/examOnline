/**
 * 切屏 / 失焦归并状态机单测（阶段 22 第 3 片，硬约定 9）。
 *
 * 核心断言：**一次「离开」只产生一条上报**——切标签页时 blur 与
 * visibilitychange-hidden 会先后到达，不归并就是虚报条数；以及回归信号
 * 重置离开轮次。纯函数直调，无 DOM、无计时器。
 */
import { describe, expect, it } from 'vitest';

import { INITIAL_BEHAVIOR_STATE, reduceBehaviorSignal } from '@/utils/behaviorEvents';

describe('reduceBehaviorSignal：一次离开一条上报', () => {
  it('切标签页（blur 先、hidden 后）：只报 WINDOW_BLUR 一条，hidden 被归并', () => {
    const first = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'window-blur');
    expect(first.event).toBe('WINDOW_BLUR');

    const second = reduceBehaviorSignal(first.state, 'visibilitychange-hidden');
    expect(second.event).toBeNull();
    expect(second.state).toBe(first.state);
  });

  it('切标签页（hidden 先、blur 后）：只报 SWITCH_SCREEN 一条，blur 被归并', () => {
    const first = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'visibilitychange-hidden');
    expect(first.event).toBe('SWITCH_SCREEN');

    const second = reduceBehaviorSignal(first.state, 'window-blur');
    expect(second.event).toBeNull();
  });

  it('只切走窗口（页面仍可见，只有 blur）：报 WINDOW_BLUR', () => {
    const result = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'window-blur');
    expect(result.event).toBe('WINDOW_BLUR');
  });

  it('回归（visible 或 focus）重置离开轮次：下一轮离开再报一条', () => {
    const away = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'visibilitychange-hidden');
    expect(away.event).toBe('SWITCH_SCREEN');

    const back = reduceBehaviorSignal(away.state, 'visibilitychange-visible');
    expect(back.event).toBeNull();
    expect(back.state.awayReported).toBe(false);

    const awayAgain = reduceBehaviorSignal(back.state, 'window-blur');
    expect(awayAgain.event).toBe('WINDOW_BLUR');
  });

  it('focus 也是回归信号（窗口切回但标签页没变）', () => {
    const away = reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'window-blur');
    const back = reduceBehaviorSignal(away.state, 'window-focus');
    expect(back.state.awayReported).toBe(false);

    const awayAgain = reduceBehaviorSignal(back.state, 'visibilitychange-hidden');
    expect(awayAgain.event).toBe('SWITCH_SCREEN');
  });

  it('回归信号本身永不产生上报', () => {
    expect(
      reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'visibilitychange-visible').event
    ).toBeNull();
    expect(reduceBehaviorSignal(INITIAL_BEHAVIOR_STATE, 'window-focus').event).toBeNull();
  });

  it('纯函数性：传入状态对象不被原地修改', () => {
    const original = { awayReported: false };
    reduceBehaviorSignal(original, 'visibilitychange-hidden');
    expect(original.awayReported).toBe(false);
  });
});
