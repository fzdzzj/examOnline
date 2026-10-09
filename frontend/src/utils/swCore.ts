/**
 * Service Worker 离线外壳纯函数决策模块（阶段 3）。
 *
 * 设计红线：
 * 1. 缓存决策 shouldCache：同源静态资源可缓存；/api/** 严格放行禁缓存；非 GET 及跨域禁缓存；
 * 2. 导航请求 isNavigationRequest：mode === 'navigate' 或 accept 含 text/html；
 * 3. 版本清理 staleCachesToDelete：清理过期旧缓存，保留当前活跃版本；
 * 4. 纯函数设计，不依赖真实浏览器全局变量（如 window/document/self），便于 Vitest 单测。
 */

export const CACHE_PREFIX = 'offline-exam-shell-';
export const CACHE_VERSION = 'v1';
export const CACHE_NAME = `${CACHE_PREFIX}${CACHE_VERSION}`;

export const STATIC_EXTENSIONS = [
  '.js',
  '.css',
  '.png',
  '.jpg',
  '.jpeg',
  '.gif',
  '.svg',
  '.ico',
  '.woff',
  '.woff2',
  '.ttf',
  '.eot',
  '.json',
];

export interface NavigationRequestProbe {
  mode?: string;
  headers?: Headers | Record<string, string>;
}

/**
 * 判断请求是否应被 runtime 缓存。
 *
 * 规则：
 * - 必须为 GET 请求；
 * - 必须为同源请求（若提供 selfOrigin）；
 * - API 请求（/api/**）绝对禁止缓存；
 * - 属于静态资源路径或静态扩展名。
 */
export function shouldCache(urlInput: string | URL, method = 'GET', selfOrigin?: string): boolean {
  if (method.toUpperCase() !== 'GET') return false;

  const url =
    typeof urlInput === 'string' ? new URL(urlInput, selfOrigin || 'http://localhost') : urlInput;

  // 跨域检查
  if (selfOrigin && url.origin !== selfOrigin) return false;

  // API 请求一律 network-only 禁缓存
  if (url.pathname.startsWith('/api/') || url.pathname.includes('/api/')) return false;

  // 检查静态资源
  if (url.pathname.startsWith('/assets/')) return true;

  const pathname = url.pathname.toLowerCase();
  return STATIC_EXTENSIONS.some((ext) => pathname.endsWith(ext));
}

/**
 * 判断是否为导航请求（HTML 页面请求）。
 */
export function isNavigationRequest(probe: NavigationRequestProbe): boolean {
  if (probe.mode === 'navigate') return true;

  if (probe.headers) {
    let accept: string | null | undefined;
    if (typeof (probe.headers as Headers).get === 'function') {
      accept = (probe.headers as Headers).get('accept');
    } else {
      const headerObj = probe.headers as Record<string, string>;
      accept = headerObj['accept'] || headerObj['Accept'];
    }
    if (typeof accept === 'string' && accept.includes('text/html')) {
      return true;
    }
  }

  return false;
}

/**
 * 返回待清理的旧缓存名称列表。
 *
 * 保证：
 * - 仅匹配属于该系统的缓存前缀（CACHE_PREFIX）；
 * - 排除当前活跃版本 activeVersion；
 * - 保留其它系统的缓存或未知缓存。
 */
export function staleCachesToDelete(activeVersion: string, existingCaches: string[]): string[] {
  return existingCaches.filter(
    (cacheName) => cacheName.startsWith(CACHE_PREFIX) && cacheName !== activeVersion
  );
}
