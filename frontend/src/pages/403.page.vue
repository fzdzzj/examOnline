<template>
  <div class="state-page">
    <Result status="403" title="403" sub-title="当前账号没有访问该页面的权限">
      <template #extra>
        <Button type="primary" @click="goHome">返回首页</Button>
      </template>
    </Result>
  </div>
</template>

<script setup lang="ts">
import { Button, Result } from 'ant-design-vue';
import { useRouter } from 'vue-router';

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
