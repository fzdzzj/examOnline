<template>
  <div data-test="adaptive-quiz-panel" class="space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <div class="hint">
        按能力值 θ 动态出题：开局取中等难度题，作答后即时重估 θ，选题取信息量最大者； SE
        收敛、达最大题数或题池耗尽即终止并产出能力报告。
      </div>
      <div class="flex items-center gap-2">
        <template v-if="phase === 'idle'">
          <span class="hint">题数</span>
          <InputNumber
            v-model:value="maxItems"
            :min="1"
            :max="20"
            size="small"
            class="w-20"
            data-test="adaptive-max-items"
          />
          <Button type="primary" :loading="starting" data-test="adaptive-start-btn" @click="start">
            开始自适应测验
          </Button>
        </template>
        <Button v-else data-test="adaptive-restart-btn" @click="reset">重新开始</Button>
      </div>
    </div>

    <Alert v-if="error" type="error" show-icon :message="error" data-test="adaptive-error" />

    <div
      v-if="phase === 'idle' && !error"
      class="rounded border border-dashed p-4 hint"
      data-test="adaptive-intro"
    >
      题池来自你已判分考试的 IRT
      标定结果（样本不足的题不入池）。逐题作答，答对答错都会即时反映到能力值。
    </div>

    <template v-else-if="phase === 'running' && question">
      <div class="flex flex-wrap items-center gap-4" data-test="adaptive-progress">
        <Tag color="blue">第 {{ answeredCount + 1 }} / {{ maxItems }} 题</Tag>
        <span class="hint">
          θ
          <b data-test="adaptive-theta">{{ theta.toFixed(2) }}</b>
        </span>
        <span class="hint">
          SE
          <b data-test="adaptive-se">{{ se.toFixed(2) }}</b>
        </span>
      </div>

      <div class="rounded border p-4" data-test="adaptive-question">
        <div class="mb-3 flex items-start gap-2">
          <Tag>{{ question.questionType }}</Tag>
          <span class="font-medium">{{ question.questionContent }}</span>
        </div>
        <QuestionImage v-if="question.imageUrl" :src="question.imageUrl" class="mb-2" />

        <RadioGroup
          v-if="isSingle"
          v-model:value="singleAnswer"
          class="flex flex-col gap-2"
          data-test="adaptive-choices"
        >
          <Radio v-for="(choice, idx) in question.choices || []" :key="idx" :value="letterOf(idx)">
            {{ letterOf(idx) }}. {{ choice }}
          </Radio>
        </RadioGroup>

        <CheckboxGroup
          v-else-if="isMultiple"
          v-model:value="multipleSelected"
          class="flex flex-col gap-2"
          data-test="adaptive-choices"
        >
          <Checkbox
            v-for="(choice, idx) in question.choices || []"
            :key="idx"
            :value="letterOf(idx)"
          >
            {{ letterOf(idx) }}. {{ choice }}
          </Checkbox>
        </CheckboxGroup>

        <RadioGroup v-else-if="isJudge" v-model:value="judgeAnswer" data-test="adaptive-choices">
          <Radio value="T">正确</Radio>
          <Radio value="F">错误</Radio>
        </RadioGroup>

        <Input
          v-else
          v-model:value="fillAnswer"
          placeholder="请输入答案"
          data-test="adaptive-fill-input"
          @press-enter="submit"
        />
      </div>

      <Alert
        v-if="feedback"
        :type="feedback.correct ? 'success' : 'error'"
        show-icon
        data-test="adaptive-feedback"
      >
        <template #message>
          {{ feedback.correct ? '回答正确' : '回答错误' }}
        </template>
        <template #description>
          <div>正确答案：{{ feedback.correctAnswer || '—' }}</div>
          <div v-if="feedback.analysis">解析：{{ feedback.analysis }}</div>
          <div class="mt-1">能力值 θ 更新为 {{ theta.toFixed(2) }}（SE {{ se.toFixed(2) }}）</div>
        </template>
      </Alert>

      <div class="flex items-center gap-2">
        <Button
          type="primary"
          :loading="submitting"
          :disabled="!answerReady"
          data-test="adaptive-submit-btn"
          @click="submit"
        >
          提交本题
        </Button>
        <span class="hint">答对后下一题会向更高难度移动（θ 上调）</span>
      </div>
    </template>

    <template v-else-if="phase === 'finished'">
      <div class="rounded border p-4" data-test="adaptive-report">
        <div class="mb-3 flex flex-wrap items-center gap-3">
          <span class="font-medium">能力报告</span>
          <Tag color="blue" data-test="adaptive-termination-reason">
            {{ terminationLabel }}
          </Tag>
        </div>
        <div class="grid grid-cols-2 gap-3 md:grid-cols-4">
          <div>
            <div class="hint">能力值 θ</div>
            <div class="text-xl font-bold" data-test="adaptive-report-theta">
              {{ reportTheta.toFixed(2) }}
            </div>
          </div>
          <div>
            <div class="hint">标准误 SE</div>
            <div class="text-xl font-bold" data-test="adaptive-report-se">
              {{ reportSe.toFixed(2) }}
            </div>
          </div>
          <div>
            <div class="hint">正确率</div>
            <div class="text-xl font-bold" data-test="adaptive-report-accuracy">
              {{ reportAccuracy }}%
            </div>
          </div>
          <div>
            <div class="hint">作答 / 答对</div>
            <div class="text-xl font-bold" data-test="adaptive-report-count">
              {{ report?.answeredCount ?? answeredCount }} /
              {{ report?.correctCount ?? correctCount }}
            </div>
          </div>
        </div>

        <div class="mt-4 space-y-2">
          <div class="hint">逐题轨迹</div>
          <div
            v-for="item in report?.items || []"
            :key="item.questionId"
            class="flex flex-wrap items-center gap-2 rounded border p-2"
            data-test="adaptive-report-item"
          >
            <Tag color="default">{{ item.order }}</Tag>
            <span :class="item.correct ? 'text-green-700' : 'text-red-600'">
              {{ item.correct ? '✓' : '✗' }}
            </span>
            <span class="flex-1">{{ item.questionContent }}</span>
            <span class="hint">作答：{{ item.answer || '—' }}</span>
            <span class="hint">正确答案：{{ item.correctAnswer || '—' }}</span>
            <span class="hint">θ {{ Number(item.thetaAfter ?? 0).toFixed(2) }}</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 自适应测验面板（spec add-irt-adaptive-testing）：
 *
 * - 逐题作答流：开局 → 单题作答 → 下一题 / 终局；
 * - 能力值实时反馈：每题后展示 θ 与 SE（后端 EAP 估计，前端只呈现）；
 * - 终止能力报告：θ、SE、正确率、终止原因与逐题轨迹；
 * - 判分同源：作答只提交原始答案，判定与解析一律来自后端（不本地判分）。
 */
import { Alert, Button, Checkbox, Input, InputNumber, Radio, Tag } from 'ant-design-vue';
import { computed, ref } from 'vue';

import {
  getAdaptiveQuizReport,
  startAdaptiveQuiz,
  submitAdaptiveQuizAnswer,
  type AdaptiveQuizAnswerResponse,
  type AdaptiveQuizQuestionItem,
  type AdaptiveQuizReportResponse,
  type AdaptiveQuizStartResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { ApiError } from '@/api/types';
import QuestionImage from '@/components/question/QuestionImage.vue';
import { QUESTION_TYPE_LABELS } from '@/utils/questionTypes';

const RadioGroup = Radio.Group;
const CheckboxGroup = Checkbox.Group;

type Phase = 'idle' | 'running' | 'finished';

const phase = ref<Phase>('idle');
const starting = ref(false);
const submitting = ref(false);
const error = ref('');

const maxItems = ref(10);
const sessionId = ref(0);
const theta = ref(0);
const se = ref(1);
const answeredCount = ref(0);
const correctCount = ref(0);
const question = ref<AdaptiveQuizQuestionItem | null>(null);
const feedback = ref<AdaptiveQuizAnswerResponse | null>(null);
const report = ref<AdaptiveQuizReportResponse | null>(null);

const singleAnswer = ref<string | undefined>(undefined);
const multipleSelected = ref<string[]>([]);
const judgeAnswer = ref<string | undefined>(undefined);
const fillAnswer = ref('');

const isSingle = computed(() => question.value?.questionType === QUESTION_TYPE_LABELS[1]);
const isMultiple = computed(() => question.value?.questionType === QUESTION_TYPE_LABELS[2]);
const isJudge = computed(() => question.value?.questionType === QUESTION_TYPE_LABELS[3]);
const isFillBlank = computed(() => question.value?.questionType === QUESTION_TYPE_LABELS[5]);

const answerReady = computed(() => {
  if (isSingle.value) return !!singleAnswer.value;
  if (isMultiple.value) return multipleSelected.value.length > 0;
  if (isJudge.value) return !!judgeAnswer.value;
  if (isFillBlank.value) return fillAnswer.value.trim().length > 0;
  return false;
});

const reportTheta = computed(() => report.value?.theta ?? theta.value);
const reportSe = computed(() => report.value?.se ?? se.value);
const reportAccuracy = computed(() => {
  const accuracy = report.value?.accuracy;
  return typeof accuracy === 'number' ? accuracy : 0;
});

const terminationLabel = computed(() => {
  switch (report.value?.terminationReason) {
    case 'SE_CONVERGED':
      return '能力估计已收敛（SE 达标）';
    case 'MAX_ITEMS':
      return '已达最大题数';
    case 'POOL_EXHAUSTED':
      return '可用题池已耗尽';
    default:
      return '进行中';
  }
});

function letterOf(index: number): string {
  return String.fromCharCode(65 + index);
}

function errorMessageOf(err: unknown, fallback: string): string {
  return err instanceof ApiError || err instanceof Error ? err.message : fallback;
}

function resetAnswerInputs(): void {
  singleAnswer.value = undefined;
  multipleSelected.value = [];
  judgeAnswer.value = undefined;
  fillAnswer.value = '';
}

function currentAnswer(): string {
  if (isSingle.value) return singleAnswer.value ?? '';
  if (isMultiple.value) {
    return [...multipleSelected.value].sort().join(',');
  }
  if (isJudge.value) return judgeAnswer.value ?? '';
  return fillAnswer.value.trim();
}

async function start(): Promise<void> {
  starting.value = true;
  error.value = '';
  report.value = null;
  feedback.value = null;
  try {
    const res = await unwrap<AdaptiveQuizStartResponse>(
      startAdaptiveQuiz({
        client,
        throwOnError: true,
        body: { maxItems: maxItems.value ?? 10 },
      })
    );
    if (!res) {
      throw new Error('开局响应为空');
    }
    sessionId.value = res.sessionId ?? 0;
    theta.value = res.theta ?? 0;
    se.value = res.se ?? 1;
    answeredCount.value = res.answeredCount ?? 0;
    correctCount.value = 0;
    question.value = res.currentQuestion ?? null;
    resetAnswerInputs();
    phase.value = 'running';
  } catch (err) {
    error.value = errorMessageOf(err, '开始自适应测验失败');
  } finally {
    starting.value = false;
  }
}

async function submit(): Promise<void> {
  const currentQuestion = question.value;
  const questionId = currentQuestion?.questionId;
  if (!currentQuestion || questionId === undefined || !answerReady.value) return;
  submitting.value = true;
  error.value = '';
  try {
    const res = await unwrap<AdaptiveQuizAnswerResponse>(
      submitAdaptiveQuizAnswer({
        client,
        throwOnError: true,
        path: { sessionId: sessionId.value },
        body: { questionId, answer: currentAnswer() },
      })
    );
    if (!res) {
      throw new Error('作答响应为空');
    }
    feedback.value = res;
    theta.value = res.theta ?? theta.value;
    se.value = res.se ?? se.value;
    answeredCount.value = res.answeredCount ?? answeredCount.value + 1;
    if (res.correct) {
      correctCount.value += 1;
    }
    if (res.terminated) {
      await finish();
      return;
    }
    question.value = res.nextQuestion ?? null;
    resetAnswerInputs();
  } catch (err) {
    error.value = errorMessageOf(err, '提交作答失败');
  } finally {
    submitting.value = false;
  }
}

async function finish(): Promise<void> {
  try {
    report.value =
      (await unwrap<AdaptiveQuizReportResponse>(
        getAdaptiveQuizReport({
          client,
          throwOnError: true,
          path: { sessionId: sessionId.value },
        })
      )) ?? null;
  } catch (err) {
    error.value = errorMessageOf(err, '能力报告加载失败');
  } finally {
    question.value = null;
    phase.value = 'finished';
  }
}

function reset(): void {
  phase.value = 'idle';
  error.value = '';
  feedback.value = null;
  report.value = null;
  question.value = null;
  sessionId.value = 0;
  theta.value = 0;
  se.value = 1;
  answeredCount.value = 0;
  correctCount.value = 0;
  resetAnswerInputs();
}
</script>
