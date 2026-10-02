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
import {
  MONITOR_CONSUMER_TABS,
  MONITOR_POLLING_HINT,
  MONITOR_POLLING_INTERVAL_MS,
  isMonitorConsumerTab,
} from '@/constants/monitor';
import { createMonitorQueryOptions, submittedRatioOf } from '@/hooks/useExamMonitor';
// 词法护栏直接读页面源码：门控接线若被悄悄移除，行为单测仍会全绿，只有对源码的断言能拦住
// （先例：后端 PublisherConfirmScopeGuardTest 的词法护栏）。
import examDetailPageSource from '@/pages/(dashboard)/teacher/exams/[id].page.vue?raw';

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

describe('页签门控', () => {
  it('只有「监考」与「考生名单与进度」页签消费监考总览：monitor/roster 真，其余三页假', () => {
    expect(MONITOR_CONSUMER_TABS).toEqual(['monitor', 'roster']);
    expect(isMonitorConsumerTab('monitor')).toBe(true);
    expect(isMonitorConsumerTab('roster')).toBe(true);
    expect(isMonitorConsumerTab('overview')).toBe(false);
    expect(isMonitorConsumerTab('snapshot')).toBe(false);
    expect(isMonitorConsumerTab('behavior')).toBe(false);
  });

  it('注入页签谓词后 enabled = examId 合法 AND 页签消费中（合取语义）', () => {
    const consume = { isConsumerTabActive: () => true };
    const idle = { isConsumerTabActive: () => false };
    // examId 非法：即便停在消费页签也不发请求
    expect(createMonitorQueryOptions(Number.NaN, vi.fn(), consume).enabled).toBe(false);
    expect(createMonitorQueryOptions(0, vi.fn(), consume).enabled).toBe(false);
    // examId 合法 + 非消费页签：暂停轮询
    expect(createMonitorQueryOptions(9, vi.fn(), idle).enabled).toBe(false);
    // examId 合法 + 消费页签：轮询
    expect(createMonitorQueryOptions(9, vi.fn(), consume).enabled).toBe(true);
  });

  it('第三参缺省时保持旧行为：enabled 只由 examId 合法性决定（向后兼容）', () => {
    expect(createMonitorQueryOptions(9, vi.fn()).enabled).toBe(true);
    expect(createMonitorQueryOptions(Number.NaN, vi.fn()).enabled).toBe(false);
  });
});

/** 取出 `callee(` 起配平的整段调用（本例实参不含带括号的字符串字面量，无需处理引号）。 */
function extractBalancedCall(source: string, callee: string): string | null {
  const start = source.indexOf(`${callee}(`);
  if (start === -1) return null;
  let depth = 0;
  for (let i = start + callee.length; i < source.length; i += 1) {
    if (source[i] === '(') depth += 1;
    else if (source[i] === ')') {
      depth -= 1;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  return null;
}

describe('页面接线词法护栏', () => {
  it('详情页构造监考轮询选项时注入了 isMonitorConsumerTab 页签谓词', () => {
    const call = extractBalancedCall(examDetailPageSource, 'createMonitorQueryOptions');
    expect(call, '页面必须调用 createMonitorQueryOptions 构造轮询选项').not.toBeNull();
    expect(call ?? '').toContain('isMonitorConsumerTab');
  });

  it('页签谓词取自 @/constants/monitor 的权威导出，而非页面内自建比较', () => {
    expect(examDetailPageSource).toMatch(
      /import\s*\{[^}]*\bisMonitorConsumerTab\b[^}]*\}\s*from\s*['"]@\/constants\/monitor['"]/
    );
  });
});
