<script setup lang="ts">
import {
  message,
  Form,
  FormItem,
  Input,
  Select,
  DatePicker,
  Switch,
  Card,
  Button,
} from 'ant-design-vue';
import { useRouter } from 'vue-router';
import { ref } from 'vue';
import dayjs, { Dayjs } from 'dayjs';
import { papersApi, examsApi, classesApi } from '@/api';
import type { PaperResponse, ClassResponse } from '@/api';

/**
 * 考试创建页面
 * 功能：创建考试表单（试卷 + 班级 + 时间窗 + 个人时长 + 迟到允许 + 防作弊配置）
 *
 * 后端契约：
 * - POST /api/exams: 创建考试
 * - 入参：ExamCreateRequest（title, description, paperId, courseId, classId, startTime, endTime, durationMinutes, allowLateMinutes, antiCheatConfig）
 */

const router = useRouter();

// 表单数据
const formRef = ref<FormInstance>();
const loading = ref(false);
const submitted = ref(false);

// 下拉选项
const paperList = ref<PaperResponse[]>([]);
const classList = ref<ClassResponse[]>([]);
const papersLoading = ref(false);
const classesLoading = ref(false);

// 防作弊配置开关
const enableSwitchScreen = ref(true);
const enableForbidCopy = ref(false);

// 加载试卷列表
async function loadPapers() {
  papersLoading.value = true;
  try {
    const response = await papersApi.papersPageGet({ page: 1, size: 100 });
    if (response.code === 200 && response.data?.list) {
      paperList.value = response.data.list;
    }
  } catch (error) {
    console.error('加载试卷列表失败:', error);
    message.error('加载试卷列表失败');
  } finally {
    papersLoading.value = false;
  }
}

// 加载班级列表
async function loadClasses() {
  classesLoading.value = true;
  try {
    const response = await classesApi.classesPageGet({ page: 1, size: 100 });
    if (response.code === 200 && response.data?.list) {
      classList.value = response.data.list;
    }
  } catch (error) {
    console.error('加载班级列表失败:', error);
    message.error('加载班级列表失败');
  } finally {
    classesLoading.value = false;
  }
}

// 表单验证
async function validateForm() {
  try {
    await formRef.value.validate();

    // 额外业务校验
    if (formValues.value.endTime && formValues.value.startTime) {
      if (dayjs(formValues.value.endTime).isBefore(dayjs(formValues.value.startTime))) {
        message.error('结束时间必须晚于开始时间');
        return false;
      }
    }

    if (formValues.value.durationMinutes && formValues.value.durationMinutes <= 0) {
      message.error('个人时长必须为正数');
      return false;
    }

    return true;
  } catch (error) {
    console.error('表单验证失败:', error);
    return false;
  }
}

// 表单数据
const formValues = ref({
  title: '',
  description: '',
  paperId: undefined as number | undefined,
  courseId: undefined as number | undefined,
  classId: undefined as number | undefined,
  startTime: null as Dayjs | null,
  endTime: null as Dayjs | null,
  durationMinutes: 60,
  allowLateMinutes: 0,
});

// 提交表单
async function handleSubmit() {
  submitted.value = true;

  if (!(await validateForm())) {
    return;
  }

  loading.value = true;

  try {
    const payload = {
      title: formValues.value.title,
      description: formValues.value.description,
      paperId: formValues.value.paperId,
      courseId: formValues.value.courseId,
      classId: formValues.value.classId,
      startTime: formValues.value.startTime?.toISOString(),
      endTime: formValues.value.endTime?.toISOString(),
      durationMinutes: formValues.value.durationMinutes,
      allowLateMinutes: formValues.value.allowLateMinutes,
      antiCheatConfig: {
        switchScreen: enableSwitchScreen.value,
        forbidCopy: enableForbidCopy.value,
      },
    };

    const response = await examsApi.examsPost({ ExamCreateRequest: payload });

    if (response.code === 200) {
      message.success('创建考试成功');
      router.push('/teacher/exams');
    } else {
      message.error(response.message || '创建考试失败');
    }
  } catch (error) {
    console.error('创建考试失败:', error);
    message.error('创建考试失败');
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  loadPapers();
  loadClasses();
});
</script>

<template>
  <div class="p-6 max-w-4xl">
    <Card title="新建考试" class="mb-4">
      <template #extra>
        <Button @click="router.back()">返回</Button>
      </template>

      <Form
        ref="formRef"
        :model="formValues"
        layout="vertical"
        :validate-messages="submitted ? errors : {}"
      >
        <!-- 基本信息 -->
        <FormItem
          label="考试标题"
          prop="title"
          :rules="{ required: true, message: '考试标题不能为空', trigger: 'blur' }"
        >
          <Input
            v-model:value="formValues.title"
            placeholder="请输入考试标题"
            maxlength="128"
            show-count
            allow-clear
          />
        </FormItem>

        <FormItem label="考试描述" prop="description">
          <Input.TextArea
            v-model:value="formValues.description"
            placeholder="请输入考试描述（可选）"
            :rows="3"
            maxlength="512"
            show-count
            allow-clear
          />
        </FormItem>

        <!-- 绑定信息 -->
        <FormItem
          label="绑定试卷"
          prop="paperId"
          :rules="{ required: true, message: '请选择试卷', trigger: 'change' }"
        >
          <Select
            v-model:value="formValues.paperId"
            placeholder="请选择试卷"
            allow-clear
            :loading="papersLoading"
            style="width: 100%"
            @change="loadPapers"
          >
            <SelectOption v-for="paper in paperList" :key="paper.id" :value="paper.id">
              {{ paper.title }}
            </SelectOption>
          </Select>
        </FormItem>

        <FormItem label="班级（可选）" prop="classId">
          <Select
            v-model:value="formValues.classId"
            placeholder="请选择班级（可选）"
            allow-clear
            :loading="classesLoading"
            style="width: 100%"
          >
            <SelectOption v-for="clazz in classList" :key="clazz.id" :value="clazz.id">
              {{ clazz.name }}
            </SelectOption>
          </Select>
        </FormItem>

        <!-- 时间设置 -->
        <div class="grid grid-cols-2 gap-4">
          <FormItem
            label="开始时间"
            prop="startTime"
            :rules="{ required: true, message: '请选择开始时间', trigger: 'change' }"
          >
            <DatePicker
              v-model:value="formValues.startTime"
              placeholder="选择开始时间"
              show-time
              format="YYYY-MM-DD HH:mm:ss"
              style="width: 100%"
            />
          </FormItem>

          <FormItem
            label="结束时间"
            prop="endTime"
            :rules="{ required: true, message: '请选择结束时间', trigger: 'change' }"
          >
            <DatePicker
              v-model:value="formValues.endTime"
              placeholder="选择结束时间"
              show-time
              format="YYYY-MM-DD HH:mm:ss"
              style="width: 100%"
            />
          </FormItem>
        </div>

        <!-- 时长设置 -->
        <div class="grid grid-cols-2 gap-4">
          <FormItem
            label="个人答题时长（分钟）"
            prop="durationMinutes"
            :rules="{ required: true, message: '请输入个人时长', trigger: 'blur' }"
          >
            <InputNumber
              v-model:value="formValues.durationMinutes"
              placeholder="60"
              :min="1"
              style="width: 100%"
            />
          </FormItem>

          <FormItem label="允许迟到分钟数" prop="allowLateMinutes">
            <InputNumber
              v-model:value="formValues.allowLateMinutes"
              placeholder="0"
              :min="0"
              style="width: 100%"
            />
          </FormItem>
        </div>

        <!-- 防作弊配置 -->
        <Divider>防作弊配置</Divider>

        <FormItem label="启用切屏检测">
          <Switch v-model:checked="enableSwitchScreen" />
          <span class="ml-2 text-gray-500 text-sm">检测学生切换浏览器标签或窗口</span>
        </FormItem>

        <FormItem label="禁用复制粘贴">
          <Switch v-model:checked="enableForbidCopy" />
          <span class="ml-2 text-gray-500 text-sm">禁止学生复制题目或答案内容</span>
        </FormItem>

        <!-- 操作按钮 -->
        <div class="flex justify-end gap-4 mt-6">
          <Button @click="router.back()">取消</Button>
          <Button type="primary" @click="handleSubmit" :loading="loading">创建考试</Button>
        </div>
      </Form>
    </Card>
  </div>
</template>

<style scoped>
.p-6 {
  padding: 1.5rem;
}

.mb-4 {
  margin-bottom: 1rem;
}

.max-w-4xl {
  max-width: 56rem;
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

.flex {
  display: flex;
}

.justify-end {
  justify-content: flex-end;
}

.gap-4 {
  gap: 1rem;
}

.ml-2 {
  margin-left: 0.5rem;
}

.text-gray-500 {
  color: #6b7280;
}

.text-sm {
  font-size: 0.875rem;
}
</style>
