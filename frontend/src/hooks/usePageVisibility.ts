/**
 * 页面可见性监听 Hook（浏览器标签页激活状态门控）。
 *
 * 约定与设计：
 * 1. 状态感知：读取 `document.visibilityState !== 'hidden'`，暴露 `isVisible: Ref<boolean>` 与 `visibilityState: Ref<DocumentVisibilityState>`；
 * 2. 状态切换回调：
 *    - `onVisible`：从 hidden 恢复到 visible 时触发（用于立即拉取最新数据）；
 *    - `onHidden`：从 visible 切到 hidden 时触发；
 * 3. 依赖注入（单测友好）：
 *    - `deps.addListener` / `deps.removeListener`
 *    - `deps.getVisibilityState`
 *    缺省直接使用全局 document 事件监听；
 * 4. 生命周期管理：`onScopeDispose` 自动注销监听，防泄漏。
 */
import { getCurrentScope, onScopeDispose, ref } from 'vue';
import type { Ref } from 'vue';

export interface UsePageVisibilityOptions {
  onVisible?: () => void;
  onHidden?: () => void;
  deps?: {
    addListener?: (type: string, handler: () => void) => void;
    removeListener?: (type: string, handler: () => void) => void;
    getVisibilityState?: () => DocumentVisibilityState;
  };
}

export interface UsePageVisibilityView {
  isVisible: Ref<boolean>;
  visibilityState: Ref<DocumentVisibilityState>;
}

export function usePageVisibility(options: UsePageVisibilityOptions = {}): UsePageVisibilityView {
  const { onVisible, onHidden, deps } = options;

  const getInitialState = (): DocumentVisibilityState => {
    if (deps?.getVisibilityState) return deps.getVisibilityState();
    return typeof document !== 'undefined' ? document.visibilityState : 'visible';
  };

  const initial = getInitialState();
  const visibilityState = ref<DocumentVisibilityState>(initial);
  const isVisible = ref<boolean>(initial !== 'hidden');

  const handleVisibilityChange = (): void => {
    const nextState = deps?.getVisibilityState
      ? deps.getVisibilityState()
      : typeof document !== 'undefined'
        ? document.visibilityState
        : 'visible';

    const prevState = visibilityState.value;
    if (prevState === nextState) return;

    visibilityState.value = nextState;
    isVisible.value = nextState !== 'hidden';

    if (prevState === 'hidden' && nextState !== 'hidden') {
      onVisible?.();
    } else if (prevState !== 'hidden' && nextState === 'hidden') {
      onHidden?.();
    }
  };

  if (deps?.addListener) {
    deps.addListener('visibilitychange', handleVisibilityChange);
    if (getCurrentScope()) {
      onScopeDispose(() => {
        deps.removeListener?.('visibilitychange', handleVisibilityChange);
      });
    }
  } else if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', handleVisibilityChange);
    if (getCurrentScope()) {
      onScopeDispose(() => {
        document.removeEventListener('visibilitychange', handleVisibilityChange);
      });
    }
  }

  return { isVisible, visibilityState };
}
