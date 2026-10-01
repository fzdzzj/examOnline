/**
 * axios 实例配置。
 *
 * ⚠️ baseURL 必须是空串：examOnline 后端 14 个 Controller 全部挂在 `/api/**` 下，
 * `openapi.yaml` 里的路径**本身就带** `/api`（例如 `/api/auth/login`）。
 * 若在这里再设 `baseURL: '/api'`，hey-api 的 buildUrl 会拼成 `/api/api/auth/login`；
 * 若在 dev 代理里 rewrite 掉 `/api`，后端会全部 404（见 vite.config.ts 注释）。
 * dev 走 Vite 代理、生产走同源反向代理，两种情况都不需要 baseURL。
 */
export const apiConfig = {
  baseURL: '',
  timeout: 15000,
};
