/**
 * Service Worker 考试离线外壳（阶段 3 手写源文件）。
 *
 * 缓存策略：
 * 1. install 阶段：预缓存入口 HTML（'/' 与 '/index.html'），执行 self.skipWaiting()；
 * 2. activate 阶段：清理属于本项目前缀但不等于当前版本的旧缓存，执行 clients.claim()；
 * 3. fetch 拦截阶段：
 *    - 非 GET 请求：放行，不缓存；
 *    - /api/** 请求：严格 network-only 禁缓存，直接 fetch(request)；
 *    - 导航请求（mode === 'navigate' 或 accept 含 text/html）：网络优先，断网离线时回退到缓存的 '/index.html'；
 *    - 同源静态资源（JS/CSS/SVG/字体/assets）：runtime 缓存，若命中缓存且断网则从缓存提供。
 */

const CACHE_PREFIX = 'offline-exam-shell-';
const CACHE_VERSION = 'v1';
const CACHE_NAME = `${CACHE_PREFIX}${CACHE_VERSION}`;

const PRECACHE_URLS = ['/', '/index.html'];

/**
 * 静态资源扩展名列表。
 *
 * 注意：此列表必须与 src/utils/swCore.ts 的 STATIC_EXTENSIONS 保持一致。
 * 同步责任由词法护栏 swGuard.spec.ts 承担——护栏读取本文件源文本并断言红线特征。
 */
const STATIC_EXTENSIONS = [
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

/**
 * 判断 URL 是否为应被 runtime 缓存的同源静态资源。
 * 口径与 swCore.shouldCache 保持一致：/assets/ 路径或静态扩展名。
 */
function isStaticAsset(url) {
  if (url.pathname.startsWith('/assets/')) return true;
  const pathname = url.pathname.toLowerCase();
  return STATIC_EXTENSIONS.some((ext) => pathname.endsWith(ext));
}

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE_NAME)
      .then((cache) => cache.addAll(PRECACHE_URLS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((key) => key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME)
            .map((key) => caches.delete(key))
        )
      )
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);

  // 跨域请求直接放行
  if (url.origin !== self.location.origin) return;

  // API 请求一律 network-only 禁缓存
  if (url.pathname.startsWith('/api/') || url.pathname.includes('/api/')) {
    return;
  }

  // 导航请求（HTML 页面请求）：网络优先，断网回退缓存入口
  const isNav =
    request.mode === 'navigate' ||
    (request.headers.get('accept') && request.headers.get('accept').includes('text/html'));

  if (isNav) {
    event.respondWith(
      fetch(request).catch(() =>
        caches.match('/index.html').then((cached) => cached || caches.match('/'))
      )
    );
    return;
  }

  // 同源静态资源（/assets/ 路径或静态扩展名）：网络优先并写入缓存，离线时回退缓存
  // 口径：同源 GET + 非 API + (startsWith /assets/ OR 静态扩展名) ——与 swCore.shouldCache 一致
  if (!isStaticAsset(url)) {
    // 非静态资源（如 /favicon.svg 以外的动态 URL）：直接放行，不缓存
    return;
  }

  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response && response.status === 200) {
          const responseToCache = response.clone();
          caches.open(CACHE_NAME).then((cache) => {
            cache.put(request, responseToCache);
          });
        }
        return response;
      })
      .catch(() => caches.match(request))
  );
});
