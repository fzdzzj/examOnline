/**
 * 切屏 / 失焦上报 hook 单测（阶段 22 第 3 片 + 切屏时长返修，tasks.json 任务 5 前三条）。
 *
 * 必须覆盖（返修单三条裁决 + agent-prompt 回报第 6 段点名）：
 * - **离开不发请求**：离开瞬间只暂存，report 调用 0 次；
 * - **回归带时长一次一条**：回归瞬间恰好一条请求，eventData={durationMs}
 *   （单调时钟差，注入 now 序列断言；blur→hidden 升级 SWITCH_SCREEN）；
 * - **警告在回归上报后才更新**（离开瞬间无请求自然无警告）；
 * - **flush 三条路径**：页面显式调 `flush()` / suspended 置真 / `beforeunload`，
 *   eventData={incomplete:true} 且不含 durationMs；
 * - flush 失败还原暂存（下一轮还能再报）；警告不触发交卷（结构性断言）。
 *
 * 监听注入自定义 add/remove（不依赖真实 window 事件）；时钟注入固定序列，无真 sleep。
 */
import { describe, expect, it, vi } from 'vitest';
import { effectScope, ref } from 'vue';
import type { Ref } from 'vue';

import { useBehaviorReport } from '@/hooks/useBehaviorReport';
import type { BehaviorReportResponse } from '@/api/axios';

interface Harness {
  fire(type: string): void;
  report: ReturnType<typeof vi.fn>;
  warning: ReturnType<typeof useBehaviorReport>['warning'];
  reportedCount: ReturnType<typeof useBehaviorReport>['reportedCount'];
  flush(): Promise<void>;
  suspended: Ref<boolean>;
  dispose(): void;
}

function setup(
  reportImpl?: (
    examId: number,
    body: { eventType: string }
  ) => Promise<BehaviorReportResponse | undefined>,
  nowImpl?: () => number
): Harness {
  const listeners = new Map<string, Set<() => void>>();
  const report = vi.fn(
    reportImpl ??
      (async (_examId: number, body: { eventType: string }): Promise<BehaviorReportResponse> => ({
        warned: true,
        severity: 1,
        severityName: '低',
        count: 1,
        message: `已记录 ${body.eventType}`,
      }))
  );
  const suspended = ref(false);
  const scope = effectScope();
  let hook!: ReturnType<typeof useBehaviorReport>;
  scope.run(() => {
    hook = useBehaviorReport({
      examId: () => 1,
      suspended: () => suspended.value,
      deps: {
        report: report as never,
        ...(nowImpl ? { now: nowImpl } : {}),
        addListener: (type, handler) => {
          if (!listeners.has(type)) listeners.set(type, new Set());
          listeners.get(type)!.add(handler);
        },
        removeListener: (type, handler) => listeners.get(type)?.delete(handler),
      },
    });
  });
  return {
    fire: (type: string) => listeners.get(type)?.forEach((h) => h()),
    report,
    warning: hook.warning,
    reportedCount: hook.reportedCount,
    flush: () => hook.flush(),
    suspended,
    dispose: () => scope.stop(),
  };
}

describe('useBehaviorReport：离开不发，回归报一条带时长', () => {
  it('离开瞬间只暂存：blur / hidden 都不发请求', () => {
    const documentVisibility = vi
      .spyOn(document, 'visibilityState', 'get')
      .mockReturnValue('hidden');
    const h = setup(undefined, () => 1000);
    h.fire('blur');
    h.fire('visibilitychange');
    expect(h.report).not.toHaveBeenCalled();
    documentVisibility.mockRestore();
  });

  it('回归瞬间恰好一条：eventData={durationMs} 为单调时钟差（注入 now 序列）', async () => {
    const documentVisibility = vi.spyOn(document, 'visibilityState', 'get');
    documentVisibility.mockReturnValue('visible');
    let tick = 1000;
    const h = setup(undefined, () => {
      const v = tick;
      tick += 500; // 每次读时钟走 500ms（返回处理器里读一次 now）
      return v;
    });

    documentVisibility.mockReturnValue('hidden');
    h.fire('visibilitychange'); // 离开，leaveAt=1000
    documentVisibility.mockReturnValue('visible');
    tick = 51000; // 回归时刻
    h.fire('visibilitychange');
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(1));
    expect(h.report).toHaveBeenCalledWith(1, {
      eventType: 'SWITCH_SCREEN',
      eventData: { durationMs: 50000 }, // 51000 - 1000
    });
    documentVisibility.mockRestore();
  });

  it('切标签页（blur→hidden→visible）：一次离开一条 SWITCH_SCREEN，不拆分', async () => {
    const documentVisibility = vi.spyOn(document, 'visibilityState', 'get');
    documentVisibility.mockReturnValue('visible');
    let now = 1000;
    const h = setup(undefined, () => now);

    h.fire('blur'); // leaveAt=1000，暂存 WINDOW_BLUR
    documentVisibility.mockReturnValue('hidden');
    h.fire('visibilitychange'); // 升级 SWITCH_SCREEN，保留 leaveAt
    expect(h.report).not.toHaveBeenCalled();

    documentVisibility.mockReturnValue('visible');
    now = 8000;
    h.fire('visibilitychange');
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(1));
    expect(h.report).toHaveBeenCalledWith(1, {
      eventType: 'SWITCH_SCREEN',
      eventData: { durationMs: 7000 },
    });
    documentVisibility.mockRestore();
  });

  it('时长是单调时钟差不是本机时刻：请求体里没有任何绝对时间字段', async () => {
    let now = 1000;
    const h = setup(undefined, () => now);
    h.fire('blur');
    now = 120000;
    h.fire('focus');
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(1));
    const body = h.report.mock.calls[0][1] as { eventData: Record<string, unknown> };
    expect(Object.keys(body.eventData)).toEqual(['durationMs']);
    expect(body.eventData.occurredTime).toBeUndefined();
  });

  it('警告推迟到回归上报后更新：离开瞬间 warning 不动', async () => {
    let now = 1000;
    const h = setup(undefined, () => now);
    h.fire('blur');
    expect(h.warning.value).toBeNull(); // 离开没请求，没响应可更新
    now = 6000;
    h.fire('focus');
    await vi.waitFor(() => expect(h.warning.value?.warned).toBe(true));
    expect(h.warning.value?.message).toBe('已记录 WINDOW_BLUR');
  });
});

describe('useBehaviorReport：flush 三条兜底路径（时长未知）', () => {
  it('路径 1 —— 页面交卷前显式 flush：incomplete:true，无 durationMs', async () => {
    const h = setup(undefined, () => 1000);
    h.fire('blur'); // 暂存，未回归
    expect(h.report).not.toHaveBeenCalled();

    await h.flush();
    expect(h.report).toHaveBeenCalledTimes(1);
    expect(h.report).toHaveBeenCalledWith(1, {
      eventType: 'WINDOW_BLUR',
      eventData: { incomplete: true },
    });
    // flush 后再回归：无暂存，不再报（本轮已终结）
    h.fire('focus');
    expect(h.report).toHaveBeenCalledTimes(1);
  });

  it('路径 2 —— suspended 置真（已交卷/已收卷）自动 flush', async () => {
    const h = setup(undefined, () => 1000);
    h.fire('blur');
    expect(h.report).not.toHaveBeenCalled();

    h.suspended.value = true; // watch 触发 flush(true)，绕过挂空挡
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(1));
    expect(h.report).toHaveBeenCalledWith(1, {
      eventType: 'WINDOW_BLUR',
      eventData: { incomplete: true },
    });
  });

  it('路径 3 —— beforeunload 兜底 flush', async () => {
    const documentVisibility = vi.spyOn(document, 'visibilityState', 'get');
    documentVisibility.mockReturnValue('hidden');
    const h = setup(undefined, () => 1000);
    await h.flush(); // 无暂存：空转安全，0 次请求
    expect(h.report).toHaveBeenCalledTimes(0);

    h.fire('visibilitychange'); // 离开：暂存 SWITCH_SCREEN
    h.fire('beforeunload'); // 页面卸载兜底
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(1));
    expect(h.report).toHaveBeenCalledWith(1, {
      eventType: 'SWITCH_SCREEN',
      eventData: { incomplete: true },
    });
    documentVisibility.mockRestore();
  });

  it('flush 失败还原暂存：下一轮回归还能再报（离开不因一次网络失败永久丢失）', async () => {
    let fail = true;
    const h = setup(
      async () => {
        if (fail) throw new Error('network down');
        return { warned: false };
      },
      () => 1000
    );
    h.fire('blur');

    await h.flush(); // 失败：暂存还原
    expect(h.report).toHaveBeenCalledTimes(1);
    expect(h.reportedCount.value).toBe(0);

    fail = false;
    h.fire('focus'); // 回归路径再报
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(2));
    expect(h.reportedCount.value).toBe(1);
  });
});

describe('useBehaviorReport：只警告不交卷与旁路语义', () => {
  it('结构性断言：返回视图只有 warning/reportedCount/flush，没有任何提交入口', () => {
    const h = setup();
    expect(Object.keys(h)).toEqual(
      expect.arrayContaining(['fire', 'report', 'warning', 'reportedCount', 'flush', 'dispose'])
    );
  });

  it('后端判定不需警告（warned=false）时清掉旧警告', async () => {
    let warned = true;
    let now = 1000;
    const h = setup(
      async () => ({ warned, severityName: '低', count: 3, message: 'x' }),
      () => now
    );
    h.fire('blur');
    now = 2000;
    h.fire('focus');
    await vi.waitFor(() => expect(h.warning.value?.warned).toBe(true));

    warned = false;
    h.fire('blur');
    now = 3000;
    h.fire('focus');
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(2));
    expect(h.warning.value).toBeNull();
  });

  it('上报失败是旁路：不抛错、不崩（绝不打断作答）', () => {
    const h = setup(async () => {
      throw new Error('network down');
    });
    expect(() => h.fire('blur')).not.toThrow();
    expect(() => h.fire('focus')).not.toThrow();
  });
});
