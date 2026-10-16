/**
 * 考前设备与运行环境就绪度自检 Hook（add-pre-exam-device-readiness-check 阶段 2）。
 *
 * 四类检查项全部为浏览器本地能力检测（零新增后端调用，绝不用 enter 端点当探测目标——
 * 它有起表副作用）：
 * 1. 网络往返时延（RTT）：同源轻量资源 HEAD 采样，丢弃首样本（DNS/握手冷启动）取中位数；
 * 2. 屏幕分辨率 / 有效视口：低于建议阈值 → 警示（仅徽标，不阻断）；
 * 3. 全屏支持：`document.fullscreenEnabled`（与既有全屏守卫同语义）；
 * 4. 浏览器兼容：userAgent 内核白名单（Chrome/Edge/Firefox/Safari）+ 关键 API 特性检测
 *    （Fullscreen API、localStorage——草稿与答卷快照留存依赖）。
 *
 * 门控语义（知情门控，非绝对阻断，考试可用性优先）：
 * - RTT 超阈值 / 分辨率过小 → warning（仅警示徽标）；
 * - 内核不兼容 / localStorage 不可用 → critical（强警示 + 「仍要进入」二次确认）；
 * - 自检逻辑自身异常 → 兜底「已完成 + 全部警示」放行，绝不让自检故障阻断开考。
 *
 * 全部环境能力经 deps 注入（measureRttSample / getUserAgent / isFullscreenEnabled /
 * isLocalStorageAvailable / getViewport），jsdom 与单测环境下 mock 化可单测，与 UI 完全分离。
 * 会话级留存助手（loadReadinessReport / saveReadinessReport）同文件提供——页面用它把
 * 自检完成结果按 examId 写入 sessionStorage，刷新/返回再进不重复自检。
 */

export type ReadinessItemKey = 'rtt' | 'viewport' | 'fullscreen' | 'compatibility';

/** pass 通过 / warning 警示（不阻断）/ critical 强警示（需二次确认）。 */
export type ReadinessLevel = 'pass' | 'warning' | 'critical';

export interface ReadinessItem {
  key: ReadinessItemKey;
  level: ReadinessLevel;
  label: string;
  detail: string;
}

export interface DeviceReadinessReport {
  items: ReadinessItem[];
  completed: true;
  /** critical > warning > pass（组件与页面据此决定二次确认 / 直接放行）。 */
  overall: ReadinessLevel;
}

export interface UseDeviceReadinessDeps {
  /** 单次同源 HEAD 往返毫秒；网络失败返回 null（该样本按失败计数，不阻断）。 */
  measureRttSample?: () => Promise<number | null>;
  getUserAgent?: () => string;
  isFullscreenEnabled?: () => boolean;
  isLocalStorageAvailable?: () => boolean;
  getViewport?: () => { width: number; height: number };
}

export interface UseDeviceReadinessOptions {
  deps?: UseDeviceReadinessDeps;
  thresholds?: {
    /** 中位数超过该毫秒数 → RTT 警示。默认 600ms。 */
    rttWarningMs?: number;
    viewportMinWidth?: number;
    viewportMinHeight?: number;
    /** 采样次数（含被丢弃的首样本）。默认 3 次 → 取后 2 次中位数。 */
    rttSampleCount?: number;
  };
}

/** 四类检查项的中文标签：hook 生成 items、组件渲染进度/徽标共用同一份，不许各说各话。 */
export const READINESS_ITEM_LABELS: Record<ReadinessItemKey, string> = {
  rtt: '网络往返时延（RTT）',
  viewport: '屏幕分辨率 / 视口',
  fullscreen: '全屏支持',
  compatibility: '浏览器兼容（内核与关键 API）',
};

/** 会话级留存的 storage 形态：生产传 sessionStorage，单测传可控 mock。 */
export type ReadinessStorage = Pick<Storage, 'getItem' | 'setItem'> | null;

const STORAGE_KEY_PREFIX = 'deviceReadiness:report:';

export function readinessStorageKey(examId: number): string {
  return `${STORAGE_KEY_PREFIX}${examId}`;
}

/**
 * 读取本场自检留存；无留存 / 垃圾数据 / 未完成报告一律视为「未留存」。
 * 读取失败静默返回 null——留存坏了不能把学生挡在考试外。
 */
export function loadReadinessReport(
  storage: ReadinessStorage,
  examId: number
): DeviceReadinessReport | null {
  try {
    if (!storage) return null;
    const raw = storage.getItem(readinessStorageKey(examId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<DeviceReadinessReport>;
    if (parsed?.completed === true && Array.isArray(parsed.items) && parsed.items.length > 0) {
      return parsed as DeviceReadinessReport;
    }
    return null;
  } catch {
    return null;
  }
}

/** 写入本场自检留存；失败静默（会话内仍以内存态门控，不影响放行逻辑）。 */
export function saveReadinessReport(
  storage: ReadinessStorage,
  examId: number,
  report: DeviceReadinessReport
): void {
  try {
    if (!storage) return;
    storage.setItem(readinessStorageKey(examId), JSON.stringify(report));
  } catch {
    // 留存失败静默旁路
  }
}

/** 丢弃 null（失败样本）后取中位数；无有效样本返回 null。 */
export function medianOf(samples: Array<number | null>): number | null {
  const numbers = samples.filter((v): v is number => typeof v === 'number');
  if (numbers.length === 0) return null;
  const sorted = [...numbers].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[mid] : Math.round((sorted[mid - 1] + sorted[mid]) / 2);
}

/** RTT 采样循环：采样 rttSampleCount 次，丢弃首样本（DNS/握手冷启动），其余取中位数。 */
export async function measureRttMedian(
  measureSample: () => Promise<number | null>,
  sampleCount: number
): Promise<number | null> {
  const samples: Array<number | null> = [];
  for (let i = 0; i < sampleCount; i += 1) {
    try {
      samples.push(await measureSample());
    } catch {
      samples.push(null);
    }
  }
  return medianOf(samples.slice(1));
}

/**
 * 生产默认采样器：对同源轻量资源（/favicon.ico）发 HEAD 请求测往返时延。
 * 非浏览器环境（jsdom 单测等 window.fetch 不可用）直接返回 null，不发真实网络请求。
 */
export function defaultMeasureRttSample(): Promise<number | null> {
  if (typeof window === 'undefined' || typeof window.fetch !== 'function') {
    return Promise.resolve(null);
  }
  const probeUrl = `${window.location.origin}/favicon.ico`;
  const start = performance.now();
  return fetch(probeUrl, { method: 'HEAD', cache: 'no-store' })
    .then(() => Math.round(performance.now() - start))
    .catch(() => null);
}

export interface KernelDetection {
  /** 'Chrome' | 'Edge' | 'Firefox' | 'Safari'；未识别为 null。 */
  name: string | null;
  supported: boolean;
}

/**
 * userAgent 内核白名单识别。判定顺序 Edge → Firefox → Chrome → Safari：
 * Edge UA 同时含 Edg/ 与 Chrome/ 与 Safari/ 标记，必须先判 Edg/；
 * Chrome UA 含 Safari/ 标记，须在 Safari 之前判。
 */
export function detectKernel(userAgent: string): KernelDetection {
  if (/Edg\//.test(userAgent)) return { name: 'Edge', supported: true };
  if (/Firefox\//.test(userAgent)) return { name: 'Firefox', supported: true };
  if (/Chrome\//.test(userAgent)) return { name: 'Chrome', supported: true };
  if (/Safari\//.test(userAgent)) return { name: 'Safari', supported: true };
  return { name: null, supported: false };
}

/**
 * localStorage 可用性探测：能写能删即可用（Safari 隐私模式等抛异常场景判不可用）。
 * 草稿与答卷快照本地留存依赖它，不可用即强警示。
 */
export function probeLocalStorage(
  storage: Pick<Storage, 'setItem' | 'removeItem'> | null
): boolean {
  try {
    if (!storage) return false;
    const key = '__device_readiness_probe__';
    storage.setItem(key, '1');
    storage.removeItem(key);
    return true;
  } catch {
    return false;
  }
}

/** 汇总：任一 critical → critical；否则任一 warning → warning；全通过 → pass。 */
export function overallOf(items: ReadinessItem[]): ReadinessLevel {
  if (items.some((item) => item.level === 'critical')) return 'critical';
  if (items.some((item) => item.level === 'warning')) return 'warning';
  return 'pass';
}

function buildRttItem(rttMs: number | null, warningMs: number): ReadinessItem {
  if (rttMs === null) {
    return {
      key: 'rtt',
      level: 'warning',
      label: READINESS_ITEM_LABELS.rtt,
      detail: '网络时延检测失败，无法采样（不阻断，进入后留意断线降级）',
    };
  }
  if (rttMs > warningMs) {
    return {
      key: 'rtt',
      level: 'warning',
      label: READINESS_ITEM_LABELS.rtt,
      detail: `网络时延偏高（${rttMs} ms，建议阈值 ${warningMs} ms），弱网下自动保存可能延迟`,
    };
  }
  return {
    key: 'rtt',
    level: 'pass',
    label: READINESS_ITEM_LABELS.rtt,
    detail: `网络时延正常（${rttMs} ms）`,
  };
}

function buildViewportItem(
  width: number,
  height: number,
  minWidth: number,
  minHeight: number
): ReadinessItem {
  if (width < minWidth || height < minHeight) {
    return {
      key: 'viewport',
      level: 'warning',
      label: READINESS_ITEM_LABELS.viewport,
      detail: `当前视口 ${width}×${height}，低于建议 ${minWidth}×${minHeight}，答题排版可能拥挤`,
    };
  }
  return {
    key: 'viewport',
    level: 'pass',
    label: READINESS_ITEM_LABELS.viewport,
    detail: `当前视口 ${width}×${height}，满足建议分辨率`,
  };
}

function buildFullscreenItem(ok: boolean): ReadinessItem {
  if (ok) {
    return {
      key: 'fullscreen',
      level: 'pass',
      label: READINESS_ITEM_LABELS.fullscreen,
      detail: '支持全屏作答',
    };
  }
  return {
    key: 'fullscreen',
    level: 'warning',
    label: READINESS_ITEM_LABELS.fullscreen,
    detail: 'fullscreenEnabled 不可用，部分考试的全屏作答要求可能无法满足（仅引导不阻断）',
  };
}

function buildCompatibilityItem(kernel: KernelDetection, localStorageOk: boolean): ReadinessItem {
  if (!kernel.supported || !localStorageOk) {
    const reasons: string[] = [];
    if (!kernel.supported) {
      reasons.push(kernel.name ? `内核「${kernel.name}」不在支持名单` : '无法识别浏览器内核');
    }
    if (!localStorageOk) {
      reasons.push('localStorage 不可用，草稿与答卷快照无法本地留存');
    }
    return {
      key: 'compatibility',
      level: 'critical',
      label: READINESS_ITEM_LABELS.compatibility,
      detail: reasons.join('；'),
    };
  }
  return {
    key: 'compatibility',
    level: 'pass',
    label: READINESS_ITEM_LABELS.compatibility,
    detail: `内核 ${kernel.name} 兼容，localStorage 可用`,
  };
}

/** 异常兜底形态：全部警示——自检故障不得制造 critical 二次确认去挡考。 */
function fallbackReport(): DeviceReadinessReport {
  return {
    completed: true,
    overall: 'warning',
    items: [
      {
        key: 'rtt',
        level: 'warning',
        label: READINESS_ITEM_LABELS.rtt,
        detail: '自检异常，未测得网络时延',
      },
      {
        key: 'viewport',
        level: 'warning',
        label: READINESS_ITEM_LABELS.viewport,
        detail: '自检异常，无法校验视口',
      },
      {
        key: 'fullscreen',
        level: 'warning',
        label: READINESS_ITEM_LABELS.fullscreen,
        detail: '自检异常，无法校验全屏支持',
      },
      {
        key: 'compatibility',
        level: 'warning',
        label: READINESS_ITEM_LABELS.compatibility,
        detail: '自检异常，无法校验浏览器兼容性',
      },
    ],
  };
}

export interface UseDeviceReadinessView {
  /**
   * 跑一遍完整自检，返回就绪报告。
   * 任何检查逻辑抛错都不外泄——按「已完成 + 全部警示」兜底放行（run 永不 reject）。
   * onProgress 按检查顺序回调当前检查项 key（组件据此逐项呈现执行进度）。
   */
  run: (onProgress?: (key: ReadinessItemKey) => void) => Promise<DeviceReadinessReport>;
}

export function useDeviceReadiness(
  options: UseDeviceReadinessOptions = {}
): UseDeviceReadinessView {
  const deps = options.deps ?? {};
  const thresholds = {
    rttWarningMs: options.thresholds?.rttWarningMs ?? 600,
    viewportMinWidth: options.thresholds?.viewportMinWidth ?? 1024,
    viewportMinHeight: options.thresholds?.viewportMinHeight ?? 600,
    rttSampleCount: options.thresholds?.rttSampleCount ?? 3,
  };

  const run = async (
    onProgress?: (key: ReadinessItemKey) => void
  ): Promise<DeviceReadinessReport> => {
    try {
      const items: ReadinessItem[] = [];

      onProgress?.('rtt');
      const rttMs = await measureRttMedian(
        deps.measureRttSample ?? defaultMeasureRttSample,
        thresholds.rttSampleCount
      );
      items.push(buildRttItem(rttMs, thresholds.rttWarningMs));

      onProgress?.('viewport');
      const viewport = deps.getViewport
        ? deps.getViewport()
        : typeof window !== 'undefined'
          ? { width: window.innerWidth, height: window.innerHeight }
          : { width: 0, height: 0 };
      items.push(
        buildViewportItem(
          viewport.width,
          viewport.height,
          thresholds.viewportMinWidth,
          thresholds.viewportMinHeight
        )
      );

      onProgress?.('fullscreen');
      const fullscreenOk = deps.isFullscreenEnabled
        ? deps.isFullscreenEnabled()
        : typeof document !== 'undefined'
          ? document.fullscreenEnabled === true
          : false;
      items.push(buildFullscreenItem(fullscreenOk));

      onProgress?.('compatibility');
      const userAgent = deps.getUserAgent
        ? deps.getUserAgent()
        : typeof navigator !== 'undefined'
          ? navigator.userAgent
          : '';
      const kernel = detectKernel(userAgent);
      const localStorageOk = deps.isLocalStorageAvailable
        ? deps.isLocalStorageAvailable()
        : probeLocalStorage(typeof window !== 'undefined' ? window.localStorage : null);
      items.push(buildCompatibilityItem(kernel, localStorageOk));

      return { items, completed: true, overall: overallOf(items) };
    } catch {
      return fallbackReport();
    }
  };

  return { run };
}
