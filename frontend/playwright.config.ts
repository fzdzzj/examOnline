import { defineConfig, devices } from '@playwright/test';

/**
 * e2e 冒烟配置。
 * 依赖 vite dev server 的 /api 代理（不 rewrite）打到 http://localhost:8080 的真实后端，
 * 所以跑 e2e 前必须先把后端起起来（见 docs/指导Agent交接文档.md 的前端小节）。
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: 'list',
  timeout: 60000,
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:5173',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // 复用已在跑的 dev server；没有则自动起一个
  webServer: {
    command: 'pnpm dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 120000,
  },
});
