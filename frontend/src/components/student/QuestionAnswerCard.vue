<template>
  <Card :title="cardTitle" size="small" class="mb-3">
    <p class="question-content" data-test="question-content">
      {{ question.content ?? '（题干为空）' }}
    </p>

    <!-- 题型未知：如实说明，绝不退化成单选题控件（那会让学生答出一个后端认不了的答案） -->
    <Alert
      v-if="kind === 'unsupported'"
      type="warning"
      show-icon
      message="该题型后端未在本阶段交付作答控件，请跳过本题"
      data-test="unsupported"
    />

    <!-- 单选：一个选项 -->
    <RadioGroup
      v-else-if="kind === 'single'"
      :options="choiceOptions"
      :value="singleValue"
      :disabled="locked"
      data-test="answer-single"
      @update:value="onSingle"
    />

    <!-- 多选：多个选项 -->
    <CheckboxGroup
      v-else-if="kind === 'multiple'"
      :options="choiceOptions"
      :value="multiValue"
      :disabled="locked"
      data-test="answer-multiple"
      @update:value="onMultiple"
    />

    <!-- 判断：只提交后端约定的 T/F（别名归一是后端 AnswerNormalizer 的职责，见阶段 20 硬约定 4） -->
    <RadioGroup
      v-else-if="kind === 'judge'"
      :options="JUDGE_OPTIONS"
      :value="singleValue"
      :disabled="locked"
      data-test="answer-judge"
      @update:value="onSingle"
    />

    <!-- 简答：原文提交，不做前端截断（长度上限由后端校验，前端截了会静默丢字） -->
    <Textarea
      v-else
      :value="textValue"
      :rows="4"
      :disabled="locked"
      placeholder="请输入答案"
      data-test="answer-text"
      @update:value="onText"
    />

    <p v-if="locked" class="locked-hint" data-test="locked-hint">
      倒计时已归零，作答入口已锁定（超时与否由服务端时间判定）
    </p>
  </Card>
</template>

<script setup lang="ts">
/**
 * 单题作答控件（阶段 22 第 1 片）。
 *
 * 四种题型共用这一份骨架，分支由 `questionTypeConfig.QUESTION_TYPE_CONFIGS` 的
 * `answerKind` + `correctMode` 驱动——**复用阶段 20 的题型配置驱动做法**，但那是编辑表单、
 * 这里是作答控件，所以字段清单与录入校验都不带过来（学生不需要也不该看到"正确项"）。
 *
 * 选项顺序 = `question.choices` 的数组顺序：个人快照里的选项已被后端洗牌并锁定
 * （`PersonalPaperService.shuffleChoicesAndRemapAnswer`，同时把 `correctAnswer` 字母重映射）。
 * 前端**任何**重排都会让学生看到的 A 不等于系统认的 A，因此这里既不排序也不再次打乱。
 *
 * `locked`（倒计时归零）是硬约束：不仅控件置 disabled，emit 前还再判一次——
 * 只靠 disabled 属于"浏览器帮你挡住"，改判成本太低。
 */
import { Alert, Card, CheckboxGroup, RadioGroup, Textarea } from 'ant-design-vue';
import { computed } from 'vue';

import type { QuestionView } from '@/api/axios';
import { QUESTION_TYPE_CONFIGS } from '@/components/question/questionTypeConfig';
import type { QuestionTypeCode } from '@/utils/questionTypes';
import {
  answerOfLetters,
  choicesOf,
  letterOfChoiceIndex,
  lettersOfAnswer,
} from '@/utils/studentTaking';

const props = withDefaults(
  defineProps<{
    question: QuestionView;
    /** 本题答案原文（后端规范形态：单选 A / 多选 A,C / 判断 T|F / 简答文本） */
    answer?: string;
    /** 倒计时归零锁定：禁止继续作答 */
    locked?: boolean;
  }>(),
  { answer: '', locked: false }
);

const emit = defineEmits<{ 'update:answer': [value: string] }>();

const JUDGE_OPTIONS = [
  { value: 'T', label: '正确' },
  { value: 'F', label: '错误' },
];

type AnswerKind = 'single' | 'multiple' | 'judge' | 'text' | 'unsupported';

const kind = computed<AnswerKind>(() => {
  const config = QUESTION_TYPE_CONFIGS[props.question.type as QuestionTypeCode];
  if (!config) return 'unsupported';
  if (config.answerKind === 'letters')
    return config.correctMode === 'multiple' ? 'multiple' : 'single';
  return config.answerKind;
});

const selectedLetters = computed(() => lettersOfAnswer(props.answer));

/** 选项渲染顺序即数组顺序：`letterOfChoiceIndex(i)` 与后端重映射后的答案字母同源同序。 */
const choiceOptions = computed(() =>
  choicesOf(props.question).map((text, index) => ({
    value: letterOfChoiceIndex(index),
    label: `${letterOfChoiceIndex(index)}. ${text}`,
  }))
);

const singleValue = computed(() => selectedLetters.value[0]);
const multiValue = computed(() => selectedLetters.value);
const textValue = computed(() => props.answer ?? '');

const cardTitle = computed(() => {
  const number = props.question.number ?? '?';
  const score = props.question.score;
  return `第 ${number} 题${score === undefined || score === null ? '' : `（${score} 分）`}`;
});

function commit(value: string): void {
  if (props.locked) return;
  emit('update:answer', value);
}

function onSingle(value: unknown): void {
  commit(typeof value === 'string' ? value.trim().toUpperCase() : '');
}

function onMultiple(value: unknown): void {
  const list = Array.isArray(value) ? value : [];
  commit(answerOfLetters(list.filter((item): item is string => typeof item === 'string')));
}

function onText(value: unknown): void {
  commit(typeof value === 'string' ? value : '');
}
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
.question-content {
  white-space: pre-wrap;
  margin-bottom: 0.5rem;
}
.locked-hint {
  margin-top: 0.5rem;
  color: #cf1322;
  font-size: 12px;
}
</style>
