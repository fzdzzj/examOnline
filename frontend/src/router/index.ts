import { createRouter, createWebHashHistory } from 'vue-router';
import { routes } from 'vue-router/auto-routes';

import { onSessionExpired } from '@/api/apiClient';
import { store } from '@/store';
import { setupRouterGuards } from './guard';

// 与参考项目一致用 hash 路由：静态站点部署无需服务端 rewrite。
export const router = createRouter({
  history: createWebHashHistory(),
  routes,
});

setupRouterGuards(router, store);

// 会话被后端判定失效（Refresh 失败 / 复用检测顶掉 sessionVersion）→ 清用户态并回登录页。
onSessionExpired(() => {
  store.commit('reset');
  const current = router.currentRoute.value;
  if (current.path !== '/login') {
    void router.replace({ path: '/login', query: { redirect: current.fullPath } });
  }
});

export default router;
