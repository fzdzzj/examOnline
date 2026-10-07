<template>
  <div class="landing-page">
    <Card class="welcome-card" :bordered="false">
      <div class="welcome-header">
        <h2 class="welcome-title">欢迎，{{ displayName }}！</h2>
        <div class="welcome-roles">
          <Tag v-for="role in roles" :key="role" color="blue">{{ roleLabel(role) }}</Tag>
          <span v-if="roles.length === 0">-</span>
        </div>
      </div>
      <p class="welcome-subtitle">欢迎使用在线考试系统。您可从下方常用入口快速前往核心功能。</p>
    </Card>

    <Card class="mt-4" title="常用入口" :bordered="false">
      <div class="quick-entries-grid">
        <Card
          v-for="entry in quickEntries"
          :key="entry.key"
          hoverable
          size="small"
          class="entry-card"
          @click="onEntryClick(entry.path)"
        >
          <div class="entry-header">
            <span class="entry-title">{{ entry.label }}</span>
            <span class="entry-arrow">→</span>
          </div>
          <p class="entry-desc">{{ entry.description }}</p>
        </Card>
      </div>
    </Card>

    <Card class="mt-4" title="当前身份详情" :bordered="false">
      <Descriptions :column="1" bordered size="middle">
        <DescriptionsItem label="用户 ID">{{ user?.id ?? '-' }}</DescriptionsItem>
        <DescriptionsItem label="账号">{{ user?.username ?? '-' }}</DescriptionsItem>
        <DescriptionsItem label="姓名">{{ user?.name ?? '-' }}</DescriptionsItem>
        <DescriptionsItem label="邮箱">
          {{ user?.email || '未绑定（无法使用找回密码）' }}
        </DescriptionsItem>
        <DescriptionsItem label="角色">
          <Tag v-for="role in roles" :key="role" color="blue">{{ roleLabel(role) }}</Tag>
          <span v-if="roles.length === 0">-</span>
        </DescriptionsItem>
        <DescriptionsItem label="权限点">
          <span class="permissions">{{ permissions.join('、') || '-' }}</span>
        </DescriptionsItem>
      </Descriptions>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { Card, Descriptions, DescriptionsItem, Tag } from 'ant-design-vue';
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { useStore } from 'vuex';

import { canAccess } from '@/router/access';
import { highestRoleOf, type State } from '@/store';

const router = useRouter();
const store = useStore<State>();

const user = computed(() => store.state.user);
const roles = computed(() => user.value?.roles ?? []);
const permissions = computed(() => user.value?.permissions ?? []);
const displayName = computed(() => user.value?.name || user.value?.username || '用户');

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

interface QuickEntry {
  key: string;
  label: string;
  path: string;
  description: string;
}

/**
 * 常用入口：对齐 (dashboard).page.vue 侧边栏既有路由清单与 canAccess(role, prefix)
 * 角色过滤逻辑，零新造路由，零新增数据请求。
 */
const quickEntries = computed<QuickEntry[]>(() => {
  const role = highestRoleOf(user.value);
  const items: QuickEntry[] = [];

  if (canAccess(role, '/student/')) {
    items.push(
      {
        key: 'student-exams',
        label: '我的考试',
        path: '/student/exams',
        description: '查看进行中与历史考试，进入在线作答',
      },
      {
        key: 'student-scores',
        label: '我的成绩与复核',
        path: '/student/scores',
        description: '查询已发布成绩与申请成绩复核',
      }
    );
  }

  if (canAccess(role, '/teacher/')) {
    items.push(
      {
        key: 'teacher-exams',
        label: '考试管理',
        path: '/teacher/exams',
        description: '创建、发布考试与查看考试详情',
      },
      {
        key: 'teacher-grading',
        label: '批改工作台',
        path: '/teacher/grading',
        description: '主观题判分与批改进度推进',
      },
      {
        key: 'teacher-scores',
        label: '成绩管理与发布',
        path: '/teacher/scores',
        description: '成绩汇总、发布、撤回与报表导出',
      },
      {
        key: 'teacher-papers',
        label: '组卷管理',
        path: '/teacher/papers',
        description: '手动组卷、抽题与试卷快照管理',
      },
      {
        key: 'teacher-questions',
        label: '题库管理',
        path: '/teacher/questions',
        description: '题目录入、编辑与题型标签维护',
      },
      {
        key: 'teacher-classes',
        label: '班级管理',
        path: '/teacher/classes',
        description: '班级花名册与学生入班/转班',
      },
      {
        key: 'teacher-reviews',
        label: '成绩复核处理',
        path: '/teacher/reviews',
        description: '审核并处理学生成绩复核申请',
      },
      {
        key: 'teacher-absences',
        label: '缺考名单',
        path: '/teacher/absences',
        description: '查看缺考学生名单并安排补考',
      },
      {
        key: 'teacher-makeups',
        label: '补考管理',
        path: '/teacher/makeups',
        description: '补考场次创建与最终成绩查询',
      },
      {
        key: 'teacher-tags',
        label: '标签管理',
        path: '/teacher/tags',
        description: '知识点与题目分类标签维护',
      }
    );
  }

  if (canAccess(role, '/admin/')) {
    items.push({
      key: 'admin-invite-codes',
      label: '邀请码管理',
      path: '/admin/invite-codes',
      description: '生成、作废教师注册邀请码',
    });
  }

  // 通用功能入口
  items.push({
    key: 'change-password',
    label: '修改密码',
    path: '/change-password',
    description: '修改当前账号登录密码',
  });

  return items;
});

const onEntryClick = (path: string) => {
  if (router) {
    void router.push(path);
  }
};
</script>

<style scoped>
.mt-4 {
  margin-top: 1rem;
}
.welcome-card {
  background: #fafafa;
}
.welcome-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.welcome-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}
.welcome-roles {
  display: flex;
  gap: 4px;
}
.welcome-subtitle {
  margin: 8px 0 0;
  color: #666;
  font-size: 14px;
}
.quick-entries-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
}
.entry-card {
  cursor: pointer;
  transition: all 0.2s;
  border-radius: 6px;
}
.entry-card:hover {
  border-color: #1677ff;
}
.entry-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.entry-title {
  font-weight: 600;
  font-size: 15px;
  color: #1f1f1f;
}
.entry-arrow {
  color: #8c8c8c;
  font-size: 14px;
}
.entry-desc {
  margin: 6px 0 0;
  color: #8c8c8c;
  font-size: 13px;
  line-height: 1.5;
}
.permissions {
  word-break: break-all;
}
</style>
