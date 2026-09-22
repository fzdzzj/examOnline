/**
 * 切屏 / 失焦事件的纯函数状态机（阶段 22 第 3 片，硬约定 9）。
 *
 * ## 为什么需要状态机
 *
 * `visibilitychange` 与 `blur` 在不同场景下语义重叠：
 * 切到别的标签页会**同时**触发 blur 和 visibilitychange→hidden；
 * 只把窗口切到另一个应用（标签页仍可见）只触发 blur；
 * 系统弹窗、通知中心可能只有 blur。不做归并就会把**一次离开拆成多条**上报——
 * 这正是硬约定 9 明令禁止的虚报。
 *
 * ## 归并规则（一次「离开」只产生一条上报）
 *
 * - `visibilitychange-hidden`：报告 SWITCH_SCREEN（最确证的离开信号）；
 * - `window-blur`：若本次离开还没报过（窗口切走但页面仍可见），报告 WINDOW_BLUR；
 *   若已经报过（同一离开里 blur 在 hidden 之前触发），**不重复报**；
 * - `visible` / `focus`（任一回归信号）：结束本次离开，下一轮重新计。
 *
 * 除此之外的任何推断（数次数、算时长阈值、抬严重度）都不在这里——
 * 次数与严重度由后端采集策略决定，前端只负责「确证的事件、一条不多」。
 */

/** DOM 侧信号（监听层归一化后的四种，见 `useBehaviorReport`）。 */
export type DomSignal =
  'visibilitychange-hidden' | 'visibilitychange-visible' | 'window-blur' | 'window-focus';

/** 归并状态：`away` 记录「本轮离开是否已有上报」。 */
export interface BehaviorEpisodeState {
  awayReported: boolean;
}

export const INITIAL_BEHAVIOR_STATE: BehaviorEpisodeState = { awayReported: false };

export interface BehaviorSignalResult {
  /** 归并后的新状态（纯函数：原状态不动）。 */
  state: BehaviorEpisodeState;
  /** 该产生的上报类型；null = 归并掉或无信号，不发请求。 */
  event: 'SWITCH_SCREEN' | 'WINDOW_BLUR' | null;
}

/** 纯函数：一条 DOM 信号 + 当前状态 → 是否上报、报什么、状态怎么迁移。可单测直调。 */
export function reduceBehaviorSignal(
  state: BehaviorEpisodeState,
  signal: DomSignal
): BehaviorSignalResult {
  switch (signal) {
    case 'visibilitychange-hidden':
      if (state.awayReported) {
        // 同一次离开里 blur 先到过：只报已报的那条，不拆第二条
        return { state, event: null };
      }
      return { state: { awayReported: true }, event: 'SWITCH_SCREEN' };
    case 'window-blur':
      if (state.awayReported) {
        return { state, event: null };
      }
      return { state: { awayReported: true }, event: 'WINDOW_BLUR' };
    case 'visibilitychange-visible':
    case 'window-focus':
      // 回归：结束本轮离开。哪怕本轮什么都没报（比如焦点在页面间快速切换），也归零重来
      return { state: INITIAL_BEHAVIOR_STATE, event: null };
    default: {
      // 穷尽性守卫：未来加信号类型时这里会编译报错，而不是静默漏报/多报
      const exhaustive: never = signal;
      void exhaustive;
      return { state, event: null };
    }
  }
}
