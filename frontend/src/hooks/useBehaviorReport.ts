/**
 * 切屏 / 失焦检测与上报（阶段 22 第 3 片，tasks.json 任务 5 前三条）。
 *
 * ## 它守的边界
 *
 * 1. **只警告 + 记录，绝不交卷**（硬约定 8 / spec 既有决策）：本 hook 的输出只有
 *    「后端返回的警告信息」，没有任何一个函数能触达交卷——是否作废由教师事后依据
 *    行为日志判定，前端不做「切屏 N 次自动交卷」。
 * 2. **一次离开一条上报**：DOM 信号先经 `reduceBehaviorSignal` 纯函数归并
 *    （切标签页会同时来 blur + hidden，不归并就是虚报条数），再发请求。
 *    纯函数单测打归并规则，本 hook 单测打接线与「警告不触发交卷」。
 * 3. **不传 severity、不发明事件类型**：请求体只有后端 `BehaviorReportRequest`
 *    有的三个字段（eventType 必填 + eventData + occurredTime）。occurredTime
 *    也**不发**——那是学生本机时钟，学生改表就能伪造时间线；事件时刻由后端
 *    `record` 用服务器时间落库，时间线才可信。
 * 4. **上报是尽力而为**：断线时上报失败只静默记录失败数（下次离开还会再报），
 *    绝不阻塞作答、绝不因上报失败弹错误打断学生——行为采集是旁路，
 *    这个取舍与后端 `BehaviorEventCollectService`「旁路不改变主链路」同构。
 */
import { getCurrentScope, onScopeDispose, ref, toValue } from 'vue';
import type { MaybeRefOrGetter } from 'vue';

import type { BehaviorReportResponse, JsonNode } from '@/api/axios';
import { INITIAL_BEHAVIOR_STATE, reduceBehaviorSignal } from '@/utils/behaviorEvents';
import type { BehaviorEpisodeState, DomSignal } from '@/utils/behaviorEvents';

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
  /** 后端已封闭（已交卷/已收卷）后不再上报。 */
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
  };
}

export interface BehaviorReportView {
  /** 最近一次后端确认的警告信息（驱动界面 Alert / message）。 */
  warning: ReturnType<typeof ref<BehaviorWarning | null>>;
  /** 已成功发出的上报条数（实测会 ≠ 本地离开次数：归并 + 断线丢弃都让前者更小）。 */
  reportedCount: ReturnType<typeof ref<number>>;
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
}): ListenerPair[] {
  return [
    { type: 'visibilitychange', handler: handlers.onVisibility, target: 'document' },
    { type: 'blur', handler: handlers.onBlur, target: 'window' },
    { type: 'focus', handler: handlers.onFocus, target: 'window' },
  ];
}

export function useBehaviorReport(options: UseBehaviorReportOptions): BehaviorReportView {
  const { examId, suspended, deps } = options;

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

  async function onSignal(signal: DomSignal): Promise<void> {
    const id = toValue(examId);
    const active = Number.isInteger(id) && id > 0 && !toValue(suspended);
    const { state, event } = reduceBehaviorSignal(episode, signal);
    episode = state;
    if (!active || event === null) return;

    try {
      const data = await deps.report(id, { eventType: event });
      reportedCount.value += 1;
      // 只转发后端的警告判定；warned=false 时清掉旧警告（学生回来了，别挂着吓人）
      warning.value =
        data && data.warned
          ? {
              warned: true,
              severityName: data.severityName ?? null,
              count: data.count ?? null,
              message: data.message ?? null,
            }
          : null;
    } catch {
      // 旁路取舍：上报失败不打断作答（见文件头第 4 条），计数不加、警告不动
    }
  }

  function onVisibilityChange(): void {
    const signal = makeSignal();
    if (signal !== null) void onSignal(signal);
  }

  const handlers = {
    onVisibility: onVisibilityChange,
    onBlur: () => void onSignal('window-blur'),
    onFocus: () => void onSignal('window-focus'),
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

  return { warning, reportedCount };
}
