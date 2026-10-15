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
          @blur="onStemBlur"
        />
      </FormItem>

      <!-- 题干查重预检（add-question-duplicate-detection，知情防呆不阻断保存） -->
      <Alert
        v-if="duplicateHits.length > 0"
        type="warning"
        show-icon
        :data-test="'duplicate-warning'"
        class="mb-4"
      >
        <template #message>
          <div>
            <div class="flex items-center gap-2">
              <span>
                检测到题库中存在 {{ duplicateHits.length }} 道相似题目（最高相似度
                {{ Math.round(maxSimilarity * 100) }}%）
              </span>
              <Button
                v-if="duplicateHits.length > 0"
                type="link"
                size="small"
                class="px-0"
                data-test="duplicate-toggle-preview"
                @click="duplicateShowPreview = !duplicateShowPreview"
              >
                {{ duplicateShowPreview ? '收起相似题目' : '查看相似题目' }}
              </Button>
            </div>
            <div v-if="duplicateShowPreview" class="mt-2">
              <div
                v-for="item in duplicateHits"
                :key="item.questionId"
                data-test="duplicate-preview-item"
                class="mb-1 rounded border border-amber-200 bg-amber-50 px-2 py-1"
              >
                <div class="flex items-center gap-2">
                  <span class="text-xs">[{{ typeLabelOf(item.type) }}]</span>
                  <span class="truncate flex-1 text-xs text-gray-700">{{ item.content }}</span>
                  <Tag color="warning">{{ Math.round((item.similarity ?? 0) * 100) }}%</Tag>
                </div>
                <div class="text-xs text-gray-400">默认分：{{ item.score ?? '—' }}</div>
              </div>
            </div>
          </div>
        </template>
      </Alert>

      <FormItem label="题干图片">
        <div class="flex items-start gap-3">
          <Upload
            :show-upload-list="false"
            :before-upload="beforeUpload"
            :custom-request="customUpload"
            :disabled="uploading"
            accept=".png,.jpg,.jpeg,.gif,.webp"
          >
            <Button :loading="uploading" :disabled="!!draft.imageUrl">上传图片</Button>
          </Upload>
          <div v-if="draft.imageUrl" class="image-preview">
            <img
              :src="draft.imageUrl"
              alt="题干图片预览"
              data-test="image-preview"
              class="max-h-32 max-w-56 rounded border"
            />
            <Button size="small" danger class="ml-2" @click="removeImage">移除</Button>
          </div>
        </div>
        <div v-if="uploadError" class="upload-error mt-1" data-test="image-upload-error">
          {{ uploadError }}
        </div>
        <div class="mt-1 text-gray-500 text-xs">
          支持 png/jpg/jpeg/gif/webp，单文件 ≤5MB；先上传拿 URL，保存题目时随表单提交
        </div>
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

      <FormItem v-if="config.answerKind === 'fill'" label="可接受答案" required>
        <div
          v-for="(item, index) in draft.acceptedAnswers"
          :key="index"
          class="mb-2 flex items-center gap-2"
        >
          <Input
            :value="item"
            :maxlength="512"
            :placeholder="`可接受答案 ${index + 1}`"
            :aria-label="`可接受答案 ${index + 1}`"
            class="flex-1"
            @update:value="(value: string) => (draft.acceptedAnswers[index] = value)"
          />
          <Button
            size="small"
            danger
            :disabled="draft.acceptedAnswers.length <= 1"
            @click="removeAcceptedAnswer(index)"
          >
            删除
          </Button>
        </div>
        <Button size="small" @click="addAcceptedAnswer">
          + 添加可接受答案（{{ draft.acceptedAnswers.length }}）
        </Button>
        <div class="mt-1 text-gray-500">
          命中任一即满分；中文精确匹配、英文忽略大小写；单项不可含 | 分隔符
        </div>
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
  Tag,
  Textarea,
  Upload,
  message,
} from 'ant-design-vue';
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import {
  create1,
  checkDuplicate,
  list,
  update,
  upload,
  type QuestionDuplicateItemResponse,
  type QuestionResponse,
  type TagResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { DIFFICULTY_OPTIONS, QUESTION_TYPE_OPTIONS } from '@/utils/questionTypes';
import { typeLabelOf } from '@/utils/questionTypes';
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

// ===== 题干查重预检（add-question-duplicate-detection，知情防呆，不阻断保存） =====
const duplicateHits = ref<QuestionDuplicateItemResponse[]>([]);
const duplicateLoading = ref(false);
const duplicateShowPreview = ref(false);
/** 已发起查重待检测的题干文本（避免重复/陈旧结果误报） */
const duplicateCheckedStem = ref('');
let duplicateTimer: ReturnType<typeof setTimeout> | undefined;

/** 读字数 ≥4 的「有效题干」时才算触发条件（与后端 ≥2 字符口径取更保守的上限）。 */
function effectiveStemLength(stem: string): number {
  return stem.trim().length;
}

/** 发起查重：失焦与防抖共用的真正请求。空/过短题干清空提示；命中即渲染 warning。 */
async function runDuplicateCheck(stem: string): Promise<void> {
  const trimmed = stem.trim();
  if (effectiveStemLength(trimmed) < 4) {
    duplicateHits.value = [];
    duplicateCheckedStem.value = '';
    duplicateShowPreview.value = false;
    return;
  }
  if (duplicateCheckedStem.value === trimmed && duplicateHits.value.length > 0) {
    // 同一题干已检过且有结果：不复检（避免连锁防抖重复请求）
    return;
  }
  duplicateLoading.value = true;
  try {
    // SDK 查重端点：query 传题干/排除自身/题型与默认阈值
    const hits = await unwrap<QuestionDuplicateItemResponse[]>(
      checkDuplicate({
        client,
        throwOnError: true,
        query: {
          stem: trimmed,
          excludeId: isEdit.value ? props.question?.id : undefined,
        },
      })
    );
    duplicateHits.value = hits ?? [];
    duplicateCheckedStem.value = trimmed;
    duplicateShowPreview.value = false;
  } catch {
    // 查重失败不阻断录入：静默清空，避免把网络失败渲染成业务告警
    duplicateHits.value = [];
    duplicateCheckedStem.value = '';
  } finally {
    duplicateLoading.value = false;
  }
}

/** 防抖触发（读字数上升过程中不频繁请求；停顿 500ms 后检一次）。 */
function scheduleDuplicateCheck(stem: string): void {
  if (effectiveStemLength(stem) < 4) {
    if (duplicateTimer) {
      clearTimeout(duplicateTimer);
      duplicateTimer = undefined;
    }
    duplicateHits.value = [];
    duplicateCheckedStem.value = '';
    return;
  }
  if (duplicateTimer) {
    clearTimeout(duplicateTimer);
  }
  duplicateTimer = setTimeout(() => {
    void runDuplicateCheck(stem);
  }, 500);
}

/** 失焦立即校验：清掉未决防抖锁并即时请求（让用户停顿离开时立刻看到提示）。 */
function onStemBlur(): void {
  if (duplicateTimer) {
    clearTimeout(duplicateTimer);
    duplicateTimer = undefined;
  }
  void runDuplicateCheck(draft.value.content);
}

// ===== 题干图片上传（add-question-image）：本地预校验与后端口径一致（扩展名白名单 + ≤5MB）=====
const IMAGE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'webp'];
const MAX_IMAGE_MB = 5;

const uploading = ref(false);
const uploadError = ref<string | null>(null);

/** 本地预校验：非白名单扩展名 / 超 5MB 直接拦截（与后端上传端点校验口径一致，不发起无效上传）。 */
function beforeUpload(file: File): boolean {
  uploadError.value = null;
  const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
  if (!IMAGE_EXTENSIONS.includes(ext)) {
    uploadError.value = '仅支持 png/jpg/jpeg/gif/webp 格式';
    return false;
  }
  if (file.size > MAX_IMAGE_MB * 1024 * 1024) {
    uploadError.value = `图片大小不能超过 ${MAX_IMAGE_MB}MB`;
    return false;
  }
  return true;
}

/** 上传：POST /api/question-images → 回填相对 URL；失败态与错误提示（后端拒绝文案原样透出）。 */
async function customUpload(options: { file?: string | Blob | File }): Promise<void> {
  const file =
    typeof options.file === 'object' && options.file !== null ? (options.file as File) : undefined;
  if (!file) return;
  uploading.value = true;
  uploadError.value = null;
  try {
    const result = await unwrap(upload({ client, throwOnError: true, body: { file } }));
    draft.value.imageUrl = result?.url ?? '';
  } catch (error) {
    uploadError.value = error instanceof Error ? error.message : '图片上传失败，请稍后重试';
  } finally {
    uploading.value = false;
  }
}

/** 移除：置空（保存时随表单以空串提交 = 后端归一为无图）。 */
function removeImage(): void {
  draft.value.imageUrl = '';
}

watch(
  () => [props.open, props.question] as const,
  ([open]) => {
    if (open) {
      draft.value = props.question ? draftFromResponse(props.question) : createEmptyDraft(1);
      errors.value = [];
      // 打开弹层即清空查重提示（新题目/新编辑不残留上一次的命中）
      duplicateHits.value = [];
      duplicateCheckedStem.value = '';
      duplicateShowPreview.value = false;
      if (duplicateTimer) {
        clearTimeout(duplicateTimer);
        duplicateTimer = undefined;
      }
    }
  },
  { immediate: true }
);

// 读字数防抖查重：停顿 500ms 触发（失焦路径见 onStemBlur）
watch(
  () => draft.value.content,
  (content) => {
    scheduleDuplicateCheck(content);
  }
);

const { data: tags } = useQuery({
  queryKey: ['tags'],
  queryFn: () => unwrap<TagResponse[]>(list({ client, throwOnError: true })),
});

const tagOptions = computed(() =>
  (tags.value ?? []).map((tag) => ({ value: tag.id as number, label: tag.name ?? String(tag.id) }))
);

/** 切题型时只修正选项区/可接受答案区形态，其余字段保留教师已录入内容。 */
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
    if (nextConfig.answerKind === 'fill' && draft.value.acceptedAnswers.length === 0) {
      draft.value.acceptedAnswers = [''];
    }
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

/** 填空题：增删可接受答案项（至少保留 1 项，与后端「非空列表」口径一致）。 */
function addAcceptedAnswer(): void {
  draft.value.acceptedAnswers.push('');
}

function removeAcceptedAnswer(index: number): void {
  if (draft.value.acceptedAnswers.length <= 1) return;
  draft.value.acceptedAnswers.splice(index, 1);
}

function onCancel(): void {
  emit('update:open', false);
}

/** 命中集中的最高相似度（用于提示「最高相似度 XX%」）。 */
const maxSimilarity = computed<number>(() => {
  let max = 0;
  for (const item of duplicateHits.value) {
    const sim = item.similarity ?? 0;
    if (sim > max) max = sim;
  }
  return max;
});

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
