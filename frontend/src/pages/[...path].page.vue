<template>
  <div class="state-page">
    <Result status="404" title="404" sub-title="页面不存在或链接已失效">
      <template #extra>
        <Button type="primary" @click="goHome">返回首页</Button>
      </template>
    </Result>
  </div>
</template>

<script setup lang="ts">
import { Button, Result } from 'ant-design-vue';
import { useRouter } from 'vue-router';

// meta.notFound 供守卫识别：404 页不渲染任何业务数据，因此不参与登录/角色判定，
// 否则未登录用户点错链接会被甩到登录页（见 router/access.ts#decideNavigation）。
definePage({
  meta: {
    notFound: true,
  },
});

const router = useRouter();

const goHome = () => {
  void router.replace('/');
};
</script>

<style scoped>
.state-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}
</style>
