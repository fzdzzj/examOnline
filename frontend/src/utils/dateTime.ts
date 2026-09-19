import dayjs from 'dayjs';

/**
 * 把用户输入的时间归一化成后端 LocalDateTime 能接受的 ISO-8601（带 T）。
 *
 * 后端字段是 `LocalDateTime`，只认 `2026-09-20T09:00:00`；带空格的
 * `2026-09-20 09:00:00` 会被 Jackson 判为 `400 请求体格式错误`（实测）。
 * 不可解析时返回 null，由调用方决定怎么提示——绝不把脏值发给后端。
 */
export function toIsoLocalDateTime(value: string | undefined | null): string | null {
  const trimmed = (value ?? '').trim();
  if (!trimmed) {
    return null;
  }
  const parsed = dayjs(trimmed);
  return parsed.isValid() ? parsed.format('YYYY-MM-DDTHH:mm:ss') : null;
}
