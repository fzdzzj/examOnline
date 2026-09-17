<template>
  <div class="auth-page">
    <Card class="auth-card" :bordered="false">
      <h2 class="auth-title">在线考试系统</h2>
      <p class="auth-subtitle">请使用学号 / 工号登录</p>

      <Form
        :model="form"
        :rules="rules"
        layout="vertical"
        :hide-required-mark="true"
        @finish="onSubmit"
      >
        <FormItem name="username" label="账号">
          <Input
            v-model:value="form.username"
            size="large"
            placeholder="学号 / 工号"
            autocomplete="username"
          />
        </FormItem>
        <FormItem name="password" label="密码">
          <InputPassword
            v-model:value="form.password"
            size="large"
            placeholder="登录密码"
            autocomplete="current-password"
          />
        </FormItem>
        <Button type="primary" html-type="submit" size="large" block :loading="loading">
          登录
        </Button>
      </Form>

      <div class="auth-links">
        <RouterLink to="/register">注册账号</RouterLink>
        <RouterLink to="/forgot-password">忘记密码</RouterLink>
      </div>
      <Alert v-if="lockedHint" class="mt-3" type="warning" show-icon :message="lockedHint" />
    </Card>
  </div>
</template>

<script setup lang="ts">
import { Alert, Button, Card, Form, FormItem, Input, InputPassword, message } from 'ant-design-vue';
// 校验规则类型只在 `es/form` 子路径导出，顶层没有（沿用参考项目写法）
import type { Rule } from 'ant-design-vue/es/form';
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useStore } from 'vuex';

import { BizCode } from '@/api/errorMap';
import { ApiError } from '@/api/types';
import type { State } from '@/store';

const router = useRouter();
const route = useRoute();
const store = useStore<State>();

const form = reactive({ username: '', password: '' });
const loading = ref(false);
const lockedHint = ref('');

const rules: Record<string, Rule[]> = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
};

// 表单校验通过后才会走到这里（antd 的 finish 事件在校验成功后触发）；
// 错误提示统一用 ApiError.message（已由 errorMap 解析过）。
const onSubmit = async () => {
  loading.value = true;
  lockedHint.value = '';
  try {
    await store.dispatch('signIn', { username: form.username, password: form.password });
    message.success('登录成功');
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/';
    await router.replace(redirect);
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === BizCode.ACCOUNT_LOCKED) lockedHint.value = error.message;
      else if (error.code === BizCode.TOO_MANY_REQUESTS) lockedHint.value = error.message;
      else message.error(error.message);
    } else {
      message.error(error instanceof Error ? error.message : '登录失败');
    }
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
  height: 100%;
  padding: 24px;
}
.auth-card {
  width: 380px;
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
  display: flex;
  justify-content: space-between;
  font-size: 13px;
}
</style>
