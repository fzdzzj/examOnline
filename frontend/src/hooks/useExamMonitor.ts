/**
 * 监考视图的轮询查询选项（阶段 21 缺口 3）。
 *
 * 为什么单独成文件：监考面板的「刷新间隔」与「enabled 条件」必须可被单测直接断言，
 * 而把它们写在页面里就只能靠 mount + 真 QueryClient 才看得见（见 tasks.json 任务 #4）。
 *
 * ⚠️ 三条不变式：
 * 1. **间隔取自 `constants/monitor.ts`**（后端注释建议 10–30s），不在页面里另写字面量；
 * 2. **数据新鲜度只声称「准实时轮询」**——本仓库 v3 范围没有 WebSocket 推送，
 *    写「实时」就是夸大（spec-delta「不夸大数据新鲜度」场景）；
 * 3. **人数与进度全部来自 `GET /api/exams/{examId}/monitor/overview`**，
 *    前端不合计、不推算（`MonitorService` 的在线判定走 Redis 心跳，前端无从复算）。
 */
import { MONITOR_POLLING_INTERVAL_MS } from '@/constants/monitor';
import type { MonitorOverviewResponse } from '@/api/axios';

export interface MonitorQueryOptions {
  queryKey: readonly ['exam', number, 'monitor'];
  queryFn: () => Promise<MonitorOverviewResponse | undefined>;
  /** 轮询间隔（毫秒）；页面据此反复请求，不做本地计时器补数 */
  refetchInterval: number;
  enabled: boolean;
  /** 轮询属后台刷新：失败时不要弹「重试中」，与首屏加载区分开 */
  refetchOnWindowFocus: false;
}

/**
 * @param examId 考试 ID；NaN / 非正数时 `enabled=false`（详情路由参数缺失时不发无意义请求）
 * @param fetchOverview 注入的取数函数（生产为 gen:api `overview` + `unwrap`，单测为 mock）
 */
export function createMonitorQueryOptions(
  examId: number,
  fetchOverview: (examId: number) => Promise<MonitorOverviewResponse | undefined>
): MonitorQueryOptions {
  return {
    queryKey: ['exam', examId, 'monitor'] as const,
    queryFn: () => fetchOverview(examId),
    refetchInterval: MONITOR_POLLING_INTERVAL_MS,
    enabled: Number.isInteger(examId) && examId > 0,
    refetchOnWindowFocus: false,
  };
}

/**
 * 提交进度比例：只做「已交卷 / 已进入」的除法显示，**分子分母都是后端给的计数**。
 * 分母为 0（还没有学生进入考试）时返回 null，由界面显示「暂无进入记录」而不是 0%。
 */
export function submittedRatioOf(
  overview: MonitorOverviewResponse | null | undefined
): number | null {
  const total = overview?.totalStudents ?? 0;
  if (!total) return null;
  return (overview?.submittedCount ?? 0) / total;
}
