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
 * 手输 URL 依然会被后端 @RequirePermission 拒绝，前端不声称这里是权限边界。
 *
 * 教师端：题库 / 标签 / 组卷等页面挂在 /teacher 前缀下，角色限定与后端 RBAC 一致。
 * 管理端（F-4）：邀请码管理与审计日志挂在 /admin 前缀下，仅 ADMIN 可见——权限边界在后端
 * 类级 @RequireRole(ADMIN) + 方法级 invite:manage / user:manage，菜单过滤只是体验层；
 * /student 分区仍是 disabled 占位。
 */
const menuItems = computed<MenuProps['items']>(() => {
  const loggedIn = isAuthenticatedOf(user.value);
  const role = highestRoleOf(user.value);
  const items: MenuProps['items'] = [
    { key: '/', label: '首页', disabled: !loggedIn },
    { key: '/change-password', label: '修改密码', disabled: !loggedIn },
  ];
  if (canAccess(role, '/teacher/')) {
    items.push({
      key: 'teacher-section',
      label: '教师端',
      children: [
        { key: '/teacher/questions', label: '题库管理' },
        { key: '/teacher/tags', label: '标签管理' },
        { key: '/teacher/papers', label: '组卷管理' },
        { key: '/teacher/classes', label: '班级管理' },
        { key: '/teacher/exams', label: '考试管理' },
        { key: '/teacher/grading', label: '批改工作台' },
        { key: '/teacher/scores', label: '成绩管理与发布' },
        { key: '/teacher/reviews', label: '成绩复核处理' },
        { key: '/teacher/absences', label: '缺考名单' },
        { key: '/teacher/makeups', label: '补考管理' },
      ],
    });
  }
  if (canAccess(role, '/student/')) {
    items.push({
      key: 'student-section',
      label: '学生端',
      children: [
        // 阶段 22 第 1 片：我的考试（列表 → 进入 → 极简作答 → 倒计时锁定）
        { key: '/student/exams', label: '我的考试' },
        { key: '/student/scores', label: '我的成绩与复核' },
      ],
    });
  }
  if (canAccess(role, '/admin/')) {
    items.push({
      key: 'admin-section',
      label: '管理端',
      children: [
        { key: '/admin/invite-codes', label: '邀请码管理' },
        { key: '/admin/audit-logs', label: '审计日志' },
      ],
    });
  }
  return items;
});

const selectedKeys = computed(() => [route.path]);

// 只跳转真实存在的页面；未列入白名单的菜单项点击不跳转（避免跳出 404）。
const NAVIGABLE_PATHS: readonly string[] = [
  '/',
  '/change-password',
  '/teacher/questions',
  '/teacher/tags',
  '/teacher/papers',
  '/teacher/classes',
  '/teacher/exams',
  '/teacher/grading',
  '/teacher/scores',
  '/teacher/reviews',
  '/teacher/absences',
  '/teacher/makeups',
  '/student/exams',
  '/student/scores',
  '/admin/invite-codes',
  '/admin/audit-logs',
];

const onMenuClick: MenuProps['onClick'] = ({ key }) => {
  const path = String(key);
  if (NAVIGABLE_PATHS.includes(path)) {
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
