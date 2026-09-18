<template>
  <Card title="新建试卷" class="max-w-xl">
    <Alert v-if="errors.length" type="error" show-icon class="mb-4">
      <template #message>
        <ul class="m-0 list-disc pl-5">
          <li v-for="item in errors" :key="item">{{ item }}</li>
        </ul>
      </template>
    </Alert>

    <Form layout="vertical">
      <FormItem label="试卷标题" required>
        <Input v-model:value="draft.title" :maxlength="128" placeholder="最长 128 字" />
      </FormItem>
      <FormItem label="试卷描述">
        <Textarea
          v-model:value="draft.description"
          :rows="3"
          :maxlength="512"
          placeholder="选填，最长 512 字"
        />
      </FormItem>
      <FormItem label="申报总分" required>
        <InputNumber
          v-model:value="draft.totalScore"
          :min="0"
          :max="9999.9"
          :step="0.5"
          class="w-48"
        />
        <p class="mb-0 mt-1 text-gray-400">
          先申报后加题；加题后若改总分，后端要求「各题分值之和 = 总分」。
        </p>
      </FormItem>
      <div class="flex justify-end gap-2">
        <Button @click="onCancel">取消</Button>
        <Button type="primary" :loading="saving" @click="onSubmit">创建并进入组卷</Button>
      </div>
    </Form>
  </Card>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  Form,
  FormItem,
  Input,
  InputNumber,
  Textarea,
  message,
} from 'ant-design-vue';
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { create2 } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';

const router = useRouter();

const draft = reactive<{ title: string; description: string; totalScore: number | undefined }>({
  title: '',
  description: '',
  totalScore: undefined,
});

const errors = ref<string[]>([]);
const saving = ref(false);

function validate(): string[] {
  const found: string[] = [];
  if (!draft.title.trim()) {
    found.push('试卷标题不能为空');
  }
  if (
    draft.totalScore === undefined ||
    !Number.isFinite(draft.totalScore) ||
    draft.totalScore < 0
  ) {
    found.push('申报总分不能为空且不能为负');
  }
  return found;
}

async function onSubmit(): Promise<void> {
  errors.value = validate();
  if (errors.value.length > 0) {
    return;
  }
  saving.value = true;
  try {
    const paper = await unwrap(
      create2({
        client,
        throwOnError: true,
        body: {
          title: draft.title.trim(),
          description: draft.description.trim() || undefined,
          totalScore: draft.totalScore as number,
        },
      })
    );
    message.success('试卷已创建，可开始组卷');
    if (paper?.id !== undefined) {
      await router.replace(`/teacher/papers/${paper.id}`);
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '创建失败，请稍后重试');
  } finally {
    saving.value = false;
  }
}

function onCancel(): void {
  void router.push('/teacher/papers');
}
</script>
