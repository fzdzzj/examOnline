<template>
  <div class="max-w-3xl">
    <Card title="新建考试" class="mb-4">
      <template #extra>
        <Button @click="router.back()">返回</Button>
      </template>

      <Form layout="vertical">
        <FormItem label="考试标题" required>
          <Input
            v-model:value="form.title"
            :maxlength="128"
            show-count
            placeholder="请输入考试标题"
            allow-clear
          />
        </FormItem>

        <FormItem label="考试描述">
          <Input.TextArea
            v-model:value="form.description"
            :rows="3"
            :maxlength="512"
            show-count
            placeholder="请输入考试描述（可选）"
          />
        </FormItem>

        <FormItem label="绑定试卷" required>
          <Select
            v-model:value="form.paperId"
            :options="paperOptions"
            :loading="papersFetching"
            placeholder="请选择试卷（发布考试时将生成快照并锁定）"
            allow-clear
          />
        </FormItem>

        <FormItem label="班级（可选）">
          <Select
            v-model:value="form.classId"
            :options="classOptions"
            :loading="classesFetching"
            placeholder="请选择参考班级（可选）"
            allow-clear
          />
        </FormItem>

        <div class="grid grid-cols-2 gap-4">
          <FormItem label="开始时间" required>
            <DatePicker
              v-model:value="form.startTime"
              show-time
              value-format="YYYY-MM-DDTHH:mm:ss"
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="选择开始时间"
              style="width: 100%"
            />
          </FormItem>
          <FormItem label="结束时间" required>
            <DatePicker
              v-model:value="form.endTime"
              show-time
              value-format="YYYY-MM-DDTHH:mm:ss"
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="选择结束时间"
              style="width: 100%"
            />
          </FormItem>
        </div>

        <div class="grid grid-cols-2 gap-4">
          <FormItem label="个人答题时长（分钟）" required>
            <InputNumber v-model:value="form.durationMinutes" :min="1" style="width: 100%" />
          </FormItem>
          <FormItem label="允许迟到分钟数">
            <InputNumber v-model:value="form.allowLateMinutes" :min="0" style="width: 100%" />
          </FormItem>
        </div>

        <Divider>防作弊配置</Divider>

        <FormItem label="启用切屏检测">
          <Space>
            <Switch v-model:checked="enableSwitchScreen" />
            <span class="text-sm text-gray-500">检测学生切换浏览器标签或窗口</span>
          </Space>
        </FormItem>

        <FormItem label="禁用复制粘贴">
          <Space>
            <Switch v-model:checked="enableForbidCopy" />
            <span class="text-sm text-gray-500">禁止学生复制题目或答案内容</span>
          </Space>
        </FormItem>

        <div class="mt-4 flex justify-end gap-3">
          <Button @click="router.back()">取消</Button>
          <Button type="primary" :loading="submitting" @click="handleSubmit">创建考试</Button>
        </div>
      </Form>
    </Card>
  </div>
</template>

<script setup lang="ts">
import {
  Button,
  Card,
  DatePicker,
  Divider,
  Form,
  FormItem,
  Input,
  InputNumber,
  Select,
  Space,
  Switch,
  message,
} from 'ant-design-vue';
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import {
  create3 as createExam,
  page1 as pagePapers,
  page3 as pageClasses,
  type ClassPageResponse,
  type PaperResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';

/**
 * 考试创建页面（阶段 21 考务，修复版）。
 * 后端契约：POST /api/exams（ExamCreateRequest）；试卷 / 班级下拉取自分页接口。
 * 时间用字符串（value-format）直接对齐后端 LocalDateTime 反序列化，避免时区换算；
 * 但 value-format 必须带字面量 T（YYYY-MM-DDTHH:mm:ss）——空格分隔会被后端判
 * 400「请求体格式错误」（实测；`spring.jackson.date-format` 只作用于 java.util.Date）。
 * 展示用的 format 保持人读的 YYYY-MM-DD HH:mm:ss。
 */

const router = useRouter();

const form = ref({
  title: '',
  description: '',
  paperId: undefined as number | undefined,
  classId: undefined as number | undefined,
  startTime: undefined as string | undefined,
  endTime: undefined as string | undefined,
  durationMinutes: 60,
  allowLateMinutes: 0,
});

const enableSwitchScreen = ref(true);
const enableForbidCopy = ref(false);

const { data: papersData, isFetching: papersFetching } = useQuery({
  queryKey: ['papers', 'for-exam-create'] as const,
  queryFn: () =>
    unwrap<PaperResponse[]>(
      pagePapers({ client, throwOnError: true, query: { page: 1, size: 100 } })
    ),
});

const paperOptions = computed(() =>
  (papersData.value ?? []).map((p) => ({
    value: p.id as number,
    label: p.title ?? `试卷 #${p.id}`,
  }))
);

const { data: classesData, isFetching: classesFetching } = useQuery({
  queryKey: ['classes', 'for-exam-create'] as const,
  queryFn: () =>
    unwrap<ClassPageResponse>(
      pageClasses({ client, throwOnError: true, query: { page: 1, size: 100 } })
    ),
});

const classOptions = computed(() =>
  (classesData.value?.list ?? []).map((c) => ({
    value: c.id as number,
    label: c.name ?? `班级 #${c.id}`,
  }))
);

const submitting = ref(false);

function validate(): boolean {
  if (!form.value.title.trim()) {
    message.warning('请输入考试标题');
    return false;
  }
  if (form.value.paperId === undefined) {
    message.warning('请选择绑定试卷');
    return false;
  }
  if (!form.value.startTime || !form.value.endTime) {
    message.warning('请选择开始与结束时间');
    return false;
  }
  if (form.value.endTime <= form.value.startTime) {
    message.warning('结束时间必须晚于开始时间');
    return false;
  }
  if (!form.value.durationMinutes || form.value.durationMinutes <= 0) {
    message.warning('个人答题时长必须为正整数');
    return false;
  }
  return true;
}

async function handleSubmit(): Promise<void> {
  if (!validate()) return;
  submitting.value = true;
  try {
    await unwrap(
      createExam({
        client,
        throwOnError: true,
        body: {
          title: form.value.title.trim(),
          ...(form.value.description.trim() ? { description: form.value.description.trim() } : {}),
          paperId: form.value.paperId as number,
          ...(form.value.classId !== undefined ? { classId: form.value.classId } : {}),
          startTime: form.value.startTime as string,
          endTime: form.value.endTime as string,
          durationMinutes: form.value.durationMinutes,
          allowLateMinutes: form.value.allowLateMinutes ?? 0,
          antiCheatConfig: {
            switchScreen: enableSwitchScreen.value,
            forbidCopy: enableForbidCopy.value,
          },
        },
      })
    );
    message.success('考试已创建（未发布状态）');
    router.push('/teacher/exams');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '创建考试失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.max-w-3xl {
  max-width: 48rem;
}
.mb-4 {
  margin-bottom: 1rem;
}
.grid {
  display: grid;
}
.grid-cols-2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.gap-4 {
  gap: 1rem;
}
.mt-4 {
  margin-top: 1rem;
}
.flex {
  display: flex;
}
.justify-end {
  justify-content: flex-end;
}
.gap-3 {
  gap: 0.75rem;
}
.text-sm {
  font-size: 0.875rem;
}
.text-gray-500 {
  color: #6b7280;
}
</style>
