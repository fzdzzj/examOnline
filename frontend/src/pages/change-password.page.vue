<template>
  <div class="auth-page">
    <Card class="auth-card" :bordered="false">
      <h2 class="auth-title">修改密码</h2>
      <p class="auth-subtitle">{{ displayName }}</p>

      <Form
        :model="form"
        :rules="rules"
        layout="vertical"
        :hide-required-mark="true"
        @finish="onSubmit"
      >
        <FormItem name="oldPassword" label="原密码">
          <InputPassword v-model:value="form.oldPassword" autocomplete="current-password" />
        </FormItem>
        <FormItem name="newPassword" label="新密码">
          <InputPassword
            v-model:value="form.newPassword"
            placeholder="至少 8 位"
            autocomplete="new-password"
          />
        </FormItem>
        <FormItem name="confirmPassword" label="确认新密码">
          <InputPassword v-model:value="form.confirmPassword" autocomplete="new-password" />
        </FormItem>

        <Button type="primary" html-type="submit" block :loading="loading">提交</Button>
      </Form>

      <Alert
        class="mt-3"
        type="warning"
        show-icon
        message="改密成功后所有已登录端会被强制下线（后端轮换 sessionVersion），需用新密码重新登录。"
      />
      <div class="auth-links">
        <RouterLink to="/">返回首页</RouterLink>
      </div>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { Alert, Button, Card, Form, FormItem, InputPassword, message } from 'ant-design-vue';
// 校验规则类型只在 `es/form` 子路径导出，顶层没有（沿用参考项目写法）
import type { Rule } from 'ant-design-vue/es/form';
import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useStore } from 'vuex';

import { changePassword } from '@/api/axios';
import { client } from '@/api/apiClient';
import { ApiError } from '@/api/types';
import { clearTokens } from '@/utils/token';
import type { State } from '@/store';

const router = useRouter();
const store = useStore<State>();

const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' });
const loading = ref(false);

const displayName = computed(() => store.state.user?.name || store.state.user?.username || '');

const rules: Record<string, Rule[]> = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码至少 8 位、最多 64 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string) =>
        value === form.newPassword
          ? Promise.resolve()
          : Promise.reject(new Error('两次输入的密码不一致')),
      trigger: 'blur',
    },
  ],
};

const onSubmit = async () => {
  loading.value = true;
  try {
    await changePassword({
      client,
      throwOnError: true,
      body: { oldPassword: form.oldPassword, newPassword: form.newPassword },
    });
    message.success('密码已修改，请重新登录');
    // 后端此刻已把 access token 拉黑并全端下线：不能再走 signOut（那会再发一次注定 401 的 logout），
    // 只清本地即可。
    clearTokens();
    store.commit('reset');
    await router.replace('/login');
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '密码修改失败');
  } finally {
    loading.value = false;
  }
};
</script>

<style scoped>
.auth-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 24px;
}
.auth-card {
  width: 400px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08);
}
.auth-title {
  margin: 0;
  text-align: center;
  color: #407fff;
}
.auth-subtitle {
  margin: 4px 0 20px;
  text-align: center;
  color: #999;
  font-size: 13px;
}
.auth-links {
  margin-top: 16px;
  text-align: center;
  font-size: 13px;
}
</style>
