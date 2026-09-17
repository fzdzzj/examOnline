<template>
  <div>
    <Card title="当前身份" :bordered="false">
      <Descriptions :column="1" bordered size="middle">
        <DescriptionsItem label="用户 ID">{{ user?.id ?? '-' }}</DescriptionsItem>
        <DescriptionsItem label="账号">{{ user?.username ?? '-' }}</DescriptionsItem>
        <DescriptionsItem label="姓名">{{ user?.name ?? '-' }}</DescriptionsItem>
        <DescriptionsItem label="邮箱">
          {{ user?.email || '未绑定（无法使用找回密码）' }}
        </DescriptionsItem>
        <DescriptionsItem label="角色">
          <Tag v-for="role in roles" :key="role" color="blue">{{ role }}</Tag>
          <span v-if="roles.length === 0">-</span>
        </DescriptionsItem>
        <DescriptionsItem label="权限点">
          <span class="permissions">{{ permissions.join('、') || '-' }}</span>
        </DescriptionsItem>
      </Descriptions>
    </Card>

    <Card class="mt-4" title="这一页验证了什么" :bordered="false">
      <ol class="checklist">
        <li>
          登录 → 后端签发双 Token → 前端用 Access 调
          <code>/api/auth/me</code>
          → 本页渲染出真实身份。
        </li>
        <li>
          侧边菜单按后端返回的角色渲染，越权路径会被守卫送到
          <code>/403</code>
          。
        </li>
        <li>直接刷新本页仍能进，说明续期链路（401 → 单飞 Refresh → 重放）工作正常。</li>
      </ol>
      <p class="note">
        业务模块（题库 / 考试 / 判分 / 统计）在阶段 20–23 落地；
        <code>@tanstack/vue-query</code>
        已装配， 届时列表与详情接口用它做缓存。
      </p>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { Card, Descriptions, DescriptionsItem, Tag } from 'ant-design-vue';
import { computed } from 'vue';
import { useStore } from 'vuex';

import type { State } from '@/store';

const store = useStore<State>();

const user = computed(() => store.state.user);
const roles = computed(() => user.value?.roles ?? []);
const permissions = computed(() => user.value?.permissions ?? []);
</script>

<style scoped>
.checklist {
  margin: 0;
  padding-left: 20px;
  color: #555;
  line-height: 2;
}
.note {
  margin: 12px 0 0;
  color: #999;
  font-size: 13px;
}
.permissions {
  word-break: break-all;
}
</style>
