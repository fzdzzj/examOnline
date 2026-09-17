<template>
  <Layout class="layout">
    <Layout.Sider
      v-model:collapsed="collapsed"
      :width="208"
      collapsible
      :trigger="null"
      theme="light"
      class="sider"
    >
      <div class="logo">在线考试系统</div>
      <Menu mode="inline" :items="menuItems" :selected-keys="selectedKeys" @click="onMenuClick" />
    </Layout.Sider>

    <Layout>
      <Layout.Header class="header">
        <span class="collapse-trigger" @click="collapsed = !collapsed">
          {{ collapsed ? '☰' : '✕' }}
        </span>
        <div class="header-right">
          <Tag v-for="role in roles" :key="role" color="blue">{{ roleLabel(role) }}</Tag>
          <Dropdown>
            <span class="user-trigger">
              {{ displayName }}
              <span class="caret">▾</span>
            </span>
            <template #overlay>
              <Menu @click="onUserMenuClick">
                <MenuItem key="change-password">修改密码</MenuItem>
                <MenuItem key="logout">退出登录</MenuItem>
              </Menu>
            </template>
          </Dropdown>
        </div>
      </Layout.Header>

      <Layout.Content class="content">
        <router-view />
      </Layout.Content>
    </Layout>
  </Layout>
</template>

<script setup lang="ts">
import { Dropdown, Layout, Menu, MenuItem, Tag, message, type MenuProps } from 'ant-design-vue';
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useStore } from 'vuex';

import { canAccess } from '@/router/access';
import { highestRoleOf, isAuthenticatedOf, type State } from '@/store';

const router = useRouter();
const route = useRoute();
const store = useStore<State>();

const collapsed = ref(false);

const user = computed(() => store.state.user);
const roles = computed(() => user.value?.roles ?? []);
const displayName = computed(() => user.value?.name || user.value?.username || '未登录');

const roleLabel = (role: string): string => {
  switch (String(role).toUpperCase()) {
    case 'ADMIN':
      return '管理员';
    case 'TEACHER':
      return '教师';
    case 'STUDENT':
      return '学生';
    default:
      return String(role);
  }
};

/**
 * 菜单按角色过滤。⚠️ 这只是「看不看得到」的体验层（硬约定 4）：
 * 手输 URL 依然会被后端 @RequireRole 拒绝，前端不声称这里是权限边界。
 */
const menuItems = computed<MenuProps['items']>(() => {
  const loggedIn = isAuthenticatedOf(user.value);
  const role = highestRoleOf(user.value);
  const items: Array<{ key: string; label: string; disabled: boolean }> = [
    { key: '/', label: '首页', disabled: !loggedIn },
    { key: '/change-password', label: '修改密码', disabled: !loggedIn },
  ];
  const sections: Array<{ prefix: string; label: string }> = [
    { prefix: '/admin', label: '管理端（阶段 20+ 开放）' },
    { prefix: '/teacher', label: '教师端（阶段 20+ 开放）' },
    { prefix: '/student', label: '学生端（阶段 20+ 开放）' },
  ];
  for (const section of sections) {
    if (canAccess(role, `${section.prefix}/`)) {
      items.push({ key: section.prefix, label: section.label, disabled: true });
    }
  }
  return items;
});

const selectedKeys = computed(() => [route.path]);

// 只跳转本阶段真实存在的页面；角色分区项是 disabled 的占位，点了也不会跳出 404。
const onMenuClick: MenuProps['onClick'] = ({ key }) => {
  const path = String(key);
  if (path === '/' || path === '/change-password') {
    void router.push(path);
  }
};

const onUserMenuClick: MenuProps['onClick'] = async ({ key }) => {
  if (key === 'change-password') {
    void router.push('/change-password');
    return;
  }
  if (key === 'logout') {
    await store.dispatch('signOut');
    message.success('已退出登录');
    void router.push('/login');
  }
};
</script>

<style scoped>
.layout {
  height: 100%;
}
.sider {
  border-right: 1px solid #f0f0f0;
}
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  font-size: 16px;
  font-weight: 600;
  color: #407fff;
  white-space: nowrap;
  overflow: hidden;
}
.header {
  height: 56px;
  line-height: 56px;
  padding: 0 16px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #f0f0f0;
}
.collapse-trigger {
  cursor: pointer;
  font-size: 16px;
  color: #666;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.user-trigger {
  cursor: pointer;
}
.caret {
  color: #999;
  font-size: 12px;
}
.content {
  padding: 16px;
  overflow: auto;
}
</style>
