/**
 * Refresh 单飞（硬约定 5）——本阶段唯一的强校验点。
 *
 * 后端 Access Token 轮换是 Redis Lua 原子操作，且带 jti 复用检测：
 * 同一个 Refresh Token 被用第二次会直接顶掉 sessionVersion，**全端下线**。
 * 所以「并发 401 只刷一次」不是优化，是正确性要求，必须锁死。
 */
import { describe, expect, it, vi } from 'vitest';

import { createSessionRefresher } from '../sessionRefresh';

const ACCESS = 'access-0';
const REFRESH = 'refresh-0';

function makeHarness(overrides?: Partial<Parameters<typeof createSessionRefresher>[0]>) {
  let accessToken: string | null = ACCESS;
  let refreshToken: string | null = REFRESH;
  // 模拟后端的一次性轮换：refresh-0 → refresh-1 → refresh-2，用过即废（第三次来的旧令牌一律拒绝）
  const requestRefresh = vi.fn(async (token: string) => {
    if (token === REFRESH) return { accessToken: 'access-1', refreshToken: 'refresh-1' };
    if (token === 'refresh-1') return { accessToken: 'access-2', refreshToken: 'refresh-2' };
    return null;
  });
  const onRotated = vi.fn((tokens: { accessToken: string; refreshToken: string }) => {
    accessToken = tokens.accessToken;
    refreshToken = tokens.refreshToken;
  });
  const onSessionLost = vi.fn(() => {
    accessToken = null;
    refreshToken = null;
  });

  const refresher = createSessionRefresher({
    getAccessToken: () => accessToken,
    getRefreshToken: () => refreshToken,
    requestRefresh,
    onRotated,
    onSessionLost,
    ...overrides,
  });

  return {
    refresher,
    requestRefresh,
    onRotated,
    onSessionLost,
    tokens: () => ({ accessToken, refreshToken }),
  };
}

describe('createSessionRefresher', () => {
  it('并发 3 个 401 只发起一次 refresh，三方拿到同一个新令牌', async () => {
    const { refresher, requestRefresh, onRotated, tokens } = makeHarness();

    const [a, b, c] = await Promise.all([
      refresher.refreshOnce(ACCESS),
      refresher.refreshOnce(ACCESS),
      refresher.refreshOnce(ACCESS),
    ]);

    expect(requestRefresh).toHaveBeenCalledTimes(1);
    expect(requestRefresh).toHaveBeenCalledWith(REFRESH);
    expect(onRotated).toHaveBeenCalledTimes(1);
    expect(a).toBe('access-1');
    expect(b).toBe('access-1');
    expect(c).toBe('access-1');
    expect(tokens()).toEqual({ accessToken: 'access-1', refreshToken: 'refresh-1' });
  });

  it('晚到的 401（携带的已是过期令牌）直接复用新令牌，不再刷新', async () => {
    const { refresher, requestRefresh } = makeHarness();

    await refresher.refreshOnce(ACCESS);
    // 第二个请求的 401 在轮换之后才回来：它的 stale 是旧令牌，但当前已是 access-1
    const next = await refresher.refreshOnce(ACCESS);

    expect(next).toBe('access-1');
    expect(requestRefresh).toHaveBeenCalledTimes(1);
  });

  it('in-flight 释放后，携带当前令牌的新一批 401 会重新发起刷新', async () => {
    const { refresher, requestRefresh, onRotated, tokens } = makeHarness();

    await Promise.all([refresher.refreshOnce(ACCESS), refresher.refreshOnce(ACCESS)]);
    // 第一轮已结束（access-1 又过期了）：stale 等于当前令牌 → 必须重新刷新，
    // 而不是挂回上一轮那个已经完成的 Promise。
    await Promise.all([refresher.refreshOnce('access-1'), refresher.refreshOnce('access-1')]);

    expect(requestRefresh).toHaveBeenCalledTimes(2);
    expect(requestRefresh).toHaveBeenNthCalledWith(2, 'refresh-1');
    expect(onRotated).toHaveBeenCalledTimes(2);
    expect(tokens()).toEqual({ accessToken: 'access-2', refreshToken: 'refresh-2' });
  });

  it('本地没有 Refresh Token：不请求刷新，直接判定会话失效', async () => {
    const { refresher, requestRefresh, onSessionLost } = makeHarness({
      getRefreshToken: () => null,
    });

    await expect(refresher.refreshOnce(ACCESS)).resolves.toBeNull();
    expect(requestRefresh).not.toHaveBeenCalled();
    expect(onSessionLost).toHaveBeenCalledTimes(1);
  });

  it('后端拒绝续期（抛错）：判定会话失效，不重试', async () => {
    const failing = vi.fn(async () => {
      throw new Error('401');
    });
    const { refresher, onSessionLost } = makeHarness({ requestRefresh: failing });

    await expect(refresher.refreshOnce(ACCESS)).resolves.toBeNull();
    expect(failing).toHaveBeenCalledTimes(1);
    expect(onSessionLost).toHaveBeenCalledTimes(1);
  });

  it('续期返回但缺令牌字段：同样判定会话失效（硬约定 6：后端没给就是没登录）', async () => {
    const { refresher, onRotated, onSessionLost } = makeHarness({
      requestRefresh: async () => null,
    });

    await expect(refresher.refreshOnce(ACCESS)).resolves.toBeNull();
    expect(onRotated).not.toHaveBeenCalled();
    expect(onSessionLost).toHaveBeenCalledTimes(1);
  });
});
