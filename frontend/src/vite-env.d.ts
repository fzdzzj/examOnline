/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_BASE_PATH?: string;
  readonly VITE_DEV_PROXY_TARGET?: string;
  readonly VITE_CONTRACT_URL?: string;
  /**
   * 观测面板（Grafana）基址。**刻意不给默认值**：宿主端口属环境事实，
   * 真源是 `docker/observability/docker-compose.observability.yml` 的 grafana 服务映射，
   * 写进前端代码就等于抄一份会过期的副本（见仓库根 AGENTS.md「不承载易变事实」同口径）。
   */
  readonly VITE_GRAFANA_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
