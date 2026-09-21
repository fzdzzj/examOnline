/**
 * 路由守卫装配点。
 *
 * ⚠️ 前端守卫不是安全边界（硬约定 4）：这里只决定「跳不跳登录页 / 显不显示 403」，
 * 后端 `@RequireRole` 与 `assertTeacherOwns*` 才是权限的唯一裁决者。
 * 所有规则收敛在 `access.ts#decideNavigation` 这一个函数里，强制改密前置也走同一入口。
 */
import { message } from 'ant-design-vue';
import type { Router } from 'vue-router';
import type { Store } from 'vuex';

import { ApiError } from '@/api/types';
import { highestRoleOf, isAuthenticatedOf, type State } from '@/store';
import { getAccessToken } from '@/utils/token';
import { decideNavigation } from './access';

export function setupRouterGuards(router: Router, appStore: Store<State>): void {
  router.beforeEach(async (to) => {
    // 登录态只在「本地有 token 且还没问过后端」时解析一次；每次导航都重新问会白白打后端。
    if (getAccessToken() !== null && !appStore.state.profileLoaded) {
      try {
        await appStore.dispatch('fetchProfile');
      } catch (error) {
        appStore.commit('reset');
        if (!(error instanceof ApiError) || error.status !== 401) {
          message.error(error instanceof Error ? error.message : '无法校验登录状态');
        }
      }
    }

    const decision = decideNavigation({
      path: to.path,
      loggedIn: isAuthenticatedOf(appStore.state.user),
      role: highestRoleOf(appStore.state.user),
      // 字段恒有值（Boolean 装载、non_null），缺省兜底 false，避免误拦正常用户。
      mustChangePassword: appStore.state.user?.mustChangePassword === true,
      notFound: to.meta.notFound === true,
    });

    switch (decision.action) {
      case 'allow':
        return true;
      case 'redirect':
        return { path: decision.to, query: decision.query };
      case 'forbid':
        return { path: decision.to };
    }
  });
}
