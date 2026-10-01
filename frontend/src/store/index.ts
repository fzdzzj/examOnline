/**
 * 全局状态（vuex，对齐参考项目）。
 *
 * 只放「当前用户 + 角色」这一件事。用户信息**必须来自后端 `/api/auth/me`**，
 * 不从 localStorage 里缓存用户资料（硬约定 6：本地标记不代表登录态）。
 */
import { logout as logoutContract, login as loginContract, me as meContract } from '@/api/axios';
import type { CurrentUserResponse, LoginRequest, TokenResponse } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { clearTokens, saveTokens } from '@/utils/token';
import { createStore } from 'vuex';

export type RoleName = 'ADMIN' | 'TEACHER' | 'STUDENT';

export interface State {
  user: CurrentUserResponse | null;
  /** 是否已经向后端确认过登录态（区分「未知」与「确认未登录」）。 */
  profileLoaded: boolean;
}

const ROLE_LEVEL: Record<RoleName, number> = { STUDENT: 1, TEACHER: 2, ADMIN: 3 };

/**
 * 后端 `/api/auth/me` 返回的 roles 里取最高角色（ADMIN > TEACHER > STUDENT）。
 * 纯函数，guard 与布局菜单共用，避免两处各写一份判断。
 */
export function highestRoleOf(user: CurrentUserResponse | null): RoleName | null {
  let best: RoleName | null = null;
  for (const raw of user?.roles ?? []) {
    const name = String(raw).toUpperCase() as RoleName;
    if (name in ROLE_LEVEL && (best === null || ROLE_LEVEL[name] > ROLE_LEVEL[best])) {
      best = name;
    }
  }
  return best;
}

/** 登录态判据：后端给了带 id 的用户资料才算已登录（本地有 token 不算，硬约定 6）。 */
export function isAuthenticatedOf(user: CurrentUserResponse | null): boolean {
  return user !== null && user.id !== undefined;
}

export const store = createStore<State>({
  state: (): State => ({ user: null, profileLoaded: false }),

  getters: {
    isLoggedIn: (s) => isAuthenticatedOf(s.user),
    roles: (s): string[] => s.user?.roles ?? [],
    highestRole: (s) => highestRoleOf(s.user),
  },

  mutations: {
    setUser(state, user: CurrentUserResponse | null) {
      state.user = user;
      state.profileLoaded = true;
    },
    reset(state) {
      state.user = null;
      state.profileLoaded = false;
    },
  },

  actions: {
    /** 登录：拿双 Token 落盘，随后一律以 /api/auth/me 的后端裁决为准。 */
    async signIn(context, payload: LoginRequest): Promise<void> {
      const tokens = await unwrap<TokenResponse>(
        loginContract({ client, throwOnError: true, body: payload })
      );
      if (!tokens?.accessToken || !tokens.refreshToken) {
        throw new Error('登录响应缺少令牌字段');
      }
      saveTokens(tokens.accessToken, tokens.refreshToken);
      await context.dispatch('fetchProfile');
    },

    /** 向后端确认当前身份；401 由 apiClient 的续期逻辑处理，续期失败会走到这里抛错。 */
    async fetchProfile(context): Promise<CurrentUserResponse | null> {
      const user = await unwrap<CurrentUserResponse>(meContract({ client, throwOnError: true }));
      context.commit('setUser', user ?? null);
      if (!user || user.id === undefined) {
        clearTokens();
        context.commit('reset');
        return null;
      }
      return user;
    },

    /** 登出：先让后端把令牌拉黑，无论后端结果如何都清本地（不留半死状态）。 */
    async signOut(context): Promise<void> {
      try {
        await logoutContract({ client, throwOnError: true });
      } finally {
        clearTokens();
        context.commit('reset');
      }
    },
  },
});

export type AppStore = typeof store;
