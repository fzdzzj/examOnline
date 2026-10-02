/**
 * 服务端时间倒计时（阶段 22 第 1 片，硬约定 2 的自动化守卫）。
 *
 * 这一组用例的靶心只有一句话：**前端不得本地推算考试结束时刻**。
 * 所以断言分两类——
 * - 正向：剩余秒数来自后端字段、按"锚点之后经过的时长"递减、归零才 expired；
 * - 反向（更值钱）：把 `Date.now()` 改成任意值、甚至直接让它抛错，倒计时结果不许变。
 *   反向断言是防"以后有人图省事把默认时钟换成本地时刻"的变异守卫。
 *
 * 全部用注入的秒表 / 假定时器，不真 sleep。
 * 另有一道静态源码防线：监听依赖必须窄化到时间三字段投影、不得对整卷快照做深层遍历。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { effectScope, nextTick, reactive, ref } from 'vue';

import {
  createServerCountdownEngine,
  monotonicNow,
  remainingFromServer,
  useServerCountdown,
  type ServerTimeSnapshot,
} from '@/hooks/useServerCountdown';
import useServerCountdownSource from '../useServerCountdown.ts?raw';

describe('remainingFromServer —— 剩余时长只从后端字段来', () => {
  it('优先用后端算好的 remainingSeconds', () => {
    expect(remainingFromServer({ remainingSeconds: 1800 })).toBe(1800);
    // 即便同时给了 deadline/serverTime，也不再自己相减——后端那份是权威
    expect(
      remainingFromServer({
        remainingSeconds: 10,
        serverTime: '2026-01-01T00:00:00',
        deadlineTime: '2026-01-01T09:00:00',
      })
    ).toBe(10);
  });

  it('后端只给时刻时，用两个**服务端**时刻相减（同样不掺本地时间）', () => {
    expect(
      remainingFromServer({
        serverTime: '2026-01-01T10:00:00',
        deadlineTime: '2026-01-01T10:30:00',
      })
    ).toBe(1800);
  });

  it('remainingSeconds 为 0（后端已封闭答卷）是有效值，不当成"没给"', () => {
    expect(remainingFromServer({ remainingSeconds: 0 })).toBe(0);
  });

  it('什么时间字段都没有 → null：宁可界面无倒计时，也不退化成 Date.now() 推算', () => {
    expect(remainingFromServer({})).toBeNull();
    expect(remainingFromServer(null)).toBeNull();
    expect(remainingFromServer(undefined)).toBeNull();
    expect(remainingFromServer({ deadlineTime: '2026-01-01T10:00:00' })).toBeNull();
    expect(
      remainingFromServer({ serverTime: '不是时间', deadlineTime: '2026-01-01T10:00:00' })
    ).toBeNull();
  });

  it('后端给了负数剩余（时钟回拨等异常）按未给处理，不 clamp 成 0 后宣布归零', () => {
    expect(remainingFromServer({ remainingSeconds: -5 })).toBeNull();
  });
});

describe('createServerCountdownEngine —— 锚定 + 单调时长', () => {
  it('锚点即后端值，之后按经过的单调时长递减', () => {
    let clock = 0;
    const engine = createServerCountdownEngine({ remainingSeconds: 100 }, { now: () => clock });
    expect(engine.peek()).toBe(100);
    clock = 4_500;
    expect(engine.peek()).toBe(96); // 4.5s → 只扣 4 整秒
    clock = 99_000;
    expect(engine.peek()).toBe(1);
    clock = 100_000;
    expect(engine.peek()).toBe(0);
  });

  it('永不小于 0，也未大于后端初值', () => {
    let clock = 0;
    const engine = createServerCountdownEngine({ remainingSeconds: 10 }, { now: () => clock });
    clock = 10_000_000;
    expect(engine.peek()).toBe(0);
    clock = -60_000; // 注入的秒表往回走
    expect(engine.peek()).toBe(10);
  });

  it('expired 只在后端给了时间且确实走完时成立；未锚定时永不 expired', () => {
    let clock = 0;
    const unanchored = createServerCountdownEngine({}, { now: () => clock });
    clock = 999_999;
    expect(unanchored.anchored()).toBe(false);
    expect(unanchored.expired()).toBe(false);
    expect(unanchored.peek()).toBeNull();

    let clock2 = 0;
    const anchored = createServerCountdownEngine({ remainingSeconds: 1 }, { now: () => clock2 });
    expect(anchored.expired()).toBe(false);
    clock2 = 1_000;
    expect(anchored.expired()).toBe(true);
  });

  it('后端直接给 0 → 一进来就是锁定态（已交卷/已收卷的封闭答卷）', () => {
    const engine = createServerCountdownEngine({ remainingSeconds: 0 }, { now: () => 0 });
    expect(engine.peek()).toBe(0);
    expect(engine.expired()).toBe(true);
  });

  it('重新锚定：新快照的后端值覆盖旧推算（服务端时间为准，前端不越计越多）', () => {
    let clock = 0;
    const first = createServerCountdownEngine({ remainingSeconds: 100 }, { now: () => clock });
    clock = 60_000;
    expect(first.peek()).toBe(40);
    const second = createServerCountdownEngine({ remainingSeconds: 500 }, { now: () => clock });
    expect(second.peek()).toBe(500);
  });

  it('默认时钟是浏览器单调时钟，不是本地时刻（Date.now 一旦被用上就抛）', () => {
    const original = Date.now;
    Date.now = () => {
      throw new Error('倒计时不得读取本地时刻');
    };
    try {
      const engine = createServerCountdownEngine({ remainingSeconds: 30 });
      expect(engine.peek()).toBe(30);
      expect(engine.expired()).toBe(false);
      expect(engine.peek()).toBe(30);
    } finally {
      Date.now = original;
    }
  });

  it('学生把本机时间改到任意年份，倒计时数值纹丝不动', () => {
    const original = Date.now;
    const engine = createServerCountdownEngine({ remainingSeconds: 120 });
    const before = engine.peek();
    try {
      Date.now = () => new Date('2099-01-01T00:00:00Z').getTime();
      expect(engine.peek()).toBe(before);
      Date.now = () => new Date('1970-01-01T00:00:00Z').getTime();
      expect(engine.peek()).toBe(before);
      expect(engine.expired()).toBe(false);
    } finally {
      Date.now = original;
    }
  });
});

describe('monotonicNow', () => {
  it('无 performance 环境退回 0，不抛（SSR/裁剪环境下不能让答题页整页崩）', () => {
    const original = globalThis.performance;
    // @ts-expect-error 故意造一个没有 performance 的全局
    globalThis.performance = undefined;
    try {
      expect(monotonicNow()).toBe(0);
    } finally {
      globalThis.performance = original;
    }
  });
});

describe('useServerCountdown', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('随秒表推进更新 remainingSeconds 与展示文案', () => {
    vi.useFakeTimers();
    let clock = 0;
    const scope = effectScope();
    const view = scope.run(() =>
      useServerCountdown({ remainingSeconds: 125 }, { now: () => clock, tickMs: 1000 })
    )!;

    expect(view.remainingSeconds.value).toBe(125);
    expect(view.display.value).toBe('02:05');

    clock = 60_000;
    vi.advanceTimersByTime(1000);
    expect(view.remainingSeconds.value).toBe(65);
    expect(view.display.value).toBe('01:05');
    expect(view.isExpired.value).toBe(false);

    clock = 125_000;
    vi.advanceTimersByTime(1000);
    expect(view.remainingSeconds.value).toBe(0);
    expect(view.isExpired.value).toBe(true);

    scope.stop();
  });

  it('后端未返回时间：占位展示 + isUnanchored，不锁定也不判过期', () => {
    vi.useFakeTimers();
    let clock = 0;
    const scope = effectScope();
    const view = scope.run(() => useServerCountdown({}, { now: () => clock, tickMs: 1000 }))!;

    clock = 600_000;
    vi.advanceTimersByTime(1000);
    expect(view.remainingSeconds.value).toBeNull();
    expect(view.display.value).toBe('—');
    expect(view.isExpired.value).toBe(false);
    expect(view.isUnanchored.value).toBe(true);
    scope.stop();
  });

  it('源变化（重拉答题数据）即重新锚定，取最新的服务端剩余', async () => {
    vi.useFakeTimers();
    let clock = 0;
    const source = ref<ServerTimeSnapshot | null>({ remainingSeconds: 90 });
    const scope = effectScope();
    const view = scope.run(() => useServerCountdown(source, { now: () => clock, tickMs: 1000 }))!;

    clock = 30_000;
    vi.advanceTimersByTime(1000);
    expect(view.remainingSeconds.value).toBe(60);

    // vue-query 每次 refetch 都是换新对象；此时后端那份才是权威，本地不倒推
    source.value = { remainingSeconds: 45 };
    await nextTick();
    expect(view.remainingSeconds.value).toBe(45);
    scope.stop();
  });

  it('作用域销毁后停表：不再继续消耗定时器', () => {
    vi.useFakeTimers();
    let clock = 0;
    const scope = effectScope();
    const view = scope.run(() =>
      useServerCountdown({ remainingSeconds: 60 }, { now: () => clock, tickMs: 1000 })
    )!;

    scope.stop();
    clock = 60_000;
    vi.advanceTimersByTime(5_000);
    expect(view.remainingSeconds.value).toBe(60);
  });
});

describe('useServerCountdown 窄化监听 —— 只有时间字段触发重新锚定', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  /** 模拟整卷响应混进 source：时间字段之外还挂着题目列表等大体积非时间字段。 */
  type SnapshotWithQuestions = ServerTimeSnapshot & {
    questions: Array<{ id: number; title: string }>;
  };

  it('非时间字段原地更新不重锚定：剩余秒数按秒表平滑递减，不跳跃不重置', async () => {
    vi.useFakeTimers();
    let clock = 0;
    const source = reactive<SnapshotWithQuestions>({
      remainingSeconds: 100,
      questions: [{ id: 1, title: '题1' }],
    });
    const scope = effectScope();
    const view = scope.run(() => useServerCountdown(source, { now: () => clock, tickMs: 1000 }))!;

    clock = 30_000;
    vi.advanceTimersByTime(1000);
    expect(view.remainingSeconds.value).toBe(70);

    // 原地改题目标题：若监听仍对整卷快照深层遍历，这里会重锚定并把剩余秒数跳回 100
    source.questions[0]!.title = '修改';
    await nextTick();
    expect(view.remainingSeconds.value).toBe(70);

    clock = 31_000;
    vi.advanceTimersByTime(1000);
    expect(view.remainingSeconds.value).toBe(69);
    scope.stop();
  });

  it('时间字段原地更新即重新锚定：remainingSeconds 变 80 后立即取新值', async () => {
    vi.useFakeTimers();
    let clock = 0;
    const source = reactive<ServerTimeSnapshot>({ remainingSeconds: 100 });
    const scope = effectScope();
    const view = scope.run(() => useServerCountdown(source, { now: () => clock, tickMs: 1000 }))!;

    clock = 30_000;
    vi.advanceTimersByTime(1000);
    expect(view.remainingSeconds.value).toBe(70);

    source.remainingSeconds = 80;
    await nextTick();
    expect(view.remainingSeconds.value).toBe(80);
    scope.stop();
  });
});

describe('useServerCountdown 源码词法护栏 —— 监听依赖窄化（静态防线）', () => {
  it('不得对整卷快照做深层遍历监听（防回归标记）', () => {
    expect(useServerCountdownSource).not.toContain('deep: true');
  });

  it('监听依赖窄化为时间三字段投影（remainingSeconds / deadlineTime / serverTime）', () => {
    expect(useServerCountdownSource).toContain('remainingSeconds');
    expect(useServerCountdownSource).toContain('deadlineTime');
    expect(useServerCountdownSource).toContain('serverTime');
  });
});
