// 行为事件严重度映射（与后端 SeverityLevel 枚举对齐）
// 存储口径：1=低、2=中、3=高

export const SEVERITY_LEVEL = {
  LOW: 1,
  MEDIUM: 2,
  HIGH: 3,
} as const;

export type SeverityLevel = (typeof SEVERITY_LEVEL)[keyof typeof SEVERITY_LEVEL];

export interface SeverityConfig {
  label: string;
  color: 'default' | 'processing' | 'error' | 'warning' | 'success';
}

/**
 * 严重度显示映射
 *
 * 入参刻意收宽成 `number | null | undefined`（而不是 `SeverityLevel`）：
 * 契约里 `BehaviorLogItem.severity` 与 `MonitorStudentItem.maxSeverity` 都是
 * 可选项（`severity?: number`），调用方拿到的就是 `number | undefined`，
 * 收死成 1|2|3 只会逼调用方到处写 cast。映射值与兜底口径不变。
 *
 * @param severity 后端返回的严重度值
 * @returns 对应的显示配置（label + 颜色）；未知值兜底为「低 / default」，
 *          与后端 `SeverityLevel.of()` 对非法值回退 LOW 的口径一致
 */
export function getSeverityConfig(severity: number | null | undefined): SeverityConfig {
  const configMap: Record<number, SeverityConfig> = {
    [SEVERITY_LEVEL.LOW]: { label: '低', color: 'default' },
    [SEVERITY_LEVEL.MEDIUM]: { label: '中', color: 'warning' },
    [SEVERITY_LEVEL.HIGH]: { label: '高', color: 'error' },
  };

  // 兜底：未知状态回退为 LOW
  return configMap[severity ?? -1] || { label: '低', color: 'default' };
}

/**
 * antd `Timeline` 的圆点色（只接受 red/blue/green/gray 或自定义色）。
 * 与上表的语义一致：高=red、中=orange、低=gray，未知=gray。
 */
export function getSeverityDotColor(severity: number | null | undefined): string {
  const { color } = getSeverityConfig(severity);
  if (color === 'error') return 'red';
  if (color === 'warning') return 'orange';
  return 'gray';
}
