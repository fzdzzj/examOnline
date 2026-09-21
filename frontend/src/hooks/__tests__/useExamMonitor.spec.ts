/**
 * 监考轮询选项与进度口径的用例（阶段 21 缺口 3）。
 *
 * 核心判据：**界面能显示的每个数都必须是后端给的**——所以这里不断言"前端算对了"，
 * 而是断言 ① 刷新间隔来自 `constants/monitor.ts`（不是页面里另写的字面量），
 * ② `queryFn` 把注入端点的返回原样交出去（不换形、不加字段），
 * ③ 措辞不声称「实时」。
 */
import { describe, expect, it, vi } from 'vitest';

import type { MonitorOverviewResponse } from '@/api/axios';
import { MONITOR_POLLING_HINT, MONITOR_POLLING_INTERVAL_MS } from '@/constants/monitor';
import { createMonitorQueryOptions, submittedRatioOf } from '@/hooks/useExamMonitor';

const OVERVIEW: MonitorOverviewResponse = {
  examId: 9,
  examTitle: '期中考试',
  examStatus: 1,
  totalStudents: 10,
  onlineCount: 3,
  offlineCount: 4,
  submittedCount: 3,
  abnormalCount: 2,
  totalQuestions: 20,
  students: [{ studentId: 12, status: 'SUBMITTED', progressPercent: 100 }],
};

describe('监考轮询选项', () => {
  it('刷新间隔取 constants/monitor.ts，与后端注释建议的 10–30s 同源', () => {
    const options = createMonitorQueryOptions(9, vi.fn());
    expect(options.refetchInterval).toBe(MONITOR_POLLING_INTERVAL_MS);
    expect(MONITOR_POLLING_INTERVAL_MS).toBeGreaterThanOrEqual(10000);
    expect(MONITOR_POLLING_INTERVAL_MS).toBeLessThanOrEqual(30000);
  });

  it('queryFn 把后端返回原样透出：不重包装、不补字段', async () => {
    const fetchOverview = vi.fn().mockResolvedValue(OVERVIEW);
    const options = createMonitorQueryOptions(9, fetchOverview);

    const result = await options.queryFn();

    expect(fetchOverview).toHaveBeenCalledWith(9);
    expect(result).toBe(OVERVIEW);
  });

  it('queryKey 按考试维度隔离，切换考试不会复用上一场的监考数据', () => {
    const a = createMonitorQueryOptions(1, vi.fn());
    const b = createMonitorQueryOptions(2, vi.fn());
    expect(a.queryKey).toEqual(['exam', 1, 'monitor']);
    expect(b.queryKey).not.toEqual(a.queryKey);
  });

  it('路由参数缺失（NaN / 非正数）时 enabled=false，不发无意义请求', () => {
    expect(createMonitorQueryOptions(Number.NaN, vi.fn()).enabled).toBe(false);
    expect(createMonitorQueryOptions(0, vi.fn()).enabled).toBe(false);
    expect(createMonitorQueryOptions(-1, vi.fn()).enabled).toBe(false);
    expect(createMonitorQueryOptions(9, vi.fn()).enabled).toBe(true);
  });
});

describe('提交进度口径', () => {
  it('分子分母都用后端计数：已交卷 3 / 已进入 10 → 0.3', () => {
    expect(submittedRatioOf(OVERVIEW)).toBeCloseTo(0.3);
  });

  it('还没有答卷行时返回 null（界面显示"暂无进入记录"，而不是算成 0%）', () => {
    expect(submittedRatioOf({ ...OVERVIEW, totalStudents: 0 })).toBeNull();
    expect(submittedRatioOf(undefined)).toBeNull();
    expect(submittedRatioOf(null)).toBeNull();
  });
});

describe('数据新鲜度措辞', () => {
  it('只能声称「准实时轮询」，去掉"准"之后不得剩下"实时"二字', () => {
    expect(MONITOR_POLLING_HINT).toContain('准实时轮询');
    expect(MONITOR_POLLING_HINT.replace(/准实时/g, '')).not.toMatch(/实时/);
  });

  it('措辞里写明的间隔与实际轮询间隔一致（两处必须同源，不许各写一份）', () => {
    const seconds = MONITOR_POLLING_INTERVAL_MS / 1000;
    expect(MONITOR_POLLING_HINT).toContain(`${seconds}s`);
  });
});
