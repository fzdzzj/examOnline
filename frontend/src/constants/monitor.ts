// 监考轮询配置（准实时，非 WebSocket）
// 后端注释建议：10-30s 间隔

export const MONITOR_POLLING_INTERVAL_MS = 10000; // 10 秒
export const MONITOR_POLLING_HINT = '准实时轮询，每 10s 刷新';

/**
 * 消费监考总览（`GET /api/exams/{examId}/monitor/overview`）的页签集合——其余页签轮询暂停。
 *
 * 为什么 `roster` 必须在集合内：考生名单页签的 `rosterRows` 会把班级学生**左连**
 * `overview.students` 的实考状态与答题进度（「班级之外、但已进入考试的学生」也来自
 * `overview`）。把 roster 移出集合，会让该页签的状态/进度列冻结在旧值。
 *
 * 为什么 `behavior` 不在集合内：行为日志的学生下拉候选（`logStudentOptions`）虽间接依赖
 * `rosterRows`，但进入过 monitor/roster 后 vue-query 缓存仍在，仅「直接落在 behavior 且
 * 从未访问过消费页签」时下拉缺班级外学生——边缘取舍，不为它把 behavior 加入集合。
 */
export const MONITOR_CONSUMER_TABS = ['monitor', 'roster'] as const;

/** 当前页签是否消费监考总览数据（决定轮询是否启用）。 */
export function isMonitorConsumerTab(tab: string): boolean {
  return (MONITOR_CONSUMER_TABS as readonly string[]).includes(tab);
}
