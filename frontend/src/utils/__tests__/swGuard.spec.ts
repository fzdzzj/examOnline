/**
 * sw.js 词法护栏（lexical guard）——阶段 3 修复笔 · 修复 1b。
 *
 * 设计说明：
 * sw.js 为手写 Service Worker 源文件，运行于 ServiceWorkerGlobalScope，无法在 Vitest（jsdom/node）
 * 中直接执行。本护栏通过读取源文本进行字符串/正则断言，守护 sw.js 的四条缓存红线：
 *
 *   ① /api/ network-only 放行分支存在（非 GET/API 不缓存）；
 *   ② 旧缓存清理含 startsWith(CACHE_PREFIX) 且排除当前 CACHE_NAME；
 *   ③ 非 GET 请求放行（method !== 'GET'）；
 *   ④ 预缓存入口列表（PRECACHE_URLS）存在。
 *
 * 同步责任：sw.js 与 swCore.ts 存在两份实现（sw.js 为独立 SW 文件，swCore.ts 为可单测纯函数），
 * 两者的 STATIC_EXTENSIONS 必须保持一致，此职责由本护栏承担（见 proposal.md 设计取舍登记）。
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// 读取 sw.js 源文本（与本仓库其它词法护栏同一约定：以本文件目录为基准向上定位 public/sw.js）
const SW_PATH = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../public/sw.js');
const swSource = readFileSync(SW_PATH, 'utf-8');

/**
 * 剔除注释后的 sw.js 源码（保留换行与偏移），对齐 PublisherConfirmScopeGuardTest 的
 * stripCommentsAndLiterals 先例：使红线断言只命中真实代码，注释里提及分支名不构成护栏通过，
 * ——否则"注释掉 API 放行分支"这类破坏（护栏注入判据）会因为文字仍在注释里而漏红。
 */
function stripComments(code: string): string {
  const out = code.split('');
  let i = 0;
  const n = out.length;
  while (i < n) {
    const c = out[i];
    if (c === '/' && out[i + 1] === '/') {
      while (i < n && out[i] !== '\n') {
        out[i++] = ' ';
      }
    } else if (c === '/' && out[i + 1] === '*') {
      out[i++] = ' ';
      out[i++] = ' ';
      while (i < n && !(out[i] === '*' && out[i + 1] === '/')) {
        if (out[i] !== '\n') {
          out[i] = ' ';
        }
        i++;
      }
      if (i < n) {
        out[i++] = ' ';
        if (i < n) {
          out[i++] = ' ';
        }
      }
    } else {
      i++;
    }
  }
  return out.join('');
}

// 全部红线断言作用于剔除注释后的真实代码
const swCode = stripComments(swSource);

describe('sw.js 词法护栏 · 四条缓存红线', () => {
  it('① API 请求 network-only 放行分支存在（/api/ 路径判定）', () => {
    // sw.js 必须有 /api/ 判断分支，确保 API 请求不进入缓存逻辑
    expect(swCode).toMatch(/\/api\//);
    // 必须有明确放行（return）而非缓存
    expect(swCode).toMatch(/pathname\.startsWith\(['"]\/api\//);
  });

  it('② 旧缓存清理：含 startsWith(CACHE_PREFIX) 且排除当前 CACHE_NAME', () => {
    // 必须用 startsWith 检查缓存前缀归属
    expect(swCode).toMatch(/startsWith\(CACHE_PREFIX\)/);
    // 必须排除当前 CACHE_NAME（不等于当前缓存）
    expect(swCode).toMatch(/!==\s*CACHE_NAME/);
  });

  it('③ 非 GET 请求放行分支存在', () => {
    // sw.js 必须在 fetch handler 早期检查并放行非 GET
    expect(swCode).toMatch(/request\.method\s*!==\s*['"]GET['"]/);
  });

  it('④ 预缓存入口列表（PRECACHE_URLS）存在，包含 /index.html', () => {
    // 必须有预缓存 URL 数组定义
    expect(swCode).toMatch(/PRECACHE_URLS/);
    // 必须包含 /index.html（离线导航回退目标）
    expect(swCode).toContain('/index.html');
  });
});
