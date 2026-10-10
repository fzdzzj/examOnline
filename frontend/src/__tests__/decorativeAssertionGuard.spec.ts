/**
 * 词法护栏：前端测试源码不得出现两禁形态（fix-frontend-decorative-assertions）。
 *
 * 背景：装饰性断言会让「被测行为已失效」的测试恒绿。两禁形态：
 *   1. `if (...exists())` 守卫包裹断言——selector 失配时断言被静默跳过（离线重入
 *      用例⑦的旧形态，`manual-submit-button` 全仓仅测试自引用）；
 *   2. `exists() || 兜底` 双通——主条件失效时兜底字符串仍真（ExamMonitorPanel
 *      L101 旧形态，`.abnormal-row` 行类绑定失效仍靠 html().includes 兜住）。
 *
 * 护栏约定（对应 spec「测试断言有效性纪律」）：
 *   - 扫描 src 下全部测试目录（`__tests__`）里的 `*.spec.ts` 文件（与 vitest include 口径一致）；
 *   - 断言它们不含两禁形态；合法直断 `expect(find(x).exists()).toBe(true|false)`
 *     不受影响（它对真正的失配是自然击红）；
 *   - 护栏文件自身按文件名从扫描集排除，避免「护栏被自己拦下」的自引用陷阱；
 *   - 动态集合 for-of 循环不在此加护栏（常量字面量驱动的循环是合法形态，防误伤）。
 *
 * 有效性：任何一处回退注入守卫/双通形态都会让本护栏击红（变异演示见 tasks.json）。
 */
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// 护栏自身文件名：扫描目录 walk 时按相对路径精确排除，避免自引用陷阱。
const SELF_BASENAME = 'decorativeAssertionGuard.spec.ts';
// 从本文件位置上溯到 src/（__tests__ 的直接父目录）。
const SRC_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');

/** 递归收集 src 下全部 *.spec.ts 测试文件（与 vitest include 口径一致）。 */
function collectSpecFiles(): string[] {
  const out: string[] = [];
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (entry.isFile() && entry.name.endsWith('.spec.ts')) {
        out.push(full);
      }
    }
  };
  walk(SRC_ROOT);
  return out;
}

// 两禁形态字面量。护栏按文件名排除自身，因此不会命中自己的源码。
// 守卫/双通检测按「行 + 可跨内层括号」稳健匹配：
//   form A（守卫形态）：同一行出现 `if (` 且 `.exists()`——覆盖 `if (submitBtn.exists())`
//       与嵌套内层括号的 `if (wrapper.find('.x').exists())` 两种变体；
//   form B（双通形态）：`.exists() ||`。
// 合法直断 `expect(find(x).exists()).toBe(true|false)` 不含 `if (` 前缀，天然不受影响。
const BANNED_GUARD_LINE = (line: string): boolean =>
  /if\s*\(/.test(line) && /\.exists\(\s*\)/.test(line);
const BANNED_DOUBLE_PASS = /\.exists\(\s*\)\s*\|\|/;

/** 收集一个文件里命中禁止形态的行（形如 "相对路径:N 内容"）。 */
function bannedOffenders(lines: string[], rel: string): string[] {
  return lines
    .map((line, idx) => {
      const hit = BANNED_GUARD_LINE(line) || BANNED_DOUBLE_PASS.test(line);
      const shown = line.trim();
      return hit && shown.length > 0 ? `${rel}:${idx + 1} ${shown}` : null;
    })
    .filter((x): x is string => x !== null);
}

describe('词法护栏：测试源码不含两禁形态', () => {
  const files = collectSpecFiles();
  const scanned = files.filter((f) => path.basename(f) !== SELF_BASENAME);

  it('护栏自身从扫描集排除，自引用零命中', () => {
    const names = scanned.map((f) => path.basename(f));
    expect(names).not.toContain(SELF_BASENAME);
  });

  it('所有测试文件不含守卫（`if (...)` 且 `.exists()`）或双通（`.exists() ||`）形态', () => {
    const offenders: string[] = [];
    for (const file of scanned) {
      const rel = path.relative(SRC_ROOT, file);
      offenders.push(...bannedOffenders(readFileSync(file, 'utf-8').split('\n'), rel));
    }
    expect(offenders).toEqual([]);
  });
});
