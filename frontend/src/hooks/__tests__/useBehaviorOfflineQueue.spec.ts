/**
 * 切屏事件离线兜底与补报单测（阶段 4 · 先红后绿）。
 *
 * 规范红线：
 * 1. 上报失败入本地队列（不即发即弃）；
 * 2. online 恢复与草稿 flush 同时机补报；
 * 3. 去重护栏：同 durationMs 不重复上报，守住「切屏归并为一条上报，不得翻倍」红线。
 */
import { describe, expect, it, vi } from 'vitest';
import { effectScope, ref } from 'vue';

import { useBehaviorReport } from '@/hooks/useBehaviorReport';
import { createMemoryBehaviorQueue } from '@/utils/behaviorQueue';
import type { BehaviorReportResponse } from '@/api/axios';

describe('useBehaviorReport 离线兜底队列与去重护栏', () => {
  function setupHarness(reportImpl: ReturnType<typeof vi.fn>, queue = createMemoryBehaviorQueue()) {
    const listeners = new Map<string, Set<() => void>>();
    const suspended = ref(false);
    const scope = effectScope();
    let hook!: ReturnType<typeof useBehaviorReport>;

    scope.run(() => {
      hook = useBehaviorReport({
        examId: () => 10,
        suspended: () => suspended.value,
        deps: {
          report: reportImpl as never,
          queue,
          now: () => 5000,
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
      hook,
      queue,
      dispose: () => scope.stop(),
    };
  }

  it('断网回归上报失败时，事件自动存入离线队列', async () => {
    const reportMock = vi.fn().mockRejectedValue(new Error('Network offline'));
    const queue = createMemoryBehaviorQueue();
    const h = setupHarness(reportMock, queue);

    const docVisibility = vi.spyOn(document, 'visibilityState', 'get');

    // 离开
    docVisibility.mockReturnValue('hidden');
    h.fire('visibilitychange');

    // 回归
    docVisibility.mockReturnValue('visible');
    h.fire('visibilitychange');

    await vi.waitFor(() => expect(reportMock).toHaveBeenCalledTimes(1));
    await vi.waitFor(async () => {
      const queued = await queue.peek(10);
      expect(queued).toHaveLength(1);
      expect(queued[0].eventType).toBe('SWITCH_SCREEN');
      expect(queued[0].durationMs).toBe(0);
    });

    docVisibility.mockRestore();
    h.dispose();
  });

  it('网络恢复（online 事件触发）或 flush 时，离线队列被自动补报并清空', async () => {
    let shouldFail = true;
    const reportMock = vi.fn().mockImplementation(async () => {
      if (shouldFail) throw new Error('Network offline');
      return { warned: false } as BehaviorReportResponse;
    });

    const queue = createMemoryBehaviorQueue();
    const h = setupHarness(reportMock, queue);

    const docVisibility = vi.spyOn(document, 'visibilityState', 'get');
    docVisibility.mockReturnValue('hidden');
    h.fire('visibilitychange');
    docVisibility.mockReturnValue('visible');
    h.fire('visibilitychange');

    await vi.waitFor(() => expect(reportMock).toHaveBeenCalledTimes(1));
    await vi.waitFor(async () => {
      expect(await queue.peek(10)).toHaveLength(1);
    });

    // 网络恢复
    shouldFail = false;
    h.fire('online');

    await vi.waitFor(() => expect(reportMock).toHaveBeenCalledTimes(2));
    await vi.waitFor(async () => {
      expect(await queue.peek(10)).toHaveLength(0);
    });

    docVisibility.mockRestore();
    h.dispose();
  });

  it('去重护栏：同 durationMs 不重复上报，不得翻倍', async () => {
    const reportMock = vi.fn().mockResolvedValue({ warned: false } as BehaviorReportResponse);
    const queue = createMemoryBehaviorQueue();
    const h = setupHarness(reportMock, queue);

    const docVisibility = vi.spyOn(document, 'visibilityState', 'get');
    docVisibility.mockReturnValue('hidden');
    h.fire('visibilitychange');
    docVisibility.mockReturnValue('visible');
    h.fire('visibilitychange');

    await Promise.resolve();
    expect(reportMock).toHaveBeenCalledTimes(1);

    // 手动再次向队列塞入相同 durationMs 并尝试 flushQueue
    await queue.enqueue({
      examId: 10,
      eventType: 'SWITCH_SCREEN',
      durationMs: 0,
    });

    await h.hook.flushQueue();

    // 应该被去重护栏拦截，report 次数仍然是 1
    expect(reportMock).toHaveBeenCalledTimes(1);

    docVisibility.mockRestore();
    h.dispose();
  });
});
