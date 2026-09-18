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
 * @param severity 后端返回的严重度值
 * @returns 对应的显示配置（label + 颜色）
 */
export function getSeverityConfig(severity: SeverityLevel): SeverityConfig {
  const configMap: Record<number, SeverityConfig> = {
    [SEVERITY_LEVEL.LOW]: { label: '低', color: 'default' },
    [SEVERITY_LEVEL.MEDIUM]: { label: '中', color: 'warning' },
    [SEVERITY_LEVEL.HIGH]: { label: '高', color: 'error' },
  };

  // 兜底：未知状态回退为 LOW
  return configMap[severity] || { label: '低', color: 'default' };
}
