// 监考轮询配置（准实时，非 WebSocket）
// 后端注释建议：10-30s 间隔

export const MONITOR_POLLING_INTERVAL_MS = 10000; // 10 秒
export const MONITOR_POLLING_HINT = '准实时轮询，每 10s 刷新';
