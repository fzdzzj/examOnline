import type { AxiosRequestConfig } from 'axios';

/**
 * 后端统一响应壳（ApiResponse）：`{ code, message, data }`，`code === 0` 为成功。
 * 这里**只描述信封**，`T` 一律来自 `gen:api` 生成的契约类型，不手写业务模型（硬约定 3）。
 */
export interface ApiEnvelope<T> {
  code?: number;
  message?: string;
  data?: T;
}

/** 成功码（ResponseCode.SUCCESS）。 */
export const SUCCESS_CODE = 0;

/** 业务错误：由响应拦截器统一抛出，携带后端业务码，便于页面按码分支。 */
export class ApiError extends Error {
  readonly code: number;
  readonly data?: unknown;
  /** 原始 HTTP 状态码（401/403/423/429/…），供守卫与提示区分场景。 */
  readonly status?: number;

  constructor(code: number, message: string, data?: unknown, status?: number) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.data = data;
    this.status = status;
  }
}

/**
 * 带重试标记的请求配置。
 * `_retry`：重放过一次就不再重放，防 401 死循环。
 * `_staleAccessToken`：请求发出当时用的 Access Token，供单飞逻辑判断「这个 401 是不是晚到的」。
 */
export interface RetriableRequestConfig extends AxiosRequestConfig {
  _retry?: boolean;
  _staleAccessToken?: string | null;
}
