/**
 * Service Worker 注册与卸载模块（创新点 5 二期 · 阶段 4）。
 *
 * 守的设计约定：
 * 1. 学生考试域精确注册：在学生考试域（考试列表与作答页）挂载时注册，不扩散至全局；
 * 2. 离开域注销：在路由导航离开学生考试域（目标不在 /student/exams 下）时 unregister，不干扰登录或教师端等页面；
 * 3. 域内保留：列表到作答页相互跳转不触发 SW 注销，SW 持续生效拦截断网重入；
 * 4. 容错降级：在不支持 ServiceWorker 或非安全上下文环境优雅降级返回 null / false。
 */

/**
 * 判断目标路由是否属于学生考试域。
 *
 * 范围：
 * - 考试列表：/student/exams
 * - 作答页：/student/exams/:id 及所有子路径
 */
export function isStudentExamRoute(path: string): boolean {
  return path === '/student/exams' || path.startsWith('/student/exams/');
}

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
