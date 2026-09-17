/**
 * Access / Refresh Token 的本地存取（硬约定 5：落 localStorage）。
 *
 * 这里只是**存储**，不代表登录态。是否已登录必须由后端裁决：
 * 守卫会拿这里的 token 去调 /api/auth/me 校验（见 src/router/guard.ts），
 * 校验失败即清理并跳登录页——绝不用「本地有 token」当作已登录（硬约定 6）。
 */
const ACCESS_TOKEN_KEY = 'access_token';
const REFRESH_TOKEN_KEY = 'refresh_token';

/** 读取 Access Token；未登录或存储不可用时返回 null。 */
export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

/** 读取 Refresh Token（仅用于续期）。 */
export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

/** 写入 Access Token；空值视为清理，避免存下 "null"/"undefined" 字符串。 */
export function setAccessToken(token?: string | null): void {
  if (token) {
    localStorage.setItem(ACCESS_TOKEN_KEY, token);
  } else {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
  }
}

/** 写入 Refresh Token（后端 Rotation：每次续期都要覆盖旧值）。 */
export function setRefreshToken(token?: string | null): void {
  if (token) {
    localStorage.setItem(REFRESH_TOKEN_KEY, token);
  } else {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
  }
}

/** 写入登录返回的双 Token。 */
export function saveTokens(accessToken: string, refreshToken: string): void {
  setAccessToken(accessToken);
  setRefreshToken(refreshToken);
}

/** 清空本地 Token（登出 / 续期失败 / 会话失效时调用）。 */
export function clearTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}
