/**
 * Service Worker 注册与注销单测（阶段 3 · 先红后绿）。
 *
 * 规范要求：
 * 1. 注册流程 mock navigator.serviceWorker 测，不依赖真实 Chromium；
 * 2. 精确注册：支持指定 swPath 或默认 '/sw.js'；
 * 3. 卸载流程：调用 unregisterExamServiceWorker 时注销 scope 相关的 registration；
 * 4. 环境容错：若 navigator.serviceWorker 不存在（如非安全上下文），优雅返回 null / false。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { registerExamServiceWorker, unregisterExamServiceWorker } from '@/utils/swRegister';

describe('SW 注册与注销 (mock navigator.serviceWorker)', () => {
  const originalSW = navigator.serviceWorker;

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    Object.defineProperty(navigator, 'serviceWorker', {
      value: originalSW,
      configurable: true,
      writable: true,
    });
  });

  it('支持 ServiceWorker 的环境下成功注册并返回 registration', async () => {
    const mockRegistration = {
      scope: '/',
      unregister: vi.fn().mockResolvedValue(true),
    };
    const registerMock = vi.fn().mockResolvedValue(mockRegistration);

    Object.defineProperty(navigator, 'serviceWorker', {
      value: {
        register: registerMock,
        getRegistrations: vi.fn().mockResolvedValue([mockRegistration]),
      },
      configurable: true,
      writable: true,
    });

    const reg = await registerExamServiceWorker('/sw.js');
    expect(registerMock).toHaveBeenCalledWith('/sw.js', { scope: '/' });
    expect(reg).toBe(mockRegistration);
  });

  it('调用 unregisterExamServiceWorker 时成功注销已存在的 registration', async () => {
    const unregisterMock = vi.fn().mockResolvedValue(true);
    const mockRegistration = {
      scope: '/',
      unregister: unregisterMock,
    };

    Object.defineProperty(navigator, 'serviceWorker', {
      value: {
        register: vi.fn(),
        getRegistrations: vi.fn().mockResolvedValue([mockRegistration]),
      },
      configurable: true,
      writable: true,
    });

    const result = await unregisterExamServiceWorker();
    expect(unregisterMock).toHaveBeenCalledTimes(1);
    expect(result).toBe(true);
  });

  it('不支持 ServiceWorker（未定义）时优雅降级，不抛异常', async () => {
    Object.defineProperty(navigator, 'serviceWorker', {
      value: undefined,
      configurable: true,
      writable: true,
    });

    const reg = await registerExamServiceWorker();
    expect(reg).toBeNull();

    const unreg = await unregisterExamServiceWorker();
    expect(unreg).toBe(false);
  });
});
