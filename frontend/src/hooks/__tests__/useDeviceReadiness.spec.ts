/**
 * 考前设备就绪自检 Hook 单测（add-pre-exam-device-readiness-check 阶段 2 · 先红后绿）。
 *
 * 覆盖四类检查项的判定口径与异常兜底：
 * 1. RTT：同源 HEAD 采样丢弃首样本取中位数；全样本失败按警示兜底；
 * 2. 视口：低于建议阈值 → 警示（不阻断）；
 * 3. 全屏：document.fullscreenEnabled 同语义特性检测；
 * 4. 兼容：userAgent 内核白名单（Chrome/Edge/Firefox/Safari）+ localStorage 特性检测，
 *    任一不满足 → critical（强警示）；
 * 5. 自检逻辑自身异常 → 兜底「已完成 + 全部警示」，绝不 reject、绝不阻断。
 *
 * 全部通过 deps 注入确定性环境（jsdom 缺 API 不构成干扰）。
 */
import { describe, expect, it, vi } from 'vitest';

import {
  READINESS_ITEM_LABELS,
  detectKernel,
  loadReadinessReport,
  medianOf,
  saveReadinessReport,
  useDeviceReadiness,
  type DeviceReadinessReport,
  type ReadinessItem,
  type ReadinessItemKey,
} from '@/hooks/useDeviceReadiness';

function reportOf(runResult: DeviceReadinessReport): Record<ReadinessItemKey, ReadinessItem> {
  return Object.fromEntries(runResult.items.map((item) => [item.key, item])) as Record<
    ReadinessItemKey,
    ReadinessItem
  >;
}

/** 按顺序吐出预设样本的 RTT 采样器（null 模拟网络失败样本）。 */
function queuedSamples(values: Array<number | null>) {
  let index = 0;
  return () => Promise.resolve(values[index++]);
}

describe('RTT 采样：丢弃首样本取中位数', () => {
  it('丢弃首个样本（DNS/握手冷启动），其余样本取中位数', async () => {
    const { run } = useDeviceReadiness({
      deps: { measureRttSample: queuedSamples([999, 100, 200]) },
      thresholds: { rttSampleCount: 3, rttWarningMs: 600 },
    });
    const report = await run();
    const rtt = reportOf(report).rtt;
    // 样本 [999, 100, 200] → 丢首 → [100, 200] → 中位数 150
    expect(rtt.level).toBe('pass');
    expect(rtt.detail).toContain('150');
  });

  it('中位数超过阈值 → 警示徽标（不阻断，level=warning）', async () => {
    const { run } = useDeviceReadiness({
      deps: {
        measureRttSample: queuedSamples([999, 700, 800]),
        getViewport: () => ({ width: 1920, height: 1080 }),
        isFullscreenEnabled: () => true,
        getUserAgent: () =>
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        isLocalStorageAvailable: () => true,
      },
      thresholds: { rttSampleCount: 3, rttWarningMs: 600 },
    });
    const report = await run();
    expect(reportOf(report).rtt.level).toBe('warning');
    expect(report.overall).toBe('warning');
  });

  it('全部样本失败（网络不可测）→ 按警示兜底，不 reject', async () => {
    const { run } = useDeviceReadiness({
      deps: { measureRttSample: queuedSamples([null, null, null]) },
      thresholds: { rttSampleCount: 3 },
    });
    const report = await run();
    expect(reportOf(report).rtt.level).toBe('warning');
    expect(reportOf(report).rtt.detail).toContain('无法');
  });

  it('medianOf：奇数个取中间值、偶数个取中间两数均值、无有效样本返回 null', () => {
    expect(medianOf([5, 1, 3])).toBe(3);
    expect(medianOf([4, 2, 6, 8])).toBe(5);
    expect(medianOf([null, null])).toBeNull();
    expect(medianOf([])).toBeNull();
    expect(medianOf([10, null, 20])).toBe(15);
  });
});

describe('屏幕分辨率 / 视口', () => {
  it('低于建议阈值 → 警示（不阻断）', async () => {
    const { run } = useDeviceReadiness({
      deps: { getViewport: () => ({ width: 800, height: 600 }) },
      thresholds: { viewportMinWidth: 1024, viewportMinHeight: 600 },
    });
    const report = await run();
    expect(reportOf(report).viewport.level).toBe('warning');
    expect(reportOf(report).viewport.detail).toContain('800×600');
  });

  it('满足建议视口 → 通过', async () => {
    const { run } = useDeviceReadiness({
      deps: { getViewport: () => ({ width: 1920, height: 1080 }) },
      thresholds: { viewportMinWidth: 1024, viewportMinHeight: 600 },
    });
    const report = await run();
    expect(reportOf(report).viewport.level).toBe('pass');
  });
});

describe('全屏支持（fullscreenEnabled 同语义）', () => {
  it('document.fullscreenEnabled === true → 通过', async () => {
    const { run } = useDeviceReadiness({
      deps: { isFullscreenEnabled: () => true },
    });
    const report = await run();
    expect(reportOf(report).fullscreen.level).toBe('pass');
  });

  it('fullscreenEnabled 不可用 → 警示（与既有全屏守卫同为软引导，不阻断）', async () => {
    const { run } = useDeviceReadiness({
      deps: { isFullscreenEnabled: () => false },
    });
    const report = await run();
    expect(reportOf(report).fullscreen.level).toBe('warning');
  });
});

describe('浏览器兼容：userAgent 内核白名单 + localStorage 特性检测', () => {
  it('detectKernel：Chrome / Edge / Firefox / Safari 均识别为支持', () => {
    expect(
      detectKernel(
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
      )
    ).toEqual({ name: 'Chrome', supported: true });
    expect(
      detectKernel(
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0'
      )
    ).toEqual({ name: 'Edge', supported: true });
    expect(
      detectKernel(
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:115.0) Gecko/20100101 Firefox/115.0'
      )
    ).toEqual({ name: 'Firefox', supported: true });
    expect(
      detectKernel(
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15'
      )
    ).toEqual({ name: 'Safari', supported: true });
  });

  it('detectKernel：未知内核（jsdom / 旧 IE 等）→ 不支持', () => {
    expect(
      detectKernel(
        'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) jsdom/27.2.0'
      )
    ).toEqual({ name: null, supported: false });
    expect(detectKernel('')).toEqual({ name: null, supported: false });
  });

  it('内核不兼容 → critical（强警示），整体 critical', async () => {
    const { run } = useDeviceReadiness({
      deps: {
        getUserAgent: () =>
          'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) jsdom/27.2.0',
        isLocalStorageAvailable: () => true,
      },
    });
    const report = await run();
    const compat = reportOf(report).compatibility;
    expect(compat.level).toBe('critical');
    expect(compat.detail).toContain('内核');
    expect(report.overall).toBe('critical');
  });

  it('localStorage 不可用 → critical（草稿/快照留存依赖），整体 critical', async () => {
    const { run } = useDeviceReadiness({
      deps: {
        getUserAgent: () =>
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        isLocalStorageAvailable: () => false,
      },
    });
    const report = await run();
    const compat = reportOf(report).compatibility;
    expect(compat.level).toBe('critical');
    expect(compat.detail).toContain('localStorage');
  });

  it('内核支持 + localStorage 可用 → 通过', async () => {
    const { run } = useDeviceReadiness({
      deps: {
        getUserAgent: () =>
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        isLocalStorageAvailable: () => true,
      },
    });
    const report = await run();
    expect(reportOf(report).compatibility.level).toBe('pass');
  });
});

describe('异常兜底：自检自身故障按「已完成 + 全部警示」放行', () => {
  it('任一检查逻辑抛错 → 不 reject，返回全部警示的已完成报告', async () => {
    const { run } = useDeviceReadiness({
      deps: {
        measureRttSample: async () => {
          throw new Error('boom');
        },
        getViewport: () => {
          throw new Error('viewport broken');
        },
      },
    });
    const report = await run();
    expect(report.completed).toBe(true);
    expect(report.items).toHaveLength(4);
    for (const item of report.items) {
      expect(item.level).toBe('warning');
    }
    expect(report.overall).toBe('warning');
  });
});

describe('run 进度回调与报告汇总', () => {
  it('onProgress 按 rtt → viewport → fullscreen → compatibility 顺序回调', async () => {
    const { run } = useDeviceReadiness({
      deps: {
        measureRttSample: queuedSamples([100, 100, 100]),
        getViewport: () => ({ width: 1920, height: 1080 }),
        isFullscreenEnabled: () => true,
        getUserAgent: () =>
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        isLocalStorageAvailable: () => true,
      },
    });
    const progress: ReadinessItemKey[] = [];
    const report = await run((key) => progress.push(key));
    expect(progress).toEqual(['rtt', 'viewport', 'fullscreen', 'compatibility']);
    expect(report.overall).toBe('pass');
  });

  it('全部通过时整体 pass；items 携带中文标签（组件与测试共用同一份）', async () => {
    const { run } = useDeviceReadiness({
      deps: {
        measureRttSample: queuedSamples([50, 50, 50]),
        getViewport: () => ({ width: 1920, height: 1080 }),
        isFullscreenEnabled: () => true,
        getUserAgent: () =>
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        isLocalStorageAvailable: () => true,
      },
    });
    const report = await run();
    expect(report.overall).toBe('pass');
    for (const item of report.items) {
      expect(item.label).toBe(READINESS_ITEM_LABELS[item.key]);
    }
  });
});

describe('会话级留存助手（sessionStorage）', () => {
  it('save → load 同 examId 往返一致；异 examId / 垃圾数据 / 未完成报告均读不到', () => {
    const storage = {
      getItem: vi.fn(),
      setItem: vi.fn(),
      removeItem: vi.fn(),
    };
    const report: DeviceReadinessReport = {
      completed: true,
      overall: 'pass',
      items: [
        {
          key: 'rtt',
          level: 'pass',
          label: READINESS_ITEM_LABELS.rtt,
          detail: '网络时延正常（50 ms）',
        },
        {
          key: 'viewport',
          level: 'pass',
          label: READINESS_ITEM_LABELS.viewport,
          detail: '视口满足建议',
        },
        {
          key: 'fullscreen',
          level: 'pass',
          label: READINESS_ITEM_LABELS.fullscreen,
          detail: '支持全屏',
        },
        {
          key: 'compatibility',
          level: 'pass',
          label: READINESS_ITEM_LABELS.compatibility,
          detail: '兼容',
        },
      ],
    };
    saveReadinessReport(storage, 42, report);
    const key = (storage.setItem as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(key).toBe('deviceReadiness:report:42');
    // key-aware 读取：只命中 42 的留存键，99 读不到
    storage.getItem.mockImplementation((asked: string) =>
      asked === 'deviceReadiness:report:42' ? JSON.stringify(report) : null
    );
    expect(loadReadinessReport(storage, 42)).toEqual(report);
    expect(loadReadinessReport(storage, 99)).toBeNull();
    storage.getItem.mockReturnValue('not-json');
    expect(loadReadinessReport(storage, 42)).toBeNull();
    storage.getItem.mockReturnValue(JSON.stringify({ completed: false, items: [] }));
    expect(loadReadinessReport(storage, 42)).toBeNull();
  });
});
