/**
 * Service Worker 核心纯函数单测（阶段 3 · 先红后绿）。
 *
 * 规范红线要求：
 * 1. 缓存决策 `shouldCache(url, method)`：同源静态资源可缓存；API 请求一律放行不缓存；跨域与非 GET 禁缓存；
 * 2. 导航请求识别 `isNavigationRequest(request)`：mode === 'navigate' 或 accept 含 text/html；
 * 3. 过期版本清理 `staleCachesToDelete(activeVersion, existingCaches)`：清理旧版本、精确保留当前版本；
 * 4. 不依赖真实 Chromium，全部为纯函数，由 Vitest 直接覆盖。
 */
import { describe, expect, it } from 'vitest';

import { CACHE_NAME, isNavigationRequest, shouldCache, staleCachesToDelete } from '@/utils/swCore';

describe('SW Core 纯函数逻辑', () => {
  describe('shouldCache 缓存决策', () => {
    const origin = 'https://exam.test.com';

    it('同源静态指纹资源（js/css/svg/woff）允许缓存', () => {
      expect(shouldCache('https://exam.test.com/assets/index-BvFSvEa6.js', 'GET', origin)).toBe(
        true
      );
      expect(shouldCache('https://exam.test.com/assets/style.css', 'GET', origin)).toBe(true);
      expect(shouldCache('https://exam.test.com/favicon.ico', 'GET', origin)).toBe(true);
      expect(shouldCache('https://exam.test.com/font.woff2', 'GET', origin)).toBe(true);
    });

    it('API 请求（/api/**）一律严格放行，禁止缓存', () => {
      expect(
        shouldCache('https://exam.test.com/api/exam-taking/exams/1/enter', 'GET', origin)
      ).toBe(false);
      expect(shouldCache('https://exam.test.com/api/auth/me', 'GET', origin)).toBe(false);
      expect(shouldCache('https://exam.test.com/api/scores/my', 'GET', origin)).toBe(false);
    });

    it('非 GET 请求一律放行，禁止缓存', () => {
      expect(shouldCache('https://exam.test.com/assets/index.js', 'POST', origin)).toBe(false);
      expect(shouldCache('https://exam.test.com/assets/index.js', 'PUT', origin)).toBe(false);
      expect(shouldCache('https://exam.test.com/assets/index.js', 'DELETE', origin)).toBe(false);
    });

    it('跨域请求一律禁止缓存', () => {
      expect(shouldCache('https://other-domain.com/assets/lib.js', 'GET', origin)).toBe(false);
    });
  });

  describe('isNavigationRequest 导航请求判定', () => {
    it('mode 为 navigate 时判定为导航请求', () => {
      expect(isNavigationRequest({ mode: 'navigate' })).toBe(true);
    });

    it('headers 中 accept 包含 text/html 时判定为导航请求', () => {
      expect(
        isNavigationRequest({
          mode: 'cors',
          headers: { accept: 'text/html,application/xhtml+xml' },
        })
      ).toBe(true);
    });

    it('普通 fetch/xhr 静态资源不是导航请求', () => {
      expect(
        isNavigationRequest({
          mode: 'cors',
          headers: { accept: 'application/javascript' },
        })
      ).toBe(false);
      expect(
        isNavigationRequest({
          mode: 'no-cors',
          headers: { accept: '*/*' },
        })
      ).toBe(false);
    });
  });

  describe('staleCachesToDelete 过期版本清理', () => {
    it('清理所有旧版本 shell 缓存，精确保留当前激活版本', () => {
      const active = 'offline-exam-shell-v2';
      const existing = [
        'offline-exam-shell-v1',
        'offline-exam-shell-v2',
        'other-system-cache',
        'offline-exam-shell-v0',
      ];

      const toDelete = staleCachesToDelete(active, existing);
      expect(toDelete).toContain('offline-exam-shell-v1');
      expect(toDelete).toContain('offline-exam-shell-v0');
      expect(toDelete).not.toContain('offline-exam-shell-v2');
      expect(toDelete).not.toContain('other-system-cache');
    });

    it('无过期缓存时返回空数组', () => {
      const active = CACHE_NAME;
      const existing = [CACHE_NAME];
      expect(staleCachesToDelete(active, existing)).toEqual([]);
    });
  });
});
