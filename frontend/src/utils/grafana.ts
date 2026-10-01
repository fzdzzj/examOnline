/**
 * 观测面板（Grafana）只读入口的地址与连通性判定（阶段 21 缺口 5）。
 *
 * ⚠️ 本文件刻意**不含任何宿主端口字面量**：Grafana 跑在哪、映射到哪个端口，
 * 真源是 `docker/observability/docker-compose.observability.yml` 的 `grafana` 服务；
 * 前端只读 `VITE_GRAFANA_BASE_URL`（见 `src/vite-env.d.ts` 与 `.env.example`）。
 * 未配置就是未配置——不用默认值兜底，那等于把端口又抄回仓库。
 *
 * 「打不开」与「面板无数据」是两件事（spec-delta 要求不得把前者渲染成后者）：
 * - `unconfigured`：没给地址，入口不可用；
 * - `unreachable`：给了地址但连不上（Grafana 没起 / 端口不通 / 跨源被拦）；
 * - `reachable`：地址能连上，点击即在新标签打开只读面板。
 * 三种状态都不渲染任何图表数值——图表口径只在 Grafana 那边。
 */

export type GrafanaState = 'unconfigured' | 'unreachable' | 'reachable';

/** 归一化后的基址；未配置 / 空串返回 null。 */
export function grafanaBaseUrl(env: ImportMetaEnv = import.meta.env): string | null {
  const raw = env.VITE_GRAFANA_BASE_URL?.trim();
  if (!raw) return null;
  return raw.replace(/\/+$/, '');
}

/** Grafana 的健康探测端点（容器内即 `/api/health`，无需知道面板 uid）。 */
export function grafanaHealthUrl(base: string): string {
  return `${base.replace(/\/+$/, '')}/api/health`;
}

/** 面板入口：观测栈把「在线考试系统总览」配成了 Grafana 首页看板，故跳基址即可。 */
export function grafanaDashboardUrl(base: string): string {
  return `${base.replace(/\/+$/, '')}/`;
}

/**
 * 连通性探测：`no-cors` 只关心「有没有回应」，不读响应体（跨源读不到也不该读）。
 * 网络层失败（连不上）才 reject；被 CORS 拦但服务器活着会 resolve 一个不透明响应。
 */
export async function probeGrafana(base: string, timeoutMs = 3000): Promise<boolean> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    await fetch(grafanaHealthUrl(base), {
      method: 'GET',
      mode: 'no-cors',
      cache: 'no-store',
      signal: controller.signal,
    });
    return true;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}
