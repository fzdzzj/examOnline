/**
 * Service Worker 注册与卸载模块（阶段 3）。
 *
 * 守的三条设计约定：
 * 1. 路由级精确注册：仅在学生考试作答页挂载时注册，不扩散至全局；
 * 2. 离开注销：在路由导航离开作答页时 unregister，不干扰登录或教师端等页面；
 * 3. 刷新保留：页面在作答页内刷新不触发路由离开守卫，SW 持续生效拦截断网重入；
 * 4. 容错降级：在不支持 ServiceWorker 或非安全上下文环境优雅降级返回 null / false。
 */

export async function registerExamServiceWorker(
  swPath = '/sw.js'
): Promise<ServiceWorkerRegistration | null> {
  if (
    typeof navigator === 'undefined' ||
    !('serviceWorker' in navigator) ||
    !navigator.serviceWorker
  ) {
    return null;
  }

  try {
    const registration = await navigator.serviceWorker.register(swPath, { scope: '/' });
    return registration;
  } catch (err) {
    console.warn('[swRegister] Service Worker registration failed:', err);
    return null;
  }
}

export async function unregisterExamServiceWorker(): Promise<boolean> {
  if (
    typeof navigator === 'undefined' ||
    !('serviceWorker' in navigator) ||
    !navigator.serviceWorker
  ) {
    return false;
  }

  try {
    const registrations = await navigator.serviceWorker.getRegistrations();
    let anyUnregistered = false;
    for (const registration of registrations) {
      const ok = await registration.unregister();
      if (ok) anyUnregistered = true;
    }
    return anyUnregistered;
  } catch (err) {
    console.warn('[swRegister] Service Worker unregistration failed:', err);
    return false;
  }
}
