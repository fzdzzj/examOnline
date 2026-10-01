<template>
  <div class="auth-page">
    <Card class="auth-card" :bordered="false">
      <h2 class="auth-title">注册账号</h2>
      <p class="auth-subtitle">学生以学号注册；教师以工号 + 邀请码注册</p>

      <Form
        :model="form"
        :rules="rules"
        layout="vertical"
        :hide-required-mark="true"
        @finish="onSubmit"
      >
        <FormItem name="roleType" label="身份">
          <RadioGroup v-model:value="form.roleType">
            <Radio value="STUDENT">学生</Radio>
            <Radio value="TEACHER">教师</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem name="username" label="账号">
          <Input
            v-model:value="form.username"
            :placeholder="form.roleType === 'TEACHER' ? '工号' : '学号'"
            autocomplete="username"
          />
        </FormItem>
        <FormItem name="name" label="姓名">
          <Input v-model:value="form.name" placeholder="真实姓名" autocomplete="name" />
        </FormItem>
        <FormItem name="email" label="邮箱（选填，用于找回密码）">
          <Input v-model:value="form.email" placeholder="name@example.com" autocomplete="email" />
        </FormItem>
        <FormItem v-if="form.roleType === 'TEACHER'" name="inviteCode" label="邀请码">
          <Input v-model:value="form.inviteCode" placeholder="由管理员发放的一次性邀请码" />
        </FormItem>
        <FormItem name="password" label="密码">
          <InputPassword
            v-model:value="form.password"
            placeholder="至少 8 位"
            autocomplete="new-password"
          />
        </FormItem>
        <FormItem name="confirmPassword" label="确认密码">
          <InputPassword
            v-model:value="form.confirmPassword"
            placeholder="再次输入密码"
            autocomplete="new-password"
          />
        </FormItem>

        <Button type="primary" html-type="submit" block :loading="loading">注册</Button>
      </Form>

      <div class="auth-links">
        <span />
        <RouterLink to="/login">已有账号，去登录</RouterLink>
      </div>
    </Card>
  </div>
</template>

<script setup lang="ts">
import {
  Button,
  Card,
  Form,
  FormItem,
  Input,
  InputPassword,
  Radio,
  RadioGroup,
  message,
} from 'ant-design-vue';
// 校验规则类型只在 `es/form` 子路径导出，顶层没有（沿用参考项目写法）
import type { Rule } from 'ant-design-vue/es/form';
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { register as registerContract } from '@/api/axios';
import { client } from '@/api/apiClient';
import { ApiError } from '@/api/types';

const router = useRouter();

const form = reactive({
  roleType: 'STUDENT' as 'STUDENT' | 'TEACHER',
  username: '',
  name: '',
  email: '',
  inviteCode: '',
  password: '',
  confirmPassword: '',
});
const loading = ref(false);

const rules: Record<string, Rule[]> = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_-]+$/,
      message: '账号仅允许字母、数字、下划线与连字符',
      trigger: 'blur',
    },
  ],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  inviteCode: [
    {
      validator: (_rule: unknown, value: string) =>
        form.roleType !== 'TEACHER' || (value && value.trim().length > 0)
          ? Promise.resolve()
          : Promise.reject(new Error('教师注册需要邀请码')),
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码至少 8 位、最多 64 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string) =>
        value === form.password
          ? Promise.resolve()
          : Promise.reject(new Error('两次输入的密码不一致')),
      trigger: 'blur',
    },
  ],
};

const onSubmit = async () => {
  loading.value = true;
  try {
    await registerContract({
      client,
      throwOnError: true,
      body: {
        username: form.username,
        password: form.password,
        name: form.name,
        email: form.email || undefined,
        roleType: form.roleType,
        inviteCode: form.roleType === 'TEACHER' ? form.inviteCode : undefined,
      },
    });
    message.success('注册成功，请登录');
    await router.replace('/login');
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '注册失败');
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
.auth-links {
  margin-top: 12px;
  display: flex;
  justify-content: space-between;
  font-size: 13px;
}
</style>
