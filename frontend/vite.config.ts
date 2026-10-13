import { fileURLToPath, URL } from 'node:url';

import vue from '@vitejs/plugin-vue';
import { defineConfig, loadEnv } from 'vite';
import TailwindVitePlugin from '@tailwindcss/vite';
import VueRouter from 'unplugin-vue-router/vite';

export default defineConfig(() => {
  const env = loadEnv(process.env.NODE_ENV || 'development', process.cwd(), 'VITE_');

  return {
    base: env.VITE_BASE_PATH || '/',
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
      // 注意：这里**不设** preserveSymlinks。本包不是 monorepo 成员，而 pnpm 的严格
      // 布局把传递依赖放在 .pnpm/<pkg>/node_modules 下；一旦保留符号链接，链接目录
      // 里找不到兄弟依赖，rollup 就会报 “failed to resolve import @tanstack/query-core”。
    },
    plugins: [
      // 文件路由：约定 src/pages/**/*.page.vue（对齐参考项目）
      VueRouter({
        dts: './typed-router.d.ts',
        extensions: ['.page.vue'],
        routesFolder: './src/pages',
      }),
      vue(),
      TailwindVitePlugin(),
    ],
    server: {
      host: true,
      port: 5173,
      open: true,
      proxy: {
        // ⚠️ examOnline 后端的接口路径本身就带 /api 前缀（14 个 Controller 全挂 /api/**）。
        // 参考项目 crm-front 的网关会剥掉 /api，所以它写了 rewrite: path.replace(/^\/api/, '')。
        // 这里**绝对不能** rewrite，否则 /api/auth/login 会被转成 /auth/login → 后端全部 404。
        // 该约定由 e2e/ 与联调验证共同保证。
        '/api': {
          target: env.VITE_DEV_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true,
          cookieDomainRewrite: 'localhost',
        },
        // 题干图片（add-question-image）：下载端点置 /api 外（公开只读，随机文件名即凭据），
        // dev 同源形态与 /api 一致——不加 rewrite，原样转发到后端 /images/*
        '/images': {
          target: env.VITE_DEV_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true,
          cookieDomainRewrite: 'localhost',
        },
      },
    },
  };
});
