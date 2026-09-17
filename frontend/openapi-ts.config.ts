import { defineConfig } from '@hey-api/openapi-ts';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadEnv } from 'vite';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// 用 vite 的 loadEnv 读取 .env / .env.local
const envConfig = loadEnv(process.env.NODE_ENV || 'development', process.cwd(), 'VITE_');

// 契约来源：默认读仓库根**真实导出**的 openapi.yaml（离线、可重复）。
// 需要跟运行中的后端联调时，设 VITE_CONTRACT_URL=http://localhost:8080/v3/api-docs
// （springdoc 的 api-docs.path 是 /v3/api-docs，不带 /api 前缀——见 application.yml）。
const inputUrl = envConfig['VITE_CONTRACT_URL'] || path.join(__dirname, '..', 'openapi.yaml');

export default defineConfig({
  input: inputUrl,
  output: './src/api/axios',
  // 0.97 起 output 不再有 `scripts` 开关，二次格式化统一由 `postProcess` 描述（默认 []，
  // 即不做 prettier/eslint 二次处理）——正好符合「生成物保持上游原样、diff 可追溯」的诉求。
  plugins: [
    '@hey-api/typescript',
    '@hey-api/sdk',
    {
      name: '@hey-api/client-axios',
      // axios 实例由 src/api/apiClient.ts 注入（带拦截器），生成器不自行配置；
      // 错误一律抛出，交由 apiClient 的响应拦截器统一收敛成 ApiError。
      throwOnError: true,
    },
  ],
});
