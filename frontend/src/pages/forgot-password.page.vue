<template>
  <div class="auth-page">
    <Card class="auth-card" :bordered="false">
      <h2 class="auth-title">找回密码</h2>
      <p class="auth-subtitle">向注册邮箱发送 6 位验证码（5 分钟内有效）</p>

      <Form
        :model="form"
        :rules="rules"
        layout="vertical"
        :hide-required-mark="true"
        @finish="onSubmit"
      >
        <FormItem name="email" label="注册邮箱">
          <div class="email-row">
            <Input v-model:value="form.email" placeholder="name@example.com" autocomplete="email" />
            <Button :disabled="countdown > 0" :loading="sending" @click="onSendCode">
              {{ countdown > 0 ? `${countdown}s` : '发送验证码' }}
            </Button>
          </div>
        </FormItem>
        <FormItem name="code" label="验证码">
          <Input v-model:value="form.code" placeholder="6 位验证码" :maxlength="6" />
        </FormItem>
        <FormItem name="newPassword" label="新密码">
          <InputPassword
            v-model:value="form.newPassword"
            placeholder="至少 8 位"
            autocomplete="new-password"
          />
        </FormItem>

        <Button type="primary" html-type="submit" block :loading="loading">重置密码</Button>
      </Form>

      <Alert
        class="mt-3"
        type="info"
        show-icon
        message="开发环境未配置 SMTP 时，验证码只写后端日志，不会真的发到邮箱。"
      />
      <div class="auth-links">
        <RouterLink to="/login">返回登录</RouterLink>
        <RouterLink to="/register">注册账号</RouterLink>
      </div>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { Alert, Button, Card, Form, FormItem, Input, InputPassword, message } from 'ant-design-vue';
// 校验规则类型只在 `es/form` 子路径导出，顶层没有（沿用参考项目写法）
import type { Rule } from 'ant-design-vue/es/form';
import { onUnmounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { resetPassword as resetPasswordContract, sendResetCode } from '@/api/axios';
import { client } from '@/api/apiClient';
import { ApiError } from '@/api/types';

const router = useRouter();

const form = reactive({ email: '', code: '', newPassword: '' });
const loading = ref(false);
const sending = ref(false);
const countdown = ref(0);
let timer: ReturnType<typeof setInterval> | undefined;

const rules: Record<string, Rule[]> = {
  email: [
    { required: true, message: '请输入注册邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码至少 8 位、最多 64 位', trigger: 'blur' },
  ],
};

const startCountdown = () => {
  countdown.value = 60;
  timer = setInterval(() => {
    countdown.value -= 1;
    if (countdown.value <= 0 && timer) {
      clearInterval(timer);
      timer = undefined;
    }
  }, 1000);
};

const onSendCode = async () => {
  if (!form.email) {
    message.warning('请先填写注册邮箱');
    return;
  }
  sending.value = true;
  try {
    await sendResetCode({ client, throwOnError: true, body: { email: form.email } });
    // 后端对未注册邮箱也返回成功（防枚举），所以提示语不承诺「已发送到你邮箱」。
    message.success('如该邮箱已注册，验证码已发送；未配置 SMTP 时请查看后端日志');
    startCountdown();
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '验证码发送失败');
  } finally {
    sending.value = false;
  }
};

const onSubmit = async () => {
  loading.value = true;
  try {
    await resetPasswordContract({
      client,
      throwOnError: true,
      body: { email: form.email, code: form.code, newPassword: form.newPassword },
    });
    message.success('密码已重置，请用新密码登录');
    await router.replace('/login');
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '密码重置失败');
  } finally {
    loading.value = false;
  }
};

onUnmounted(() => {
  if (timer) clearInterval(timer);
});
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
  width: 420px;
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
.email-row {
  display: flex;
  gap: 8px;
}
.auth-links {
  margin-top: 16px;
  display: flex;
  justify-content: space-between;
  font-size: 13px;
}
</style>
