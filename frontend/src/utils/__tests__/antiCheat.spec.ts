/**
 * 作答端防作弊策略纯函数单测（add-anti-cheat-restrictions 阶段 3）。
 *
 * 守的边界（与后端 `AntiCheatConfigs` / `IpMatcher` 同一口径，前端只做「预检与提示」）：
 * - **脏配置不误伤**：antiCheatConfig 是 JsonNode（未知形状），只认严格 `true`，
 *   缺失/非对象/字符串真值一律按「未开启」——绝不因为配置长得怪就拦学生的键鼠；
 * - **白名单文本规范化**：多行/逗号混用、空白、重复项，一律去空白去重保持声明顺序；
 * - **格式预检只做「明显不合法」判定**：白名单写错等于把整场考生挡在门外
 *   （后端不命中即 403），故在保存前提示；判定以条目为单位，权威匹配器仍是后端。
 */
import { describe, expect, it } from 'vitest';

import {
  ANTI_CHEAT_HINT,
  asClipboardAction,
  invalidIpWhitelistEntries,
  parseIpWhitelistText,
  readAntiCheatPolicy,
} from '@/utils/antiCheat';

describe('readAntiCheatPolicy：脏配置一律按未开启处理', () => {
  it.each([
    ['undefined', undefined],
    ['null', null],
    ['字符串', 'forbidCopy'],
    ['数组', [{ forbidCopy: true }]],
    ['数字', 1],
    ['布尔', true],
  ])('%s → 两个守卫都关闭', (_name, raw) => {
    expect(readAntiCheatPolicy(raw)).toEqual({ forbidCopy: false, fullScreen: false });
  });

  it('只认严格 true：真值字符串/数字一律不开启（与后端布尔读取口径一致）', () => {
    expect(readAntiCheatPolicy({ forbidCopy: 'true', fullScreen: 1 })).toEqual({
      forbidCopy: false,
      fullScreen: false,
    });
  });

  it('两个开关各自独立：只开一个时另一个仍关闭', () => {
    expect(readAntiCheatPolicy({ forbidCopy: true })).toEqual({
      forbidCopy: true,
      fullScreen: false,
    });
    expect(readAntiCheatPolicy({ fullScreen: true })).toEqual({
      forbidCopy: false,
      fullScreen: true,
    });
  });

  it('陌生键与 ipWhitelist 不影响作答端判定（前端只读自己认识的键）', () => {
    expect(readAntiCheatPolicy({ ipWhitelist: ['192.168.1.*'], forbidCopy: true })).toEqual({
      forbidCopy: true,
      fullScreen: false,
    });
  });
});

describe('asClipboardAction：只认四种剪贴板 DOM 事件', () => {
  it.each(['copy', 'cut', 'paste', 'contextmenu'])('%s → 原样返回', (type) => {
    expect(asClipboardAction(type)).toBe(type);
  });

  it('大小写不敏感（防御性归一，DOM 事件名规范为小写）', () => {
    expect(asClipboardAction('ContextMenu')).toBe('contextmenu');
  });

  it.each(['keydown', 'fullscreenchange', 'visibilitychange', ''])(
    '非剪贴板事件 %s → null（不误拦）',
    (type) => {
      expect(asClipboardAction(type)).toBeNull();
    }
  );
});

describe('parseIpWhitelistText：多行/逗号混用文本 → 规范化数组', () => {
  it('按行与逗号拆分、去首尾空白、丢弃空项、去重并保持声明顺序', () => {
    expect(
      parseIpWhitelistText(' 192.168.1.* \n\n10.0.0.0/8, 192.168.1.*\n  , 203.0.113.7 ')
    ).toEqual(['192.168.1.*', '10.0.0.0/8', '203.0.113.7']);
  });

  it('空文本/纯空白文本 → 空数组（等于不做 IP 限制，全网放行）', () => {
    expect(parseIpWhitelistText('')).toEqual([]);
    expect(parseIpWhitelistText('  \n , \t')).toEqual([]);
  });

  it('非字符串输入（脏回填）→ 空数组，不抛错', () => {
    expect(parseIpWhitelistText(undefined)).toEqual([]);
    expect(parseIpWhitelistText(42)).toEqual([]);
  });
});

describe('invalidIpWhitelistEntries：服务端支持形态的预检', () => {
  // 与后端 IpMatcherTest 同一批形态（单 IP / 整段通配符 / CIDR / 回环 / IPv4-mapped / 作用域后缀）
  const SUPPORTED = [
    '192.168.1.100',
    '0.0.0.0',
    '255.255.255.255',
    '192.168.1.*',
    '10.*.*.*',
    '*',
    '10.0.0.0/8',
    '192.168.1.0/24',
    '10.10.0.0/16',
    '192.168.1.0/25',
    '0.0.0.0/0',
    '127.0.0.1',
    '::1',
    '0:0:0:0:0:0:0:1',
    '2001:db8::/32',
    '::ffff:192.168.1.7',
    'fe80::1%eth0',
    ' 192.168.1.1 ',
  ];

  const MALFORMED = [
    'abc',
    '192.168.1',
    '192.168.1.256',
    '192.168.1.1*',
    '192.168.1.*.5',
    '10.0.0.0/33',
    '10.0.0.0/abc',
    '10.0.0.0/',
    '2001:db8::/129',
    '2001:db8:::1',
    '192.168.1.1:80',
  ];

  it.each(SUPPORTED)('支持形态 %s → 不列为问题条目（零误报是硬要求）', (entry) => {
    expect(invalidIpWhitelistEntries([entry])).toEqual([]);
  });

  it.each(MALFORMED)('明显不合法 %s → 列为问题条目（保存前提示，防整场被 403）', (entry) => {
    expect(invalidIpWhitelistEntries([entry])).toEqual([entry]);
  });

  it('混合清单：只挑出不合法条目，合法条目与顺序不受影响', () => {
    expect(invalidIpWhitelistEntries(['192.168.1.*', 'abc', '10.0.0.0/8', '10.0.0.0/33'])).toEqual([
      'abc',
      '10.0.0.0/33',
    ]);
  });

  it('空清单/空白条目 → 无问题（空清单等于不限 IP）', () => {
    expect(invalidIpWhitelistEntries([])).toEqual([]);
    expect(invalidIpWhitelistEntries(['', '   '])).toEqual([]);
  });
});

describe('ANTI_CHEAT_HINT：文案是常量且不含禁用表达', () => {
  it('三条提示都是非空常量（供作答端友好提示复用，避免各处自造文案）', () => {
    expect(ANTI_CHEAT_HINT.COPY_BLOCKED.length).toBeGreaterThan(0);
    expect(ANTI_CHEAT_HINT.FULLSCREEN_REQUIRED.length).toBeGreaterThan(0);
    expect(ANTI_CHEAT_HINT.FULLSCREEN_EXIT.length).toBeGreaterThan(0);
  });

  it('提示明确「只警告不交卷」语义（不得暗示自动交卷或成绩作废由前端判定）', () => {
    expect(ANTI_CHEAT_HINT.COPY_BLOCKED).toContain('记录');
    expect(ANTI_CHEAT_HINT.FULLSCREEN_EXIT).toContain('记录');
    for (const text of Object.values(ANTI_CHEAT_HINT)) {
      expect(text).not.toContain('自动交卷');
      expect(text).not.toContain('作废');
    }
  });
});
