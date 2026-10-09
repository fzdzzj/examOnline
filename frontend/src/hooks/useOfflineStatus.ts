/**
 * 离线保护状态双检测 Hook（阶段 2）。
 *
 * 守的三条设计约定：
 * 1. 双检测：`navigator.onLine === false` OR 草稿心跳保存失败（`unsynced`）双通道判定；
 * 2. 恢复联动：监听 `online` 事件，网络恢复时立即触发 `onRecover` 回调（flush）；
 * 3. 依赖注入：`deps.addListener` 与 `deps.getOnLine` 支持单测确定性注入，生产走原生 window。
 */
import { computed, getCurrentScope, onScopeDispose, ref, toValue } from 'vue';
import type { ComputedRef, MaybeRefOrGetter, Ref } from 'vue';

import type { DraftSyncStatus } from '@/hooks/useAutoSaveDraft';

export interface UseOfflineStatusOptions {
  autoSaveStatus: MaybeRefOrGetter<DraftSyncStatus>;
  onRecover?: () => Promise<void> | void;
  deps?: {
    addListener?: (type: string, handler: () => void) => void;
    removeListener?: (type: string, handler: () => void) => void;
    getOnLine?: () => boolean;
  };
}

export interface UseOfflineStatusView {
  isOnline: Ref<boolean>;
  isOffline: ComputedRef<boolean>;
}

export function useOfflineStatus(options: UseOfflineStatusOptions): UseOfflineStatusView {
  const { autoSaveStatus, onRecover, deps } = options;

  const getInitialOnline = (): boolean => {
    if (deps?.getOnLine) return deps.getOnLine();
    return typeof navigator !== 'undefined' ? navigator.onLine : true;
  };

  const isOnline = ref(getInitialOnline());

  const handleOnline = (): void => {
    isOnline.value = true;
    if (onRecover) {
      void onRecover();
    }
  };

  const handleOffline = (): void => {
    isOnline.value = false;
  };

  if (deps?.addListener) {
    deps.addListener('online', handleOnline);
    deps.addListener('offline', handleOffline);
    if (getCurrentScope()) {
      onScopeDispose(() => {
        deps.removeListener?.('online', handleOnline);
        deps.removeListener?.('offline', handleOffline);
      });
    }
  } else if (typeof window !== 'undefined') {
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    if (getCurrentScope()) {
      onScopeDispose(() => {
        window.removeEventListener('online', handleOnline);
        window.removeEventListener('offline', handleOffline);
      });
    }
  }

  // 双检测：浏览器离线 OR 草稿同步失败（unsynced）
  const isOffline = computed(() => {
    return !isOnline.value || toValue(autoSaveStatus) === 'unsynced';
  });

  return { isOnline, isOffline };
}
