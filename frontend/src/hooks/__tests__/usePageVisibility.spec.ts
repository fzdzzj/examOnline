import { effectScope } from 'vue';
import { describe, expect, it, vi } from 'vitest';

import { usePageVisibility } from '@/hooks/usePageVisibility';

describe('usePageVisibility Hook', () => {
  it('初始状态感知：根据 getVisibilityState 返回值初始化 isVisible 与 visibilityState', () => {
    const scope = effectScope();
    scope.run(() => {
      const visibleView = usePageVisibility({
        deps: { getVisibilityState: () => 'visible' },
      });
      expect(visibleView.isVisible.value).toBe(true);
      expect(visibleView.visibilityState.value).toBe('visible');

      const hiddenView = usePageVisibility({
        deps: { getVisibilityState: () => 'hidden' },
      });
      expect(hiddenView.isVisible.value).toBe(false);
      expect(hiddenView.visibilityState.value).toBe('hidden');
    });
    scope.stop();
  });

  it('切换到 hidden：isVisible 变为 false，触发 onHidden 回调，不触发 onVisible', () => {
    const listeners: Record<string, () => void> = {};
    let currentState: DocumentVisibilityState = 'visible';
    const onVisible = vi.fn();
    const onHidden = vi.fn();

    const scope = effectScope();
    let view!: ReturnType<typeof usePageVisibility>;
    scope.run(() => {
      view = usePageVisibility({
        onVisible,
        onHidden,
        deps: {
          addListener: (type, handler) => {
            listeners[type] = handler;
          },
          getVisibilityState: () => currentState,
        },
      });
    });

    expect(view.isVisible.value).toBe(true);
    expect(listeners.visibilitychange).toBeDefined();

    // 触发切到 hidden
    currentState = 'hidden';
    listeners.visibilitychange();

    expect(view.isVisible.value).toBe(false);
    expect(view.visibilityState.value).toBe('hidden');
    expect(onHidden).toHaveBeenCalledTimes(1);
    expect(onVisible).not.toHaveBeenCalled();

    scope.stop();
  });

  it('恢复到 visible：isVisible 变为 true，触发 onVisible 回调，不触发 onHidden', () => {
    const listeners: Record<string, () => void> = {};
    let currentState: DocumentVisibilityState = 'hidden';
    const onVisible = vi.fn();
    const onHidden = vi.fn();

    const scope = effectScope();
    let view!: ReturnType<typeof usePageVisibility>;
    scope.run(() => {
      view = usePageVisibility({
        onVisible,
        onHidden,
        deps: {
          addListener: (type, handler) => {
            listeners[type] = handler;
          },
          getVisibilityState: () => currentState,
        },
      });
    });

    expect(view.isVisible.value).toBe(false);

    // 恢复可见
    currentState = 'visible';
    listeners.visibilitychange();

    expect(view.isVisible.value).toBe(true);
    expect(view.visibilityState.value).toBe('visible');
    expect(onVisible).toHaveBeenCalledTimes(1);
    expect(onHidden).not.toHaveBeenCalled();

    scope.stop();
  });

  it('相同状态连续触发时幂等：不重复触发回调', () => {
    const listeners: Record<string, () => void> = {};
    let currentState: DocumentVisibilityState = 'visible';
    const onVisible = vi.fn();
    const onHidden = vi.fn();

    const scope = effectScope();
    scope.run(() => {
      usePageVisibility({
        onVisible,
        onHidden,
        deps: {
          addListener: (type, handler) => {
            listeners[type] = handler;
          },
          getVisibilityState: () => currentState,
        },
      });
    });

    // 初始已是 visible，再次触发相同的 visible
    currentState = 'visible';
    listeners.visibilitychange();

    expect(onVisible).not.toHaveBeenCalled();
    expect(onHidden).not.toHaveBeenCalled();

    scope.stop();
  });

  it('生命周期注销：scope 停止时解绑 visibilitychange 监听器', () => {
    const removeListener = vi.fn();
    const scope = effectScope();
    scope.run(() => {
      usePageVisibility({
        deps: {
          addListener: vi.fn(),
          removeListener,
          getVisibilityState: () => 'visible',
        },
      });
    });

    expect(removeListener).not.toHaveBeenCalled();
    scope.stop();
    expect(removeListener).toHaveBeenCalledWith('visibilitychange', expect.any(Function));
  });
});
