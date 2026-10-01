import { describe, expect, it } from 'vitest';

import { toIsoLocalDateTime } from '../dateTime';

describe('toIsoLocalDateTime：后端 LocalDateTime 只接受带 T 的 ISO-8601', () => {
  it('把界面上空格分隔的时间转成 ISO（补考创建曾因空格格式被后端判 400）', () => {
    expect(toIsoLocalDateTime('2026-09-20 09:00:00')).toBe('2026-09-20T09:00:00');
  });

  it('已经是 ISO 的输入原样通过', () => {
    expect(toIsoLocalDateTime('2026-09-20T09:00:00')).toBe('2026-09-20T09:00:00');
  });

  it('只给到分钟时补零秒', () => {
    expect(toIsoLocalDateTime(' 2026-09-20 09:00 ')).toBe('2026-09-20T09:00:00');
  });

  it('空值与不可解析的输入返回 null，绝不把脏值发给后端', () => {
    expect(toIsoLocalDateTime('')).toBeNull();
    expect(toIsoLocalDateTime(undefined)).toBeNull();
    expect(toIsoLocalDateTime('明天上午')).toBeNull();
  });
});
