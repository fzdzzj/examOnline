/**
 * 切屏 / 失焦检测与上报（阶段 22 第 3 片 + 切屏时长返修，tasks.json 任务 5 前三条）。
 *
 * ## 它守的边界
 *
 * 1. **只警告 + 记录，绝不交卷**（硬约定 8 / spec 既有决策）：本 hook 的输出只有
 *    「后端返回的警告信息」，没有任何一个函数能触达交卷——是否作废由教师事后依据
 *    行为日志判定，前端不做「切屏 N 次自动交卷」。
 * 2. **离开不报、回归报一条、兜底 flush**（返修裁决）：
 *    - 离开瞬间只**暂存** `{类型, 离开时刻}`（单调时钟），不发请求；
 *    - 回归瞬间上报一条 `eventData = { durationMs }`（时长 = 回归 − 离开，
 *      单调时钟差，学生改本机表拨不动）；
 *    - 三路兜底 flush（页面在交卷前显式调 `flush()` / suspended 置真 /
 *      `beforeunload`）：暂存事件还在就立刻上报 `eventData = { incomplete: true }`
 *      标注时长未知，不谎报。
 *    归并规则（blur 与 hidden 重叠只算一次离开、hidden 升级 SWITCH_SCREEN）在
 *    `reduceBehaviorSignal` 纯函数里，单测打纯函数，本 hook 单测打接线与三条 flush 路径。
 * 3. **不传 severity、不发明事件类型**：请求体只有后端 `BehaviorReportRequest`
 *    有的三个字段（eventType 必填 + eventData + occurredTime）。occurredTime
 *    也**不发**——那是学生本机时钟，学生改表就能伪造时间线；事件时刻由后端
 *    `record` 用服务器时间落库，时间线才可信。eventData 里的 durationMs 是
 *    **单调时钟差值**（相对量），不是本机时刻（绝对量），改表伪造不了它。
 * 4. **上报是尽力而为**：断线时上报失败只静默恢复暂存（下次回归 / flush 还会再报），
 *    绝不阻塞作答、绝不因上报失败弹错误打断学生——行为采集是旁路，
 *    这个取舍与后端 `BehaviorEventCollectService`「旁路不改变主链路」同构。
 */
import { getCurrentScope, onScopeDispose, ref, toValue, watch } from 'vue';
import type { MaybeRefOrGetter } from 'vue';

import type { BehaviorReportResponse, JsonNode } from '@/api/axios';
import {
  INITIAL_BEHAVIOR_STATE,
  flushPendingEpisode,
  reduceBehaviorSignal,
} from '@/utils/behaviorEvents';
import type { BehaviorEpisodeState, BehaviorEventType, DomSignal } from '@/utils/behaviorEvents';

export interface BehaviorWarning {
  /** 后端原样返回：是否建议弹提醒、严重度名称、本次考试累计切屏次数、提示文案 */
  warned: boolean;
  severityName?: string | null;
  count?: number | null;
  message?: string | null;
}

export interface UseBehaviorReportOptions {
  /** 考试 ID；非正整数时不上报（与作答引擎同一挂空挡判据）。 */
  examId: MaybeRefOrGetter<number>;
  /** 后端已封闭（已交卷/已收卷）后不再上报；置真瞬间 flush 未上报的暂存离开。 */
  suspended: MaybeRefOrGetter<boolean>;
  deps: {
    /** 生产绑生成的 `reportBehavior`；单测绑 mock。返回后端警告载荷（或 undefined）。 */
    report: (
      examId: number,
      body: { eventType: string; eventData?: JsonNode }
    ) => Promise<BehaviorReportResponse | undefined>;
    /** 信号源接线（生产 = window/document；单测注入自定义 EventTarget）。 */
    addListener?: (type: string, handler: () => void) => void;
    removeListener?: (type: string, handler: () => void) => void;
    /** 单调时钟（测离开时长）；默认 `performance.now()`，单测注入固定序列。 */
    now?: () => number;
  };
}

export interface BehaviorReportView {
  /** 最近一次后端确认的警告信息（驱动界面 Alert / message）。回归上报后才更新。 */
  warning: ReturnType<typeof ref<BehaviorWarning | null>>;
  /** 已成功发出的上报条数（实测会 ≠ 本地离开次数：归并 + 断线丢弃都让前者更小）。 */
  reportedCount: ReturnType<typeof ref<number>>;
  /** 兜底 flush：把未上报的暂存离开立刻报出（eventData={incomplete:true}）。页面在交卷前调用。 */
  flush(): Promise<void>;
}

/** 事件类型 → DOM 监听目标的接线表（显式，避免隐式全局）。 */
interface ListenerPair {
  type: string;
  handler: () => void;
  target: 'window' | 'document';
}

function listenerPairs(handlers: {
  onVisibility: () => void;
  onBlur: () => void;
  onFocus: () => void;
  onBeforeUnload: () => void;
}): ListenerPair[] {
  return [
    { type: 'visibilitychange', handler: handlers.onVisibility, target: 'document' },
    { type: 'blur', handler: handlers.onBlur, target: 'window' },
    { type: 'focus', handler: handlers.onFocus, target: 'window' },
    { type: 'beforeunload', handler: handlers.onBeforeUnload, target: 'window' },
  ];
}

export function useBehaviorReport(options: UseBehaviorReportOptions): BehaviorReportView {
  const { examId, suspended, deps } = options;

  // 只测「离开了多久」这个时长，不测「现在是几点」（useServerCountdown 同款纪律）
  const nowFn =
    deps.now ?? ((): number => (typeof performance !== 'undefined' ? performance.now() : 0));

  const warning = ref<BehaviorWarning | null>(null);
  const reportedCount = ref(0);

  /** 归并状态机（可变内部态；纯函数负责所有迁移决策）。 */
  let episode: BehaviorEpisodeState = INITIAL_BEHAVIOR_STATE;

  function makeSignal(): DomSignal | null {
    if (typeof document === 'undefined') return null;
    return document.visibilityState === 'hidden'
      ? 'visibilitychange-hidden'
      : 'visibilitychange-visible';
  }

  async function send(
    id: number,
    eventType: BehaviorEventType,
    eventData: JsonNode | undefined
  ): Promise<boolean> {
    try {
      const data = await deps.report(id, { eventType, eventData });
      reportedCount.value += 1;
      // 只转发后端的警告判定；warned=false 时清掉旧警告（别挂着吓人）
      warning.value =
        data && data.warned
          ? {
              warned: true,
              severityName: data.severityName ?? null,
              count: data.count ?? null,
              message: data.message ?? null,
            }
          : null;
      return true;
    } catch {
      // 旁路取舍：上报失败不打断作答（见文件头第 4 条），计数不加、警告不动
      return false;
    }
  }

  /** DOM 信号入口：暂存 / 回归上报。归并决策全在纯函数里。 */
  async function onSignal(signal: DomSignal): Promise<void> {
    const id = toValue(examId);
    const active = Number.isInteger(id) && id > 0 && !toValue(suspended);
    const { state, event, durationMs } = reduceBehaviorSignal(episode, signal, nowFn());
    episode = state;
    if (!active || event === null) return;
    // 回归上报：eventData 只带单调时长（相对量），不带本机时刻（绝对量可伪造）
    await send(id, event, { durationMs });
  }

  /**
   * 兜底 flush：暂存事件还在就立刻上报 `{incomplete:true}`（时长未知——离开
   * 没有得到回归，测量被异常终结）。失败时暂存还原，下一轮回归 / flush 还能再报。
   * 页面调用的公开入口只在答卷未封闭时生效；suspended 置真 / beforeunload 的
   * 内部路径必须能报（那正是「离开没有回归」的兜底场景）。
   */
  async function flush(ignoreSuspension = false): Promise<void> {
    const id = toValue(examId);
    const valid = Number.isInteger(id) && id > 0;
    if (!valid || (toValue(suspended) && !ignoreSuspension)) return;
    const before = episode;
    const { state, event } = flushPendingEpisode(episode);
    episode = state;
    if (event === null) return;
    const ok = await send(id, event, { incomplete: true });
    if (!ok) episode = before; // 还原暂存：这次离开不能因为一次网络失败就永久丢掉
  }

  function onVisibilityChange(): void {
    const signal = makeSignal();
    if (signal !== null) void onSignal(signal);
  }

  const handlers = {
    onVisibility: onVisibilityChange,
    onBlur: () => void onSignal('window-blur'),
    onFocus: () => void onSignal('window-focus'),
    onBeforeUnload: () => void flush(true),
  };

  // 生产默认绑 window/document；单测注入 add/removeListener 打自定义 EventTarget
  const registered: ListenerPair[] = listenerPairs(handlers);

  const useCustom = Boolean(deps.addListener);
  if (useCustom) {
    const add = deps.addListener!;
    const remove = deps.removeListener ?? (() => undefined);
    for (const { type, handler } of registered) add(type, handler);
    if (getCurrentScope()) {
      onScopeDispose(() => {
        for (const { type, handler } of registered) remove(type, handler);
      });
    }
  } else if (typeof window !== 'undefined') {
    for (const { type, handler, target } of registered) {
      const t = target === 'document' ? window.document : window;
      t.addEventListener(type, handler);
    }
    if (getCurrentScope()) {
      onScopeDispose(() => {
        for (const { type, handler, target } of registered) {
          const t = target === 'document' ? window.document : window;
          t.removeEventListener(type, handler);
        }
      });
    }
  }

  // suspended 置真（已交卷/已收卷）瞬间：这是「离开没有回归」的另一条兜底路径，
  // 必须绕过挂空挡判据（否则这条路径永远不会发）
  if (getCurrentScope()) {
    watch(
      () => toValue(suspended),
      (closed) => {
        if (closed) void flush(true);
      }
    );
  }

  return { warning, reportedCount, flush };
}
