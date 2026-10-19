import { fileURLToPath, URL } from 'node:url';

import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  // 没有这条，src/**/__tests__ 里 mount 任何 .vue 都会以 "Failed to parse...
  // <unknown file content>" 直接失败——模板级缺陷（空白列、Alert 与实际数据不符）
  // 就没有任何自动化能拦住。
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environmentOptions: {
      jsdom: {},
    },
    setupFiles: ['./src/__tests__/vitest.setup.ts'],
    environment: 'jsdom',
    // 单测放 src/**/__tests__，e2e 归 playwright，互不混跑
    include: ['src/**/__tests__/**/*.spec.ts'],
    restoreMocks: true,
    // 宿主 CPU 争用下默认 5s 超时随机击红（受害集合随机、单跑全过，C6/C7 两轮复核实测），
    // 显式固化 30s 预算；不增删用例、不改 include/setupFiles/restoreMocks/environment。
    testTimeout: 30000,
  },
});
