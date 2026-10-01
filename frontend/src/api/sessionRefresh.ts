/**
 * Refresh 单飞（single-flight）。
 *
 * 为什么必须是单飞而不是「每个 401 各自刷新」：
 * 后端 Refresh Token 是**一次性轮换**的（`AuthService#refresh` 校验旧 jti 后立即作废），
 * 并且带**复用检测**——同一个旧 Refresh Token 被第二次使用时，后端判定为令牌泄露，
 * 直接 `bumpSessionVersion` 把该用户的会话版本顶掉 → **该用户所有端全部下线**。
 * 所以并发 N 个 401 若各自刷新，只有一个能成功、其余全部触发复用检测，属真实事故路径。
 *
 * 本模块刻意不依赖 axios / vue / store，全部靠注入的回调工作，便于 vitest 直接单测。
 */

/** 后端轮换后返回的双 Token（契约里字段是可选项，故由调用方收敛后传入）。 */
export interface RotatedTokens {
  accessToken: string;
  refreshToken: string;
}

export interface SessionRefresherDeps {
  /** 读当前 Access Token，用于识别「401 晚到」的请求。 */
  getAccessToken: () => string | null;
  /** 读当前 Refresh Token。 */
  getRefreshToken: () => string | null;
  /** 真正去调后端刷新；失败请抛错。 */
  requestRefresh: (refreshToken: string) => Promise<RotatedTokens | null>;
  /** 刷新成功后落盘（Rotation：新旧 Token 都要覆盖）。 */
  onRotated: (tokens: RotatedTokens) => void;
  /** 无 Refresh Token 或刷新失败：清理本地会话。 */
  onSessionLost: () => void;
}

export interface SessionRefresher {
  /**
   * 请求一次续期。
   * @param staleAccessToken 触发 401 的那个请求当时用的 Access Token
   * @returns 可用的新 Access Token；null 表示会话已失效，调用方应跳登录页
   */
  refreshOnce: (staleAccessToken: string | null) => Promise<string | null>;
}

export function createSessionRefresher(deps: SessionRefresherDeps): SessionRefresher {
  /** 正在进行中的续期；并发 401 共用同一个 Promise，只发一次后端请求。 */
  let inflight: Promise<string | null> | null = null;

  const doRefresh = async (): Promise<string | null> => {
    const refreshToken = deps.getRefreshToken();
    if (!refreshToken) {
      deps.onSessionLost();
      return null;
    }
    try {
      const rotated = await deps.requestRefresh(refreshToken);
      if (!rotated || !rotated.accessToken) {
        deps.onSessionLost();
        return null;
      }
      deps.onRotated(rotated);
      return rotated.accessToken;
    } catch {
      deps.onSessionLost();
      return null;
    }
  };

  const refreshOnce = (staleAccessToken: string | null): Promise<string | null> => {
    // 令牌已被别的请求换过（本请求的 401 是晚到的）：直接复用新令牌，绝不再刷一次。
    const current = deps.getAccessToken();
    if (current && current !== staleAccessToken) {
      return Promise.resolve(current);
    }
    if (inflight) {
      return inflight;
    }
    const p = doRefresh();
    inflight = p;
    // 先于所有等待者清空 inflight，让「下一批」401 能重新发起（而不是挂在一个已完成的 Promise 上）。
    void p.finally(() => {
      if (inflight === p) {
        inflight = null;
      }
    });
    return p;
  };

  return { refreshOnce };
}
