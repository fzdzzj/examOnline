import { computed, effectScope, ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';

import type { MonitorOverviewResponse } from '@/api/axios';
import { isMonitorConsumerTab, MONITOR_POLLING_INTERVAL_MS } from '@/constants/monitor';
import { createMonitorQueryOptions } from '@/hooks/useExamMonitor';
import { usePageVisibility } from '@/hooks/usePageVisibility';

const MOCK_OVERVIEW: MonitorOverviewResponse = {
  examId: 10,
  examTitle: '期末统考',
  examStatus: 1,
  totalStudents: 30,
  onlineCount: 28,
  offlineCount: 2,
  submittedCount: 15,
  abnormalCount: 1,
  totalQuestions: 25,
  students: [],
};

describe('监考总览页签可见性门控链路（monitorTabVisibilityGate）', () => {
  it('初始可见：处于消费页签且浏览器页签可见时，轮询间隔正常为 10s', () => {
    const scope = effectScope();
    scope.run(() => {
      const activeTab = ref('monitor');
      const examId = ref(10);
      const fetchOverview = vi.fn().mockResolvedValue(MOCK_OVERVIEW);

      const { isVisible } = usePageVisibility({
        deps: { getVisibilityState: () => 'visible' },
      });

      const options = createMonitorQueryOptions(examId.value, fetchOverview, {
        isConsumerTabActive: () => isMonitorConsumerTab(activeTab.value),
        isPageVisible: () => isVisible.value,
      });

      expect(options.enabled).toBe(true);
      expect(options.refetchInterval).toBe(MONITOR_POLLING_INTERVAL_MS);
    });
    scope.stop();
  });

  it('页签隐藏：切换到 hidden 时暂停轮询（refetchInterval 变为 false），不产生定时请求', () => {
    const scope = effectScope();
    scope.run(() => {
      const activeTab = ref('monitor');
      const examId = ref(10);
      const fetchOverview = vi.fn().mockResolvedValue(MOCK_OVERVIEW);
      const listeners: Record<string, () => void> = {};
      let currentState: DocumentVisibilityState = 'visible';

      const { isVisible } = usePageVisibility({
        deps: {
          addListener: (type, handler) => {
            listeners[type] = handler;
          },
          getVisibilityState: () => currentState,
        },
      });

      const monitorOptions = computed(() =>
        createMonitorQueryOptions(examId.value, fetchOverview, {
          isConsumerTabActive: () => isMonitorConsumerTab(activeTab.value),
          isPageVisible: () => isVisible.value,
        })
      );

      expect(monitorOptions.value.refetchInterval).toBe(MONITOR_POLLING_INTERVAL_MS);

      // 切换到后台隐藏
      currentState = 'hidden';
      listeners.visibilitychange();

      // 断言轮询间隔变为 false（暂停轮询）
      expect(monitorOptions.value.refetchInterval).toBe(false);
    });
    scope.stop();
  });

  it('恢复可见：从 hidden 恢复到 visible 时立即触发一次拉取，且轮询间隔恢复为 10s', () => {
    const scope = effectScope();
    scope.run(() => {
      const activeTab = ref('monitor');
      const examId = ref(10);
      const fetchOverview = vi.fn().mockResolvedValue(MOCK_OVERVIEW);
      const refetchMonitor = vi.fn().mockResolvedValue(undefined);
      const listeners: Record<string, () => void> = {};
      let currentState: DocumentVisibilityState = 'hidden';

      const { isVisible } = usePageVisibility({
        onVisible: () => {
          if (
            isMonitorConsumerTab(activeTab.value) &&
            Number.isInteger(examId.value) &&
            examId.value > 0
          ) {
            void refetchMonitor();
          }
        },
        deps: {
          addListener: (type, handler) => {
            listeners[type] = handler;
          },
          getVisibilityState: () => currentState,
        },
      });

      const monitorOptions = computed(() =>
        createMonitorQueryOptions(examId.value, fetchOverview, {
          isConsumerTabActive: () => isMonitorConsumerTab(activeTab.value),
          isPageVisible: () => isVisible.value,
        })
      );

      // 初始 hidden 时暂停
      expect(monitorOptions.value.refetchInterval).toBe(false);
      expect(refetchMonitor).not.toHaveBeenCalled();

      // 恢复可见
      currentState = 'visible';
      listeners.visibilitychange();

      // 断言立即拉取了一次
      expect(refetchMonitor).toHaveBeenCalledTimes(1);
      // 断言轮询间隔恢复为 10s
      expect(monitorOptions.value.refetchInterval).toBe(MONITOR_POLLING_INTERVAL_MS);
    });
    scope.stop();
  });

  it('非消费页签隔离：停留在 behavior 页签时，切屏恢复不误触发监考总览立即拉取', () => {
    const scope = effectScope();
    scope.run(() => {
      const activeTab = ref('behavior');
      const examId = ref(10);
      const fetchOverview = vi.fn().mockResolvedValue(MOCK_OVERVIEW);
      const refetchMonitor = vi.fn().mockResolvedValue(undefined);
      const listeners: Record<string, () => void> = {};
      let currentState: DocumentVisibilityState = 'hidden';

      usePageVisibility({
        onVisible: () => {
          if (
            isMonitorConsumerTab(activeTab.value) &&
            Number.isInteger(examId.value) &&
            examId.value > 0
          ) {
            void refetchMonitor();
          }
        },
        deps: {
          addListener: (type, handler) => {
            listeners[type] = handler;
          },
          getVisibilityState: () => currentState,
        },
      });

      const monitorOptions = computed(() =>
        createMonitorQueryOptions(examId.value, fetchOverview, {
          isConsumerTabActive: () => isMonitorConsumerTab(activeTab.value),
          isPageVisible: () => currentState === 'visible',
        })
      );

      // 非消费页签原本 enabled 就是 false
      expect(monitorOptions.value.enabled).toBe(false);

      // 恢复可见
      currentState = 'visible';
      listeners.visibilitychange();

      // 非消费页签不得调用 refetchMonitor
      expect(refetchMonitor).not.toHaveBeenCalled();
    });
    scope.stop();
  });
});
