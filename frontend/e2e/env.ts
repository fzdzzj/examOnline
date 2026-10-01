import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 读取 e2e/.env 注入 process.env，避免把账号写进测试代码。
 *
 * 已存在的环境变量优先（CI 里可直接用 secrets 覆盖本地文件）。
 * e2e/.env 含凭据，必须留在 .gitignore 里，只提交 e2e/.env.example。
 */
export function loadE2eEnv(): void {
  const envPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '.env');
  if (!fs.existsSync(envPath)) {
    return;
  }
  const content = fs.readFileSync(envPath, 'utf8');
  for (const line of content.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) {
      continue;
    }
    const index = trimmed.indexOf('=');
    const key = trimmed.slice(0, index).trim();
    const value = trimmed.slice(index + 1).trim();
    if (key && !(key in process.env)) {
      process.env[key] = value;
    }
  }
}
