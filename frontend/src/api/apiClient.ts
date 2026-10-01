/**
 * API 层薄封装（硬约定 3：接口定义与类型一律来自 `gen:api`，这里只做拦截与映射）。
 *
 * 职责：
 * 1. 请求拦截：注入 `Authorization: Bearer <access>`（生成的客户端 `auth: false`，故必须在此注入）；
 * 2. 响应拦截：解包后端 `ApiResponse{code,message,data}`，非 0 码转 `ApiError`（带可读中文文案）；
 * 3. 401 → Refresh 单飞 → 重放（硬约定 5）；续期失败 → 清本地 + 广播会话失效（硬约定 6）。
 *
 * ⚠️ `throwOnError` 必须为 true：`client.gen.ts` 的 catch 分支在 `throwOnError` 为假时
 * 会把 AxiosError 当返回值吞掉（`e.error = e.response?.data; return e`），页面就拿不到异常了。
 */
import axios, { type AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';

import { getAccessToken, getRefreshToken, clearTokens, saveTokens } from '@/utils/token';
import { refresh as refreshContract } from './axios';
import { createClient } from './axios/client';
import { apiConfig } from './config';
import { resolveErrorMessage } from './errorMap';
import { createSessionRefresher } from './sessionRefresh';
import { ApiError, SUCCESS_CODE, type ApiEnvelope, type RetriableRequestConfig } from './types';

/**
 * 供会话续期专用的裸实例：不挂任何拦截器，避免「刷新失败 → 拦截器又触发刷新」的递归。
 */
const plainHttp = axios.create(apiConfig);
const plainClient = createClient({ axios: plainHttp, throwOnError: true });

/** 会话失效广播（store / router 各自订阅，清理用户态并跳登录页）。 */
type SessionExpiredListener = (reason: 'refresh_failed' | 'no_session') => void;
const expiredListeners = new Set<SessionExpiredListener>();

export function onSessionExpired(listener: SessionExpiredListener): () => void {
  expiredListeners.add(listener);
  return () => {
    expiredListeners.delete(listener);
  };
}

function emitSessionExpired(reason: 'refresh_failed' | 'no_session'): void {
  clearTokens();
  for (const listener of expiredListeners) {
    listener(reason);
  }
}

const refresher = createSessionRefresher({
  getAccessToken,
  getRefreshToken,
  onRotated: (tokens) => saveTokens(tokens.accessToken, tokens.refreshToken),
  onSessionLost: clearTokens,
  requestRefresh: async (refreshToken) => {
    const res = await refreshContract<true>({
      client: plainClient,
      throwOnError: true,
      body: { refreshToken },
    });
    const data = res.data?.data;
    // 契约里 TokenResponse 字段全是可选项，这里做空值收敛（硬约定 6：后端没给就是没登录）
    if (!data?.accessToken || !data?.refreshToken) {
      return null;
    }
    return { accessToken: data.accessToken, refreshToken: data.refreshToken };
  },
});

export const apiClient = axios.create(apiConfig);

apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig & RetriableRequestConfig) => {
    const token = getAccessToken();
    // 记下本次请求用的是哪个 Access Token：后续 401 才能判断「是不是令牌已经换过了」
    config._staleAccessToken = token;
    if (token) {
      config.headers.set('Authorization', `Bearer ${token}`);
    }
    return config;
  }
);

function toApiError(
  error: AxiosError,
  status: number | undefined,
  envelope: ApiEnvelope<unknown> | undefined
): ApiError {
  const code = envelope?.code;
  if (envelope && typeof code === 'number') {
    return new ApiError(code, resolveErrorMessage(code, envelope.message), envelope.data, status);
  }
  if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
    return new ApiError(-1, '请求超时，请检查网络后重试', undefined, status);
  }
  if (status === undefined) {
    return new ApiError(-1, '无法连接服务器，请确认后端已启动', undefined, status);
  }
  return new ApiError(status, resolveErrorMessage(status), undefined, status);
}

apiClient.interceptors.response.use(
  (response: AxiosResponse) => {
    const responseType = response.config.responseType;
    if (responseType === 'blob' || responseType === 'arraybuffer') {
      return response;
    }
    const envelope = response.data as ApiEnvelope<unknown> | undefined;
    const code = envelope?.code;
    // 后端异常一律走 GlobalExceptionHandler 的非 2xx 分支；此处为「200 但 code 非 0」的兜底，
    // 保证 code 语义在拦截器这一层统一收敛，页面无需再判信封。
    if (envelope && typeof code === 'number' && code !== SUCCESS_CODE) {
      throw new ApiError(
        code,
        resolveErrorMessage(code, envelope.message),
        envelope.data,
        response.status
      );
    }
    return response;
  },
  async (error: AxiosError<ApiEnvelope<unknown>>) => {
    const config = error.config as
      (RetriableRequestConfig & InternalAxiosRequestConfig) | undefined;
    const status = error.response?.status;
    const envelope = error.response?.data;

    if (status !== 401 || !config) {
      throw toApiError(error, status, envelope);
    }

    const stale = config._staleAccessToken ?? null;
    if (stale === null) {
      // 请求本就没带 Access Token（登录 / 注册 / 找回密码等公开端点）：
      // 这个 401 是「账号或密码错误」一类业务失败，没有会话可续，直接透传给页面。
      throw toApiError(error, status, envelope);
    }

    let nextAccessToken: string | null = null;
    if (!config._retry && getRefreshToken() !== null) {
      nextAccessToken = await refresher.refreshOnce(stale);
    }

    if (!nextAccessToken) {
      emitSessionExpired('refresh_failed');
      throw toApiError(error, status, envelope);
    }

    config._retry = true;
    // 重放：请求拦截器会注入轮换后的新 Access Token
    return apiClient.request(config);
  }
);

/** hey-api 客户端：所有生成函数都要显式传 `{ client }`，这样拦截器与类型都走我们这套。 */
export const client = createClient({ axios: apiClient, throwOnError: true });

/**
 * 统一解包：`AxiosResponse<ApiResponse<T>>` → 契约负载 `T`。
 * 失败已由响应拦截器转成 `ApiError`，这里只处理成功路径的类型收敛。
 */
export async function unwrap<T>(
  request: Promise<{ data?: ApiEnvelope<T> }>
): Promise<T | undefined> {
  const response = await request;
  return response.data?.data;
}

export { apiClient as default };
