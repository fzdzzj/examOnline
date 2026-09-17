import { expect, test } from '@playwright/test';

import { loadE2eEnv } from './env';

/**
 * 认证冒烟：真实登录 → 首页布局 → 登出。
 *
 * 这条用例存在的核心价值是验证「dev 代理不 rewrite /api」这条硬约定：
 * 断言里显式检查请求 URL 仍带 `/api` 前缀（后端 Controller 自带该前缀，
 * 一旦被代理 rewrite 掉就会 404，而 404 在页面上只会表现成一句模糊的登录失败）。
 *
 * 前置：后端已在 8080 起来（docker 三容器 + spring-boot:run dev profile），
 * dev server 由 playwright.config.ts 的 webServer 负责起或复用。
 */
loadE2eEnv();

const username = process.env.E2E_USERNAME;
const password = process.env.E2E_PASSWORD;

test.beforeEach(async ({ page }) => {
  // 干净会话：不带上一次的 token，否则守卫会直接把 /#/login 重定向到 /
  await page.addInitScript(() => {
    localStorage.clear();
  });
});

test('E2E-A01 管理员登录后进入首页并可见角色菜单', async ({ page }) => {
  test.skip(!username || !password, '缺少 E2E_USERNAME / E2E_PASSWORD（见 e2e/.env.example）');

  await page.goto('/#/login');
  await page.getByPlaceholder('学号 / 工号').fill(username as string);
  await page.getByPlaceholder('登录密码').fill(password as string);

  // 登录与随后的 /me 都必须打到带 /api 前缀的路径上
  const [loginRes, meRes] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/auth/login')),
    page.waitForResponse((r) => r.url().includes('/api/auth/me')),
    page.getByRole('button', { name: /登\s*录/ }).click(),
  ]);

  expect(loginRes.url()).toContain('/api/auth/login');
  expect(loginRes.status()).toBe(200);
  expect(meRes.url()).toContain('/api/auth/me');
  expect(meRes.status()).toBe(200);

  await expect(page.getByRole('menuitem', { name: '首页' })).toBeVisible();
  await expect(page.getByText('管理员', { exact: true })).toBeVisible();
});

test('E2E-A02 未登录访问受保护路由会被送回登录页', async ({ page }) => {
  await page.goto('/#/change-password');
  await page.waitForURL(/#\/login/);
  await expect(page.getByPlaceholder('学号 / 工号')).toBeVisible();
  // 带上 redirect，登录后能回到原目标（守卫的可读性约定）
  expect(page.url()).toContain('redirect=');
});

test('E2E-A03 登出后回到登录页且受保护路由不再可达', async ({ page }) => {
  test.skip(!username || !password, '缺少 E2E_USERNAME / E2E_PASSWORD（见 e2e/.env.example）');

  await page.goto('/#/login');
  await page.getByPlaceholder('学号 / 工号').fill(username as string);
  await page.getByPlaceholder('登录密码').fill(password as string);
  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/auth/login')),
    page.getByRole('button', { name: /登\s*录/ }).click(),
  ]);
  await expect(page.getByRole('menuitem', { name: '首页' })).toBeVisible();

  await page.locator('.user-trigger').click();
  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/auth/logout')),
    page.getByText('退出登录').click(),
  ]);

  await expect(page.getByPlaceholder('学号 / 工号')).toBeVisible({ timeout: 15_000 });
  await page.goto('/#/change-password');
  await page.waitForURL(/#\/login/);
});
