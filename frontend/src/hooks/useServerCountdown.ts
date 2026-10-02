/**
 * 服务端时间倒计时（阶段 22 第 1 片）。
 *
 * ## 为什么长这样
 *
 * 需求把话说死了：**剩余时长必须来自后端**，前端只展示；本地归零只锁定作答，
 * 不判定超时（硬约定 2，spec-delta「倒计时以服务端时间为准」四条场景）。
 * 所以这个 hook 里只允许出现两种时间量：
 *
 * 1. `remainingFromServer()`：**纯读后端字段**（`remainingSeconds`，退化时读
 *    `deadlineTime - serverTime`，两个都是服务端值），全程不掺本地时钟；
 * 2. **单调秒表** `monotonicNow()`：默认 `performance.now()`，只测「距上次后端快照过了
 *   多久」这个**时长**，从不测「现在是几点」这个**时刻**。
 *
 * 关键区分在于：倒计时要往前走就必须知道过了多久，而「过了多久」只能本地测。
 * 用 `Date.now()` 测时长会被学生改表影响（往 future 跳一下就归零、往 past 跳就冻结），
 * 用 `performance.now()` 则不受系统时钟影响——浏览器单调时钟。
 * 反过来，**任何时候都不做 `deadlineTime - Date.now()`**：那等于本地推算考试结束时刻，
 * 正是本 hook 存在的理由所要禁止的事。最终超时判定在后端
 * （`ExamTakingService.buildAnsweringContext` 里 `now.isAfter(deadline)` 就地兜底强制交卷，
 * `ExamSweepService` 定时扫描），前端归零只是体验层锁定。
 */
import { computed, getCurrentScope, onScopeDispose, ref, shallowRef, toValue, watch } from 'vue';
import type { ComputedRef, MaybeRefOrGetter, Ref } from 'vue';

import { COUNTDOWN_TICK_MS } from '@/constants/studentTaking';
import { formatCountdown, serverRemainingOf } from '@/utils/studentTaking';

/** 后端答题上下文里与时间有关的字段（`EnterExamResponse` 的子集）。 */
export interface ServerTimeSnapshot {
  serverTime?: string | null;
  deadlineTime?: string | null;
  remainingSeconds?: number | null;
}

/**
 * 只读后端字段算剩余秒数。**不使用任何本地时钟**，可单测直调。
 *
 * 优先 `remainingSeconds`（后端 `Duration.between(now, deadline).getSeconds()` 的结果）。
 * 后端没给时退化到 `deadlineTime - serverTime`：两者都是服务端时间戳，
 * 用同一种解析方式相减得到的**差值**与时区解释无关
 * （`LocalDateTime` 无时区，`Date.parse` 一律按本地时区解析，相减后偏移互相抵消）。
 * 两个来源都拿不到 → null：宁可显示占位，也不退化成 `Date.now()` 推算。
 */
export function remainingFromServer(
  snapshot: ServerTimeSnapshot | null | undefined
): number | null {
  const direct = serverRemainingOf(snapshot?.remainingSeconds);
  if (direct !== null) return direct;

  const serverMs = Date.parse(snapshot?.serverTime ?? '');
  const deadlineMs = Date.parse(snapshot?.deadlineTime ?? '');
  if (!Number.isFinite(serverMs) || !Number.isFinite(deadlineMs)) return null;
  return Math.max(0, Math.floor((deadlineMs - serverMs) / 1000));
}

/** 浏览器单调时钟：不受系统时区/改表影响，只用于测量时长。 */
export function monotonicNow(): number {
  return typeof performance !== 'undefined' ? performance.now() : 0;
}

export interface CountdownEngineOptions {
  /** 注入秒表以便单测；默认 `performance.now()` */
  now?: () => number;
}

export interface CountdownEngine {
  /** 剩余秒数；null=后端未给时间（前端不推算，界面显示占位） */
  peek(): number | null;
  /** 本地是否已归零：只用于「锁定作答 + 提示」，**不是**「后端判定超时」的同义词 */
  expired(): boolean;
  /** 后端是否根本没给时间（给了才能谈锁定；没给时不许自行判定） */
  anchored(): boolean;
}

/**
 * 造一个倒计时引擎：以「拿到后端快照的那一刻」为锚点，
 * 之后每次读值 = 后端剩余秒数 − 锚点之后经过的单调时长。
 * 快照换了（重新拉答题数据）就重新锚定，永远以最新的服务端值为准。
 */
export function createServerCountdownEngine(
  snapshot: ServerTimeSnapshot | null | undefined,
  options: CountdownEngineOptions = {}
): CountdownEngine {
  const now = options.now ?? monotonicNow;
  const base = remainingFromServer(snapshot);
  const anchorAt = now();

  const peek = (): number | null => {
    if (base === null) return null;
    // Math.max(0, …) 防的是注入的秒表往回走（例如测试里手动喂负值），
    // 绝不是「本地时间比服务端晚就顺延」——顺延与否只有后端能决定。
    const elapsedSeconds = Math.floor(Math.max(0, now() - anchorAt) / 1000);
    return Math.max(0, base - elapsedSeconds);
  };

  return {
    peek,
    anchored: () => base !== null,
    expired: () => base !== null && (peek() as number) === 0,
  };
}

export interface UseServerCountdownOptions extends CountdownEngineOptions {
  /** tick 周期（毫秒）；仅影响刷新展示频率，不影响归零判定的口径 */
  tickMs?: number;
}

export interface ServerCountdownView {
  /** 当前剩余秒数（后端值 − 本地经过时长）；null=后端未给 */
  remainingSeconds: Ref<number | null>;
  /** 本地归零：作答入口据此锁定 */
  isExpired: ComputedRef<boolean>;
  /** 后端未返回任何可用时间字段 */
  isUnanchored: ComputedRef<boolean>;
  /** mm:ss / h:mm:ss 展示文案 */
  display: ComputedRef<string>;
}

/**
 * 组件用 hook：source 的时间字段（remainingSeconds / deadlineTime / serverTime）
 * 任一变化（首帧、轮询换新对象、断线重连后重拉、响应式属性原地更新）都重新锚定；
 * 整卷快照里的题目列表等非时间字段再大也不触发重锚定，监听不做深层遍历。
 * 必须在 setup 里调用；作用域销毁时自动停表。
 */
export function useServerCountdown(
  source: MaybeRefOrGetter<ServerTimeSnapshot | null | undefined>,
  options: UseServerCountdownOptions = {}
): ServerCountdownView {
  const { tickMs = COUNTDOWN_TICK_MS, now } = options;
  const remainingSeconds = ref<number | null>(null);
  const engine = shallowRef<CountdownEngine>(createServerCountdownEngine(toValue(source), { now }));

  const advance = (): void => {
    remainingSeconds.value = engine.value.peek();
  };

  // 监听依赖窄化为时间三字段的投影：source 换新引用或任一时间字段原地更新都会
  // 触发重锚定；答题数据里题目列表等非时间字段的任何变动都不在此列，
  // 也就不必为整卷快照付一次深层遍历的响应式开销。
  watch(
    () => {
      const s = toValue(source);
      return [s?.remainingSeconds, s?.deadlineTime, s?.serverTime] as const;
    },
    () => {
      engine.value = createServerCountdownEngine(toValue(source), { now });
      advance();
    },
    { immediate: true }
  );

  const timer = setInterval(advance, tickMs);
  if (getCurrentScope()) onScopeDispose(() => clearInterval(timer));

  return {
    remainingSeconds,
    // 只认 remainingSeconds：engine.expired() 内部走的是非响应式的秒表，
    // 直接放进 computed 会被永久缓存（tick 了也不重算）。归零必须随展示一起刷新。
    isExpired: computed(
      () =>
        engine.value.anchored() && remainingSeconds.value !== null && remainingSeconds.value === 0
    ),
    isUnanchored: computed(() => !engine.value.anchored()),
    display: computed(() => formatCountdown(remainingSeconds.value)),
  };
}
