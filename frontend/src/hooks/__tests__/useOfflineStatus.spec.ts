/**
 * 离线保护状态与双检测单测（阶段 2 · 先红后绿）。
 *
 * 核心逻辑：
 * 1. navigator.onLine === false 触发离线保护；
 * 2. autoSaveStatus === 'unsynced' 触发离线保护（双检测）；
 * 3. 网络恢复（online 事件）自动触发 onRecover 回调（flush）；
 * 4. 两者均恢复时，离线状态为 false。
 */
import { ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';

import { useOfflineStatus } from '@/hooks/useOfflineStatus';
import type { DraftSyncStatus } from '@/hooks/useAutoSaveDraft';

describe('useOfflineStatus 双检测与恢复联动', () => {
  function createTestEnvironment(initialOnline = true) {
    const listeners: Record<string, () => void> = {};
    let onlineState = initialOnline;

    const addListener = (type: string, handler: () => void) => {
      listeners[type] = handler;
    };
    const removeListener = (type: string) => {
      delete listeners[type];
    };
    const getOnLine = () => onlineState;

    const triggerEvent = (type: string) => {
      if (type === 'online') onlineState = true;
      if (type === 'offline') onlineState = false;
      listeners[type]?.();
    };

    return { addListener, removeListener, getOnLine, triggerEvent };
  }

  it('初始在线且草稿同步正常时，isOffline 为 false', () => {
    const env = createTestEnvironment(true);
    const status = ref<DraftSyncStatus>('synced');
    const { isOffline } = useOfflineStatus({
      autoSaveStatus: () => status.value,
      deps: env,
    });

    expect(isOffline.value).toBe(false);
  });

  it('条件 1：navigator.onLine 离线（offline 事件）触发 isOffline 为 true', () => {
    const env = createTestEnvironment(true);
    const status = ref<DraftSyncStatus>('synced');
    const { isOffline } = useOfflineStatus({
      autoSaveStatus: () => status.value,
      deps: env,
    });

    expect(isOffline.value).toBe(false);
    env.triggerEvent('offline');
    expect(isOffline.value).toBe(true);
  });

  it('条件 2：网络正常但草稿同步失败（unsynced）双检测触发 isOffline 为 true', () => {
    const env = createTestEnvironment(true);
    const status = ref<DraftSyncStatus>('synced');
    const { isOffline } = useOfflineStatus({
      autoSaveStatus: () => status.value,
      deps: env,
    });

    status.value = 'unsynced';
    expect(isOffline.value).toBe(true);
  });

  it('恢复联动：触发 online 事件时调用 onRecover 回调，双条件恢复后 isOffline 消失', async () => {
    const env = createTestEnvironment(false);
    const status = ref<DraftSyncStatus>('unsynced');
    const onRecover = vi.fn().mockImplementation(async () => {
      status.value = 'synced';
    });

    const { isOffline } = useOfflineStatus({
      autoSaveStatus: () => status.value,
      onRecover,
      deps: env,
    });

    expect(isOffline.value).toBe(true);

    // 触发网络恢复
    env.triggerEvent('online');
    expect(onRecover).toHaveBeenCalledTimes(1);

    await Promise.resolve();
    expect(isOffline.value).toBe(false);
  });
});
