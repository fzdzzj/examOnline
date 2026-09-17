import { fileURLToPath, URL } from 'node:url';

import { defineConfig } from 'vitest/config';

export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    // 单测放 src/**/__tests__，e2e 归 playwright，互不混跑
    include: ['src/**/__tests__/**/*.spec.ts'],
    restoreMocks: true,
  },
});
