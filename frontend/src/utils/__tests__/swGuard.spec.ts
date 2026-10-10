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

const HERE = path.dirname(fileURLToPath(import.meta.url));

// 读取 sw.js 源文本（与本仓库其它词法护栏同一约定：以本文件目录为基准向上定位 public/sw.js）
const SW_PATH = path.resolve(HERE, '../../../public/sw.js');
const swSource = readFileSync(SW_PATH, 'utf-8');

// 读取 swCore.ts 源文本（双清单一致性用例需要比对两份实现的 STATIC_EXTENSIONS）
const SW_CORE_PATH = path.resolve(HERE, '../swCore.ts');
const swCoreSource = readFileSync(SW_CORE_PATH, 'utf-8');

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
const swCoreCode = stripComments(swCoreSource);

/**
 * 从剔除注释后的源码中提取 STATIC_EXTENSIONS = [...] 数组字面量，返回元素数组。
 *
 * 定位方式：正则命中 `STATIC_EXTENSIONS` 后的 `=` 与 `[`，再从该 `[` 起做括号配对，
 * 取到与之匹配的 `]` 闭口（支持数组内嵌套，避免贪婪截断），最终用字符串字面量正则
 * 解析出每一项（'...' / "..."）。解析失败（两份实现有一方缺清单或语法异常）抛错使用例红。
 */
function extractStaticExtensions(code: string): string[] {
  const assignMatch = /STATIC_EXTENSIONS\s*=\s*\[/g.exec(code);
  if (!assignMatch) {
    throw new Error('源码中找不到 STATIC_EXTENSIONS = [ ... ] 定义');
  }

  const open = assignMatch.index + assignMatch[0].indexOf('[');
  let depth = 0;
  let end = -1;
  for (let i = open; i < code.length; i++) {
    if (code[i] === '[') {
      depth++;
    } else if (code[i] === ']') {
      depth--;
      if (depth === 0) {
        end = i;
        break;
      }
    }
  }
  if (end === -1) {
    throw new Error('STATIC_EXTENSIONS 数组括号未闭合');
  }

  const body = code.slice(open + 1, end);
  const entries = body.match(/'[^']*'|"[^"]*"/g);
  return entries ? entries.map((e) => e.slice(1, -1)) : [];
}

const staticExtensionsFromSwJs = extractStaticExtensions(swCode);
const staticExtensionsFromSwCore = extractStaticExtensions(swCoreCode);

describe('sw.js 词法护栏 · 缓存红线与双清单一致性', () => {
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

  it('⑤ 双清单一致性：sw.js 与 swCore.ts 的 STATIC_EXTENSIONS 逐项相等（顺序与内容）', () => {
    // 两份实现都成功解析出非空清单
    expect(staticExtensionsFromSwJs.length).toBeGreaterThan(0);
    expect(staticExtensionsFromSwCore.length).toBeGreaterThan(0);
    // 顺序与内容必须完全一致（漂移即红）
    expect(staticExtensionsFromSwJs).toEqual(staticExtensionsFromSwCore);
  });

  it('⑥ swRegister.ts 注册域扩大为学生考试域且包含离开域卸载逻辑', () => {
    const swRegisterPath = path.resolve(HERE, '../swRegister.ts');
    const swRegisterCode = readFileSync(swRegisterPath, 'utf-8');
    expect(swRegisterCode).toContain('isStudentExamRoute');
    expect(swRegisterCode).toMatch(/\/student\/exams/);
  });
});
