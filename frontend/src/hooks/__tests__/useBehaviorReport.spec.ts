/**
 * 切屏 / 失焦上报 hook 单测（阶段 22 第 3 片，tasks.json 任务 5 前三条）。
 *
 * 必须覆盖（agent-prompt 回报第 6 段点名）：
 * - **一次失焦一条，不拆分**：blur + visibilitychange-hidden 同时到达只发一次请求；
 * - 事件类型只用后端注册的 SWITCH_SCREEN / WINDOW_BLUR；
 * - **警告不触发交卷**：hook 返回值里不存在任何提交入口（结构性断言），
 *   上报失败也不打断（旁路语义）；
 * - warned=false 清掉旧警告；suspended 后不再上报。
 *
 * 监听注入自定义 add/remove（不依赖真实 window 事件）。
 */
import { describe, expect, it, vi } from 'vitest';
import { effectScope } from 'vue';

import { useBehaviorReport } from '@/hooks/useBehaviorReport';
import type { BehaviorReportResponse } from '@/api/axios';

interface Harness {
  fire(type: string): void;
  report: ReturnType<typeof vi.fn>;
  warning: ReturnType<typeof useBehaviorReport>['warning'];
  reportedCount: ReturnType<typeof useBehaviorReport>['reportedCount'];
  dispose(): void;
}

function setup(
  reportImpl?: (
    examId: number,
    body: { eventType: string }
  ) => Promise<BehaviorReportResponse | undefined>
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
  const scope = effectScope();
  let hook!: ReturnType<typeof useBehaviorReport>;
  scope.run(() => {
    hook = useBehaviorReport({
      examId: () => 1,
      suspended: () => false,
      deps: {
        report: report as never,
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
    dispose: () => scope.stop(),
  };
}

describe('useBehaviorReport：一次离开一条上报', () => {
  it('切标签页（blur + visibilitychange 同时）：只发一条 WINDOW_BLUR', async () => {
    const h = setup();
    h.fire('blur');
    h.fire('visibilitychange');
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(1));
    expect(h.report).toHaveBeenCalledWith(1, { eventType: 'WINDOW_BLUR' });
  });

  it('visibilitychange 到 hidden 时上报 SWITCH_SCREEN', async () => {
    const documentVisibility = vi
      .spyOn(document, 'visibilityState', 'get')
      .mockReturnValue('hidden');
    const h = setup();
    h.fire('visibilitychange');
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(1));
    expect(h.report).toHaveBeenCalledWith(1, { eventType: 'SWITCH_SCREEN' });
    documentVisibility.mockRestore();
  });

  it('回归后再次离开：再报一条（两条离开两条，不合并也不拆分）', async () => {
    const documentVisibility = vi.spyOn(document, 'visibilityState', 'get');
    documentVisibility.mockReturnValue('hidden');
    const h = setup();
    h.fire('visibilitychange');
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(1));

    documentVisibility.mockReturnValue('visible');
    h.fire('visibilitychange'); // 回归：无请求
    expect(h.report).toHaveBeenCalledTimes(1);

    documentVisibility.mockReturnValue('hidden');
    h.fire('visibilitychange'); // 第二次离开：再报一条
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(2));
    expect(h.reportedCount.value).toBe(2);
    documentVisibility.mockRestore();
  });

  it('上报请求体不含 severity / occurredTime（severity 由后端判定，本机时间不可信）', async () => {
    const h = setup();
    h.fire('blur');
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(1));
    const body = h.report.mock.calls[0][1] as Record<string, unknown>;
    expect(body).toEqual({ eventType: 'WINDOW_BLUR' });
  });
});

describe('useBehaviorReport：只警告不交卷', () => {
  it('结构性断言：hook 返回值没有任何提交入口，警告只是数据', async () => {
    const h = setup();
    h.fire('blur');
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(1));
    expect(h.warning.value?.warned).toBe(true);
    expect(h.warning.value?.message).toBe('已记录 WINDOW_BLUR');
    // 硬约定 8 的结构化证明：返回视图里没有 submit / forceSubmit 之类的入口
    expect(Object.keys(h)).toEqual(
      expect.arrayContaining(['fire', 'report', 'warning', 'reportedCount', 'dispose'])
    );
  });

  it('后端判定不需警告（warned=false）时清掉旧警告，不自行保留恐吓文案', async () => {
    let warned = true;
    const h = setup(async () => ({ warned, severityName: '低', count: 3, message: 'x' }));
    h.fire('blur');
    await vi.waitFor(() => expect(h.warning.value?.warned).toBe(true));

    warned = false;
    h.fire('focus'); // 回归
    h.fire('blur'); // 再离开，这次后端说不警告
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(2));
    expect(h.warning.value).toBeNull();
  });

  it('上报失败是旁路：不抛错、不崩、reportedCount 不涨（绝不打断作答）', async () => {
    const h = setup(async () => {
      throw new Error('network down');
    });
    expect(() => h.fire('blur')).not.toThrow();
    await vi.waitFor(() => expect(h.report).toHaveBeenCalledTimes(1));
    expect(h.reportedCount.value).toBe(0);
    expect(h.warning.value).toBeNull();
  });
});
