<template>
  <Modal
    :open="open"
    :title="isEdit ? '编辑题目' : '新建题目'"
    width="720px"
    :footer="null"
    :mask-closable="false"
    @cancel="onCancel"
  >
    <Alert v-if="errors.length" type="error" show-icon class="mb-4">
      <template #message>
        <ul class="m-0 list-disc pl-5">
          <li v-for="item in errors" :key="item">{{ item }}</li>
        </ul>
      </template>
    </Alert>

    <Form layout="vertical">
      <FormItem label="题型" required>
        <RadioGroup v-model:value="draft.type" :options="QUESTION_TYPE_OPTIONS" />
      </FormItem>

      <FormItem label="题干" required>
        <Textarea
          v-model:value="draft.content"
          :rows="3"
          :maxlength="5000"
          placeholder="请输入题干"
        />
      </FormItem>

      <template v-if="config.showChoices">
        <FormItem required>
          <template #label>
            选项（勾选正确项，{{
              config.correctMode === 'single' ? '只能勾一项' : `至少 ${config.minCorrect} 项`
            }}）
          </template>
          <div
            v-for="(choice, index) in draft.choices"
            :key="index"
            class="mb-2 flex items-center gap-2"
          >
            <Checkbox
              :checked="choice.isCorrect"
              :aria-label="`勾选 ${letterOfIndex(index)} 为正确项`"
              @update:checked="(checked: boolean) => onCorrectToggle(index, checked)"
            />
            <span class="w-5 text-center font-medium">{{ letterOfIndex(index) }}</span>
            <Input
              v-model:value="choice.text"
              :placeholder="`选项 ${letterOfIndex(index)} 内容`"
              class="flex-1"
            />
            <Button
              size="small"
              danger
              :disabled="draft.choices.length <= config.minChoices"
              @click="removeChoice(index)"
            >
              删除
            </Button>
          </div>
          <Button
            size="small"
            :disabled="draft.choices.length >= config.maxChoices"
            @click="addChoice"
          >
            + 添加选项（{{ draft.choices.length }}/{{ config.maxChoices }}）
          </Button>
        </FormItem>

        <FormItem label="正确答案" required>
          <span class="text-gray-500">
            以上方勾选的选项字母为准（提交格式：{{
              config.correctMode === 'single' ? '单个字母，如 A' : '升序字母列表，如 A,B'
            }}）
          </span>
        </FormItem>
      </template>

      <FormItem v-if="config.answerKind === 'judge'" label="正确答案" required>
        <!-- 硬约定 4：提交值就是后端约定的 T/F 原始格式；「正确/对/A/1」等别名归一在后端 -->
        <RadioGroup v-model:value="draft.judgeAnswer" :options="JUDGE_ANSWER_OPTIONS" />
      </FormItem>

      <FormItem v-if="config.answerKind === 'text'" label="参考答案" required>
        <Textarea
          v-model:value="draft.shortAnswer"
          :rows="3"
          :maxlength="512"
          placeholder="参考答案原文（答案归一化与判分口径由后端处理）"
        />
      </FormItem>

      <FormItem label="默认分值" required>
        <InputNumber v-model:value="draft.score" :min="0.5" :max="999.9" :step="0.5" class="w-40" />
        <span class="ml-2 text-gray-500">0.5–999.9，组卷时可在试卷内覆盖，不影响题库</span>
      </FormItem>

      <FormItem label="难度" required>
        <RadioGroup v-model:value="draft.difficulty" :options="DIFFICULTY_OPTIONS" />
      </FormItem>

      <FormItem label="标签">
        <Select
          v-model:value="draft.tagIds"
          mode="multiple"
          :options="tagOptions"
          placeholder="选择标签（可多选）"
          class="w-full"
        />
      </FormItem>

      <FormItem label="答案解析">
        <Textarea v-model:value="draft.analysis" :rows="2" :maxlength="2000" placeholder="选填" />
      </FormItem>

      <div class="flex justify-end gap-2">
        <Button @click="onCancel">取消</Button>
        <Button type="primary" :loading="saving" @click="onSubmit">保存</Button>
      </div>
    </Form>
  </Modal>
</template>

<script setup lang="ts">
/**
 * 题目编辑表单：新增 / 编辑共用（硬约定 3 的另一半——一份骨架渲染所有题型）。
 * 字段渲染与校验全部来自 questionTypeConfig 的题型配置，本组件不做题型分支。
 */
import {
  Alert,
  Button,
  Checkbox,
  Form,
  FormItem,
  Input,
  InputNumber,
  Modal,
  RadioGroup,
  Select,
  Textarea,
  message,
} from 'ant-design-vue';
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import { create1, list, update, type QuestionResponse, type TagResponse } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { DIFFICULTY_OPTIONS, QUESTION_TYPE_OPTIONS } from '@/utils/questionTypes';
import {
  createEmptyDraft,
  draftFromResponse,
  letterOfIndex,
  QUESTION_TYPE_CONFIGS,
  toCreateRequest,
  validateQuestionDraft,
  type QuestionFormDraft,
} from './questionTypeConfig';

const props = defineProps<{
  open: boolean;
  /** 传 null/undefined 表示新建；否则为编辑（用列表项全量字段回填） */
  question: QuestionResponse | null;
}>();

const emit = defineEmits<{
  (event: 'saved'): void;
  (event: 'update:open', open: boolean): void;
}>();

/** 判断题二选一：展示中文、提交 T/F（后端约定的原始格式，注释见 questionTypeConfig 文件头）。 */
const JUDGE_ANSWER_OPTIONS = [
  { label: '正确（T）', value: 'T' },
  { label: '错误（F）', value: 'F' },
];

const isEdit = computed(() => props.question?.id !== undefined);
/** 渲染骨架跟随「当前草稿题型」：编辑中切换题型即时换字段，这正是配置驱动的意义。 */
const config = computed(() => QUESTION_TYPE_CONFIGS[draft.value.type]);

const draft = ref<QuestionFormDraft>(createEmptyDraft(1));
const errors = ref<string[]>([]);
const saving = ref(false);

watch(
  () => [props.open, props.question] as const,
  ([open]) => {
    if (open) {
      draft.value = props.question ? draftFromResponse(props.question) : createEmptyDraft(1);
      errors.value = [];
    }
  },
  { immediate: true }
);

const { data: tags } = useQuery({
  queryKey: ['tags'],
  queryFn: () => unwrap<TagResponse[]>(list({ client, throwOnError: true })),
});

const tagOptions = computed(() =>
  (tags.value ?? []).map((tag) => ({ value: tag.id as number, label: tag.name ?? String(tag.id) }))
);

/** 切题型时只修正选项区形态，其余字段保留教师已录入内容。 */
watch(
  () => draft.value.type,
  (type) => {
    const nextConfig = QUESTION_TYPE_CONFIGS[type];
    if (nextConfig.showChoices) {
      if (draft.value.choices.length === 0) {
        draft.value.choices = [0, 1].map(() => ({ text: '', isCorrect: false }));
      }
      return;
    }
    draft.value.choices = [];
  }
);

/** 单选模式勾选即排他：新勾选的生效，其余自动取消。 */
function onCorrectToggle(index: number, checked: boolean): void {
  const active = QUESTION_TYPE_CONFIGS[draft.value.type];
  if (active.correctMode === 'single' && checked) {
    draft.value.choices = draft.value.choices.map((choice, i) => ({
      ...choice,
      isCorrect: i === index,
    }));
    return;
  }
  draft.value.choices[index].isCorrect = checked;
}

function addChoice(): void {
  if (draft.value.choices.length >= config.value.maxChoices) return;
  draft.value.choices.push({ text: '', isCorrect: false });
}

function removeChoice(index: number): void {
  if (draft.value.choices.length <= config.value.minChoices) return;
  draft.value.choices.splice(index, 1);
}

function onCancel(): void {
  emit('update:open', false);
}

async function onSubmit(): Promise<void> {
  const found = validateQuestionDraft(draft.value);
  errors.value = found;
  if (found.length > 0) {
    return;
  }
  saving.value = true;
  try {
    const body = toCreateRequest(draft.value);
    if (isEdit.value && props.question?.id !== undefined) {
      await unwrap(update({ client, throwOnError: true, path: { id: props.question.id }, body }));
    } else {
      await unwrap(create1({ client, throwOnError: true, body }));
    }
    message.success(isEdit.value ? '题目已更新' : '题目已创建');
    emit('saved');
    emit('update:open', false);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存失败，请稍后重试');
  } finally {
    saving.value = false;
  }
}
</script>
