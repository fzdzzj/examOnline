/**
 * 响应解包与错误映射 + 401 → 单飞续期 → 重放（拦截器层的接线）。
 *
 * 做法：给 `apiClient` 换一个受控 adapter（不起真实网络），让**真实的**请求/响应拦截器跑完整链路；
 * 只把会话续期用的生成函数 `refresh` 换成 mock（它走的是另一个不带拦截器的裸实例，
 * 目的是避开网络，同时保留 apiClient 里「契约字段全可选 → 收敛」这段逻辑被覆盖的机会）。
 */
import {
  AxiosError,
  AxiosHeaders,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { refreshMock } = vi.hoisted(() => ({ refreshMock: vi.fn() }));

vi.mock('../axios', () => ({ refresh: refreshMock }));

import { clearTokens, getAccessToken, getRefreshToken, saveTokens } from '@/utils/token';
import { apiClient, onSessionExpired, unwrap } from '../apiClient';
import { ApiError } from '../types';
import { resolveErrorMessage } from '../errorMap';

interface Reply {
  status?: number;
  body?: unknown;
}

/** 按调用序号决定这次「服务端」怎么回；测试里自己维护序号语义。 */
type Responder = (config: InternalAxiosRequestConfig, index: number) => Reply;

const sent: InternalAxiosRequestConfig[] = [];
let respond: Responder = () => ({ status: 200, body: { code: 0 } });

const adapter = vi.fn(async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
  sent.push(config);
  const { status = 200, body } = respond(config, sent.length - 1);
  const response: AxiosResponse = {
    data: body,
    status,
    statusText: 'mocked',
    headers: new AxiosHeaders(),
    config,
  };
  if (status >= 400) {
    throw new AxiosError(`HTTP ${status}`, AxiosError.ERR_BAD_RESPONSE, config, {}, response);
  }
  return response;
});

apiClient.defaults.adapter = adapter;

/** 取某次实际发出的 Authorization 头。 */
const authOf = (index: number): string | undefined =>
  String(sent[index]?.headers?.get?.('Authorization') ?? '') || undefined;

function okEnvelope(data: unknown): Reply {
  return { status: 200, body: { code: 0, message: 'success', data } };
}

function bizFailure(code: number, message: string, status = 400): Reply {
  return { status, body: { code, message } };
}

beforeEach(() => {
  sent.length = 0;
  respond = () => ({ status: 200, body: { code: 0 } });
  refreshMock.mockReset();
  clearTokens();
});

describe('请求拦截器：Bearer 注入', () => {
  it('本地有 Access Token 时带上 Authorization', async () => {
    saveTokens('access-0', 'refresh-0');
    respond = () => okEnvelope({ id: 1 });

    await apiClient.get('/api/auth/me');

    expect(authOf(0)).toBe('Bearer access-0');
  });

  it('未登录时不注入 Authorization（公开端点保持干净）', async () => {
    respond = () => okEnvelope(null);

    await apiClient.post('/api/auth/login');

    expect(authOf(0)).toBeUndefined();
  });
});

describe('响应解包', () => {
  it('code === 0：放行并由 unwrap 取出契约负载', async () => {
    respond = () => okEnvelope({ id: 7, username: 'stu01' });

    const payload = await unwrap<{ id: number; username: string }>(apiClient.get('/api/auth/me'));

    expect(payload).toEqual({ id: 7, username: 'stu01' });
  });

  it('200 但业务码非 0：转 ApiError，携带业务码与后端 data', async () => {
    respond = () => ({
      status: 200,
      body: { code: 1002, message: 'username already exists', data: { field: 'username' } },
    });

    const error = await apiClient.get('/api/users').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe(1002);
    expect((error as ApiError).status).toBe(200);
    expect((error as ApiError).data).toEqual({ field: 'username' });
    expect((error as ApiError).message).toContain('该账号已存在');
  });

  it('信封缺 code 时不误判为失败（后端非 ApiResponse 响应可穿透）', async () => {
    respond = () => ({ status: 200, body: { something: 'else' } });

    await expect(apiClient.get('/api/ping')).resolves.toBeDefined();
  });
});

describe('错误映射', () => {
  it('非 2xx 带业务码：按码表出中文文案，HTTP 状态一并保留', async () => {
    respond = () => bizFailure(1007, 'account locked', 423);

    const error = (await apiClient.get('/api/auth/login').catch((e: unknown) => e)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe(1007);
    expect(error.status).toBe(423);
    expect(error.message).toBe(
      '连续登录失败次数过多，账号已被临时锁定，请稍后再试（account locked）'
    );
  });

  it('非 2xx 无业务码：退回 HTTP 状态语义', async () => {
    respond = () => ({ status: 500, body: undefined });

    const error = (await apiClient.get('/api/exams').catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe(500);
    expect(error.message).toBe('系统繁忙，请稍后重试');
  });

  it('网关返回未登记的状态码：兜底提示带上码值，不裸奔', async () => {
    respond = () => ({ status: 418, body: undefined });

    const error = (await apiClient.get('/api/exams').catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe(418);
    expect(error.message).toBe('请求失败（错误码 418）');
  });

  it('连不上后端（无响应）：给出可行动提示而不是 undefined', async () => {
    respond = () => {
      throw new AxiosError(
        'Network Error',
        AxiosError.ERR_NETWORK,
        {} as InternalAxiosRequestConfig
      );
    };

    const error = (await apiClient.get('/api/exams').catch((e: unknown) => e)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe(-1);
    expect(error.message).toBe('无法连接服务器，请确认后端已启动');
  });

  it('resolveErrorMessage：已知码拼接后端上下文，未知码兜底', () => {
    expect(resolveErrorMessage(1005)).toBe('账号或密码错误');
    expect(resolveErrorMessage(1005, 'bad credentials')).toBe('账号或密码错误（bad credentials）');
    expect(resolveErrorMessage(1005, '账号或密码错误')).toBe('账号或密码错误');
    expect(resolveErrorMessage(9999, 'boom')).toBe('boom');
    expect(resolveErrorMessage(undefined)).toBe('请求失败');
  });
});

describe('401 处置', () => {
  it('请求本来就没带 Access Token：当作业务失败透传，绝不触发刷新', async () => {
    // beforeEach 已清空本地令牌：这条正是「登录失败」的形状
    respond = () => bizFailure(1005, 'bad credentials', 401);

    const error = (await apiClient.post('/api/auth/login').catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe(1005);
    expect(refreshMock).not.toHaveBeenCalled();
    expect(sent).toHaveLength(1);
  });

  it('并发两个 401 只刷一次，各自重放成功（拦截器层的单飞接线）', async () => {
    saveTokens('access-0', 'refresh-0');
    refreshMock.mockResolvedValue({
      data: { code: 0, data: { accessToken: 'access-1', refreshToken: 'refresh-1' } },
    });
    respond = (_config, index) =>
      index < 2 ? bizFailure(1009, 'token expired', 401) : okEnvelope({ ok: true });

    const [a, b] = await Promise.all([
      apiClient.get('/api/exams').then((r) => r.data),
      apiClient.get('/api/classes').then((r) => r.data),
    ]);

    expect(refreshMock).toHaveBeenCalledTimes(1);
    expect(sent).toHaveLength(4);
    // 前两次带旧令牌，后两次（重放）带轮换后的新令牌
    expect(authOf(0)).toBe('Bearer access-0');
    expect(authOf(1)).toBe('Bearer access-0');
    expect(authOf(2)).toBe('Bearer access-1');
    expect(authOf(3)).toBe('Bearer access-1');
    expect(a).toEqual({ code: 0, message: 'success', data: { ok: true } });
    expect(b).toEqual({ code: 0, message: 'success', data: { ok: true } });
    // Rotation：新旧令牌都要覆盖落盘
    expect(getAccessToken()).toBe('access-1');
    expect(getRefreshToken()).toBe('refresh-1');
  });

  it('续期成功但契约缺字段：判定会话失效并广播（硬约定 6）', async () => {
    saveTokens('access-0', 'refresh-0');
    const reasons: string[] = [];
    const off = onSessionExpired((reason) => reasons.push(reason));
    refreshMock.mockResolvedValue({ data: { code: 0, data: { accessToken: 'access-1' } } });
    respond = () => bizFailure(1009, 'token expired', 401);

    const error = (await apiClient.get('/api/exams').catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe(1009);
    expect(reasons).toEqual(['refresh_failed']);
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    // 没刷成功就绝不重放，避免把 401 循环变成请求风暴
    expect(sent).toHaveLength(1);
    off();
  });

  it('后端拒绝续期（refresh 抛错）：同样清本地 + 广播，原始错误透传给页面', async () => {
    saveTokens('access-0', 'refresh-0');
    const reasons: string[] = [];
    const off = onSessionExpired((reason) => reasons.push(reason));
    refreshMock.mockRejectedValue(
      new AxiosError('HTTP 401', undefined, {} as InternalAxiosRequestConfig)
    );
    respond = () => bizFailure(1009, 'refresh token reused', 401);

    const error = (await apiClient.get('/api/exams').catch((e: unknown) => e)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.message).toBe('登录已过期，请重新登录（refresh token reused）');
    expect(reasons).toEqual(['refresh_failed']);
    expect(sent).toHaveLength(1);
    off();
  });
});
