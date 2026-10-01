/**
 * 切屏 / 失焦事件的纯函数状态机（阶段 22 第 3 片 + 切屏时长返修，硬约定 9）。
 *
 * ## 上报时机（返修裁决）
 *
 * **离开瞬间不报，回归瞬间才报一条**——离开时还不知道这次离开有多长，
 * 立刻上报等于把「离开时长」这个最重要的信号永久丢掉。所以状态机改为：
 *
 * 1. **离开信号**（`visibilitychange-hidden` / `window-blur`）：只**暂存**
 *    `{类型, 离开时刻}`，不发请求；
 * 2. **回归信号**（`visible` / `focus`）：把暂存事件上报，
 *    `eventData = { durationMs }`——时长 = 回归时刻 − 离开时刻，
 *    用**单调时钟**测（`performance.now()`，学生改本机表拨不动它）；
 * 3. **兜底 flush**（交卷前 / suspended / beforeunload）：暂存事件还在就立刻上报，
 *    `eventData = { incomplete: true }` 标注「时长未知」（离开没有得到回归，
 *    测量被异常终结，不谎报一个时长）。
 *
 * ## 归并规则（一次「离开」仍然只产生一条上报）
 *
 * - 同一轮离开里 blur 与 hidden 重叠到达（切标签页就是两者同时来）：**只暂存一条**。
 *   blur 先到时暂存 WINDOW_BLUR，随后 hidden 到达会把它**升级**成 SWITCH_SCREEN
 *   （hidden 是「页面真的不可见了」的更确证信号，保留原离开时刻不重置）——
 *   这样切标签页依旧报 SWITCH_SCREEN，只切走窗口（页面仍可见）报 WINDOW_BLUR；
 * - 回归信号清空本轮，下一轮离开重新计。
 *
 * 次数、严重度仍由后端采集策略决定；前端只负责「确证的事件、一条不多、带上时长」。
 */

/** DOM 侧信号（监听层归一化后的四种，见 `useBehaviorReport`）。 */
export type DomSignal =
  'visibilitychange-hidden' | 'visibilitychange-visible' | 'window-blur' | 'window-focus';

/** 上报的事件类型（只登记后端已注册采集策略的两个值）。 */
export type BehaviorEventType = 'SWITCH_SCREEN' | 'WINDOW_BLUR';

/** 暂存中的离开事件：类型 + 离开时刻（单调时钟读数，不是墙上时间）。 */
export interface StashedLeave {
  event: BehaviorEventType;
  leaveAt: number;
}

/** 归并状态：`pending` 为本轮离开的暂存事件；null = 当前没有待上报的离开。 */
export interface BehaviorEpisodeState {
  pending: StashedLeave | null;
}

export const INITIAL_BEHAVIOR_STATE: BehaviorEpisodeState = { pending: null };

export interface BehaviorSignalResult {
  /** 归并后的新状态（纯函数：原状态不动）。 */
  state: BehaviorEpisodeState;
  /** 该上报的事件类型；null = 无（继续暂存或无事发生）。 */
  event: BehaviorEventType | null;
  /** 回归上报时的离开时长（毫秒，单调时钟差）；暂存与 flush 场景为 null。 */
  durationMs: number | null;
}

/**
 * 纯函数：一条 DOM 信号 + 当前状态 + 当前单调时刻 → 暂存 / 上报决策。
 * `now` 由调用方注入（生产 `performance.now()`，单测固定值），本函数不取时钟。
 */
export function reduceBehaviorSignal(
  state: BehaviorEpisodeState,
  signal: DomSignal,
  now: number
): BehaviorSignalResult {
  switch (signal) {
    case 'visibilitychange-hidden':
      if (state.pending === null) {
        return {
          state: { pending: { event: 'SWITCH_SCREEN', leaveAt: now } },
          event: null,
          durationMs: null,
        };
      }
      if (state.pending.event === 'WINDOW_BLUR') {
        // 同一轮离开里 blur 先到过：hidden 是更确证的离开信号，升级类型、保留原离开时刻
        return {
          state: { pending: { event: 'SWITCH_SCREEN', leaveAt: state.pending.leaveAt } },
          event: null,
          durationMs: null,
        };
      }
      return { state, event: null, durationMs: null };
    case 'window-blur':
      if (state.pending === null) {
        return {
          state: { pending: { event: 'WINDOW_BLUR', leaveAt: now } },
          event: null,
          durationMs: null,
        };
      }
      // 已有暂存（blur 先到或 hidden 先到）：不改，本轮离开仍是那一条
      return { state, event: null, durationMs: null };
    case 'visibilitychange-visible':
    case 'window-focus':
      // 回归：有暂存就上报（时长 = 回归时刻 − 离开时刻），并结束本轮离开
      if (state.pending !== null) {
        return {
          state: INITIAL_BEHAVIOR_STATE,
          event: state.pending.event,
          durationMs: Math.max(0, now - state.pending.leaveAt),
        };
      }
      return { state: INITIAL_BEHAVIOR_STATE, event: null, durationMs: null };
    default: {
      // 穷尽性守卫：未来加信号类型时这里会编译报错，而不是静默漏报/多报
      const exhaustive: never = signal;
      void exhaustive;
      return { state, event: null, durationMs: null };
    }
  }
}

/**
 * 兜底 flush 的纯函数：把仍在上报管途中的暂存事件取出（交卷前 / suspended /
 * beforeunload 调用）。`durationMs` 为 null——离开没有得到回归，测量被异常终结，
 * 上层以 `{ incomplete: true }` 标注「时长未知」，不谎报。
 * flush 失败时调用方把 pending 塞回（返回值里的 state），下一轮还能再报。
 */
export function flushPendingEpisode(state: BehaviorEpisodeState): BehaviorSignalResult {
  if (state.pending === null) {
    return { state, event: null, durationMs: null };
  }
  return { state: INITIAL_BEHAVIOR_STATE, event: state.pending.event, durationMs: null };
}
