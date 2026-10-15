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
import type { FullScreenPort } from '@/hooks/useBehaviorReport';
import type { BehaviorReportResponse } from '@/api/axios';

interface Harness {
  fire(type: string, event?: Event): void;
  report: ReturnType<typeof vi.fn>;
  warning: ReturnType<typeof useBehaviorReport>['warning'];
  reportedCount: ReturnType<typeof useBehaviorReport>['reportedCount'];
  flush(): Promise<void>;
  suspended: Ref<boolean>;
  view: ReturnType<typeof useBehaviorReport>;
  dispose(): void;
}

/** 阶段 3 守卫用例的注入项：策略源 + 全屏端口（生产默认读 document.fullscreenElement）。 */
interface GuardOptions {
  antiCheat?: () => unknown;
  fullscreen?: FullScreenPort;
}

function setup(
  reportImpl?: (
    examId: number,
    body: { eventType: string }
  ) => Promise<BehaviorReportResponse | undefined>,
  nowImpl?: () => number,
  guard?: GuardOptions
): Harness {
  const listeners = new Map<string, Set<(event: Event) => void>>();
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
      ...(guard?.antiCheat ? { antiCheat: guard.antiCheat } : {}),
      deps: {
        report: report as never,
        ...(nowImpl ? { now: nowImpl } : {}),
        ...(guard?.fullscreen ? { fullscreen: guard.fullscreen } : {}),
        addListener: (type, handler) => {
          if (!listeners.has(type)) listeners.set(type, new Set());
          listeners.get(type)!.add(handler as (event: Event) => void);
        },
        removeListener: (type, handler) =>
          listeners.get(type)?.delete(handler as (event: Event) => void),
      },
    });
  });
  return {
    fire: (type: string, event?: Event) => listeners.get(type)?.forEach((h) => h(event as Event)),
    report,
    warning: hook.warning,
    reportedCount: hook.reportedCount,
    flush: () => hook.flush(),
    suspended,
    view: hook,
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

/** 全屏端口假体：可读的「当前是否全屏」+ 可观测的进入请求。 */
function fakeFullscreen(initial = false) {
  let active = initial;
  const request = vi.fn(async () => {
    active = true;
  });
  return {
    port: { active: () => active, request } as FullScreenPort,
    request,
    setActive: (value: boolean) => {
      active = value;
    },
  };
}

function clipboardEvent(type: string): Event {
  return new Event(type, { cancelable: true });
}

function policy(on: { forbidCopy?: boolean; fullScreen?: boolean }) {
  return () => on;
}

describe('useBehaviorReport：禁复制粘贴守卫（forbidCopy，只警告不交卷）', () => {
  it.each(['copy', 'cut', 'paste', 'contextmenu'])(
    '开启后 %s 被 preventDefault 并旁路上报 COPY_PASTE（eventData.action 为事件原名）',
    async (type) => {
      const h = setup(undefined, () => 1000, {
        antiCheat: policy({ forbidCopy: true }),
      });
      const event = clipboardEvent(type);
      h.fire(type, event);

      expect(event.defaultPrevented).toBe(true);
      await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(1));
      expect(h.report).toHaveBeenCalledWith(1, {
        eventType: 'COPY_PASTE',
        eventData: { action: type },
      });
      h.dispose();
    }
  );

  it('未配置 antiCheatConfig：剪贴板事件原样放行、零上报（绝不误拦）', async () => {
    const h = setup(undefined, () => 1000);
    const event = clipboardEvent('copy');
    h.fire('copy', event);

    expect(event.defaultPrevented).toBe(false);
    expect(h.report).not.toHaveBeenCalled();
    h.dispose();
  });

  it('只开全屏、未开禁止复制粘贴：剪贴板事件仍放行（两个开关互不串门）', async () => {
    const h = setup(undefined, () => 1000, { antiCheat: policy({ fullScreen: true }) });
    const event = clipboardEvent('paste');
    h.fire('paste', event);

    expect(event.defaultPrevented).toBe(false);
    expect(h.report).not.toHaveBeenCalled();
    h.dispose();
  });

  it('答卷已封闭（suspended）：不拦不上报（守卫只在作答中生效）', async () => {
    const h = setup(undefined, () => 1000, { antiCheat: policy({ forbidCopy: true }) });
    h.suspended.value = true;
    const event = clipboardEvent('copy');
    h.fire('copy', event);

    expect(event.defaultPrevented).toBe(false);
    expect(h.report).not.toHaveBeenCalled();
    h.dispose();
  });

  it('上报失败仍已拦截：不抛错、不重复上报（旁路取舍与切屏同构）', async () => {
    const h = setup(
      async () => {
        throw new Error('network down');
      },
      () => 1000,
      { antiCheat: policy({ forbidCopy: true }) }
    );
    const event = clipboardEvent('copy');
    expect(() => h.fire('copy', event)).not.toThrow();
    expect(event.defaultPrevented).toBe(true);
    h.dispose();
  });
});

describe('useBehaviorReport：全屏作答守卫（fullScreen，只警告不交卷）', () => {
  it('退出全屏：上报 FULLSCREEN_EXIT 并给出友好提示（不触发任何交卷入口）', async () => {
    const fs = fakeFullscreen(true);
    const h = setup(undefined, () => 1000, {
      antiCheat: policy({ fullScreen: true }),
      fullscreen: fs.port,
    });
    expect(h.view.fullScreenActive.value).toBe(true);

    fs.setActive(false);
    h.fire('fullscreenchange');

    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(1));
    expect(h.report).toHaveBeenCalledWith(1, {
      eventType: 'FULLSCREEN_EXIT',
      eventData: { action: 'exit' },
    });
    expect(h.view.fullScreenActive.value).toBe(false);
    expect(h.view.guardNotice.value).toContain('全屏');
    h.dispose();
  });

  it('进入全屏：不上报、清掉提示（不谎报一次退出）', async () => {
    const fs = fakeFullscreen(false);
    const h = setup(undefined, () => 1000, {
      antiCheat: policy({ fullScreen: true }),
      fullscreen: fs.port,
    });
    expect(h.view.fullScreenActive.value).toBe(false);

    fs.setActive(true);
    h.fire('fullscreenchange');

    expect(h.report).not.toHaveBeenCalled();
    expect(h.view.fullScreenActive.value).toBe(true);
    expect(h.view.guardNotice.value).toBeNull();
    h.dispose();
  });

  it('未开全屏作答：退出全屏不上报（策略关闭即零采集）', () => {
    const fs = fakeFullscreen(true);
    const h = setup(undefined, () => 1000, {
      antiCheat: policy({ forbidCopy: true }),
      fullscreen: fs.port,
    });
    fs.setActive(false);
    h.fire('fullscreenchange');

    expect(h.report).not.toHaveBeenCalled();
    h.dispose();
  });

  it('答卷已封闭：退出全屏不拦不上报', () => {
    const fs = fakeFullscreen(true);
    const h = setup(undefined, () => 1000, {
      antiCheat: policy({ fullScreen: true }),
      fullscreen: fs.port,
    });
    h.suspended.value = true;
    fs.setActive(false);
    h.fire('fullscreenchange');

    expect(h.report).not.toHaveBeenCalled();
    h.dispose();
  });

  it('enterFullScreen：策略开启才请求全屏；浏览器拒绝时静默吸收（绝不打断作答）', async () => {
    const fs = fakeFullscreen(false);
    const h = setup(undefined, () => 1000, {
      antiCheat: policy({ fullScreen: true }),
      fullscreen: fs.port,
    });
    await h.view.enterFullScreen();
    expect(fs.request).toHaveBeenCalledTimes(1);

    const rejecting = setup(undefined, () => 1000, {
      antiCheat: policy({ fullScreen: true }),
      fullscreen: {
        active: () => false,
        request: async () => {
          throw new Error('Fullscreen request denied');
        },
      },
    });
    await expect(rejecting.view.enterFullScreen()).resolves.toBeUndefined();
    h.dispose();
    rejecting.dispose();
  });

  it('策略未开启时 enterFullScreen 不发请求（入口不产生越界行为）', async () => {
    const fs = fakeFullscreen(false);
    const h = setup(undefined, () => 1000, { fullscreen: fs.port });
    await h.view.enterFullScreen();
    expect(fs.request).not.toHaveBeenCalled();
    h.dispose();
  });

  it('dismissGuardNotice 清空提示（学生确认后不再挂着）', async () => {
    const fs = fakeFullscreen(true);
    const h = setup(undefined, () => 1000, {
      antiCheat: policy({ fullScreen: true }),
      fullscreen: fs.port,
    });
    fs.setActive(false);
    h.fire('fullscreenchange');
    await vi.waitFor(() => expect(h.view.guardNotice.value).not.toBeNull());

    h.view.dismissGuardNotice();
    expect(h.view.guardNotice.value).toBeNull();
    h.dispose();
  });
});
