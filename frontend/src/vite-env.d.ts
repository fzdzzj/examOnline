/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_BASE_PATH?: string;
  readonly VITE_DEV_PROXY_TARGET?: string;
  readonly VITE_CONTRACT_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
