<template>
  <div>
    <Card title="我的错题本" class="mb-4">
      <template #extra>
        <div class="flex items-center gap-4">
          <span class="hint hidden md:inline">
            按考试分组归集错题（得分小于满分，含部分对），连续 3 次做对自动移出。
          </span>
          <Checkbox
            v-if="!isPracticeMode"
            v-model:checked="includeMastered"
            data-test="toggle-include-mastered"
          >
            显示已消灭错题
          </Checkbox>
          <Button
            v-if="!isPracticeMode"
            type="primary"
            data-test="start-practice-btn"
            @click="startPractice"
          >
            错题重练
          </Button>
        </div>
      </template>

      <!-- 查询失败三态收口：显性呈现 Alert 且文案为后端 message 原文 -->
      <Alert
        v-if="queryError && !isPracticeMode"
        type="error"
        show-icon
        class="mb-3"
        :message="queryError"
        data-test="wrong-questions-error"
      />

      <!-- 重练模式界面 -->
      <div v-if="isPracticeMode" data-test="practice-container" class="space-y-4">
        <div class="flex items-center justify-between border-b pb-3">
          <div>
            <span class="font-bold text-lg text-gray-800">错题重练 / 消灭计划</span>
            <span class="text-xs text-gray-400 ml-2">连续 3 次做对自动移出重练池</span>
          </div>
          <Button data-test="exit-practice-btn" @click="exitPractice">退出重练</Button>
        </div>

        <Alert
          v-if="practiceError"
          type="error"
          show-icon
          class="mb-3"
          :message="practiceError"
          data-test="practice-error"
        />

        <Spin :spinning="isPoolLoading">
          <div v-if="practicePool.length === 0" class="my-8 text-center">
            <Empty description="重练池为空（所有客观错题已消灭或暂无错题）" />
            <Button type="primary" class="mt-4" @click="exitPractice">返回错题本</Button>
          </div>

          <div
            v-else-if="currentQuestion"
            class="bg-white p-6 rounded-lg border border-gray-200 shadow-sm space-y-4"
          >
            <div class="flex items-center justify-between border-b pb-2">
              <div class="flex items-center gap-2">
                <span class="font-semibold text-gray-700">
                  第 {{ currentIndex + 1 }} / {{ practicePool.length }} 题
                </span>
                <Tag color="cyan">{{ currentQuestion.questionType }}</Tag>
                <Tag color="purple">连对进度 {{ currentQuestion.consecutiveCorrect ?? 0 }}/3</Tag>
              </div>
            </div>

            <!-- 题干 -->
            <div class="text-gray-800 text-base font-medium whitespace-pre-wrap">
              {{ currentQuestion.questionContent }}
            </div>

            <!-- 题干图片 -->
            <QuestionImage :src="currentQuestion.imageUrl" class="mb-3" />

            <!-- 作答组件 -->
            <div class="my-4">
              <!-- 单选 -->
              <RadioGroup
                v-if="currentQuestion.questionType === '单选'"
                v-model:value="practiceAnswer"
                :disabled="hasSubmitted"
                class="flex flex-col gap-2"
              >
                <Radio
                  v-for="(choice, idx) in currentQuestion.choices || []"
                  :key="idx"
                  :value="String.fromCharCode(65 + idx)"
                >
                  {{ String.fromCharCode(65 + idx) }}. {{ choice }}
                </Radio>
              </RadioGroup>

              <!-- 多选 -->
              <CheckboxGroup
                v-else-if="currentQuestion.questionType === '多选'"
                v-model:value="multipleAnswers"
                :disabled="hasSubmitted"
                class="flex flex-col gap-2"
              >
                <Checkbox
                  v-for="(choice, idx) in currentQuestion.choices || []"
                  :key="idx"
                  :value="String.fromCharCode(65 + idx)"
                >
                  {{ String.fromCharCode(65 + idx) }}. {{ choice }}
                </Checkbox>
              </CheckboxGroup>

              <!-- 判断 -->
              <RadioGroup
                v-else-if="currentQuestion.questionType === '判断'"
                v-model:value="practiceAnswer"
                :disabled="hasSubmitted"
                class="flex flex-col gap-2"
              >
                <Radio value="T">正确 (T)</Radio>
                <Radio value="F">错误 (F)</Radio>
              </RadioGroup>

              <!-- 填空 -->
              <div v-else-if="isFillBlankType(currentQuestion.questionType)">
                <Input
                  v-model:value="practiceAnswer"
                  placeholder="请输入填空答案"
                  :disabled="hasSubmitted"
                  data-test="fill-blank-input"
                  class="max-w-md"
                />
              </div>
            </div>

            <!-- 提交按钮 -->
            <div v-if="!hasSubmitted" class="pt-2">
              <Button
                type="primary"
                data-test="practice-submit-btn"
                :loading="isSubmitting"
                @click="submitCurrentPractice"
              >
                提交答案
              </Button>
            </div>

            <!-- 即时反馈区 -->
            <div
              v-if="hasSubmitted && practiceFeedback"
              data-test="practice-feedback"
              class="space-y-3 pt-3 border-t"
            >
              <Alert
                :type="practiceFeedback.correct ? 'success' : 'error'"
                :message="practiceFeedback.correct ? '回答正确！' : '回答错误'"
                show-icon
              />

              <div data-test="feedback-consecutive" class="font-medium text-gray-700">
                当前连对进度：{{ practiceFeedback.consecutiveCorrect }}/3
              </div>

              <Alert
                v-if="practiceFeedback.mastered"
                type="success"
                message="恭喜！该题已连续 3 次答对，成功消灭！"
                show-icon
                data-test="mastered-alert"
              />

              <div class="text-sm bg-gray-50 p-3 rounded">
                <div data-test="feedback-correct-answer" class="text-green-700 font-medium mb-1">
                  正确答案：{{ practiceFeedback.correctAnswer }}
                </div>
                <div data-test="feedback-analysis" class="text-gray-700">
                  题目解析：{{ practiceFeedback.analysis || '暂无解析' }}
                </div>
              </div>

              <div>
                <Button type="primary" data-test="next-practice-btn" @click="nextQuestion">
                  下一题
                </Button>
              </div>
            </div>
          </div>
        </Spin>
      </div>

      <!-- 错题本列表模式界面 -->
      <Spin v-else :spinning="isFetching">
        <div v-if="!queryError" data-test="wrong-questions-content">
          <!-- 空态：暂无错题 -->
          <Empty v-if="total === 0 || groups.length === 0" description="暂无错题" class="my-8" />

          <!-- 按考试分组呈现 -->
          <div v-else class="space-y-6">
            <div
              v-for="group in groups"
              :key="group.examId"
              class="exam-group-card border rounded-lg p-4 bg-gray-50/50"
            >
              <div class="flex items-center justify-between border-b pb-2 mb-3">
                <div class="flex items-center gap-2">
                  <span class="font-bold text-base text-gray-800">{{ group.examTitle }}</span>
                  <Tag color="blue">考试 ID: {{ group.examId }}</Tag>
                  <Tag color="orange">{{ (group.wrongQuestions || []).length }} 道错题</Tag>
                </div>
                <span v-if="group.examTime" class="text-xs text-gray-400">
                  考试时间：{{ formatDate(group.examTime) }}
                </span>
              </div>

              <!-- 组内错题列表 -->
              <div class="space-y-4">
                <div
                  v-for="item in group.wrongQuestions"
                  :key="item.questionId"
                  class="bg-white p-4 rounded border border-gray-200 shadow-sm"
                >
                  <div class="flex items-center justify-between mb-2">
                    <div class="flex items-center gap-2">
                      <span class="font-semibold text-gray-700">
                        第 {{ item.questionNumber }} 题
                      </span>
                      <Tag color="cyan">{{ item.questionType }}</Tag>
                      <Tag v-if="item.myScore === 0" color="error">
                        全错 (0/{{ item.fullScore }}分)
                      </Tag>
                      <Tag v-else color="warning">
                        部分对 ({{ item.myScore }}/{{ item.fullScore }}分)
                      </Tag>
                      <!-- 连对进度与消灭状态 -->
                      <Tag v-if="item.mastered" color="success" data-test="mastered-tag">
                        已消灭 ({{ item.consecutiveCorrect }}/3)
                      </Tag>
                      <Tag v-else color="purple" data-test="consecutive-tag">
                        连对进度 {{ item.consecutiveCorrect ?? 0 }}/3
                      </Tag>
                    </div>
                  </div>

                  <!-- 题干 -->
                  <div class="text-gray-800 mb-3 whitespace-pre-wrap font-medium">
                    {{ item.questionContent }}
                  </div>

                  <!-- 题干图片（add-question-image）：含图渲染、无图零回归 -->
                  <QuestionImage :src="item.imageUrl" class="mb-3" />

                  <!-- 客观题选项 -->
                  <div
                    v-if="item.choices && item.choices.length > 0"
                    class="mb-3 pl-2 space-y-1 text-sm text-gray-600"
                  >
                    <div v-for="(choice, idx) in item.choices" :key="idx">
                      {{ String.fromCharCode(65 + idx) }}. {{ choice }}
                    </div>
                  </div>

                  <!-- 作答与标准答案对比 -->
                  <div
                    class="bg-gray-50 p-2.5 rounded text-sm mb-3 grid grid-cols-1 md:grid-cols-2 gap-2"
                  >
                    <div class="text-red-600">
                      <span class="font-medium">我的作答：</span>
                      <span>{{ item.myAnswer ? item.myAnswer : '（未作答）' }}</span>
                    </div>
                    <div class="text-green-600" data-test="wrong-correct-answer">
                      <template v-if="isFillBlankType(item.questionType)">
                        <span class="font-medium">可接受答案（命中任一即可）：</span>
                        <span>{{ acceptedAnswersOf(item.correctAnswer).join(' / ') }}</span>
                      </template>
                      <template v-else>
                        <span class="font-medium">正确答案：</span>
                        <span>{{ item.correctAnswer ? item.correctAnswer : '（无）' }}</span>
                      </template>
                    </div>
                  </div>

                  <!-- 答案解析（严格取自题库，为空时占位） -->
                  <div class="text-sm bg-blue-50/60 p-2.5 rounded border border-blue-100 mb-2">
                    <span class="font-semibold text-blue-800">答案解析：</span>
                    <span v-if="item.analysis" class="text-gray-700 whitespace-pre-wrap">
                      {{ item.analysis }}
                    </span>
                    <span v-else class="text-gray-400 italic">暂无解析</span>
                  </div>

                  <!-- 判分依据或评语（可选） -->
                  <div v-if="item.scoreDetail" class="text-xs text-gray-500 pl-1">
                    <span>判分依据/评语：{{ item.scoreDetail }}</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- 分页控件 -->
            <div class="flex justify-end pt-4">
              <Pagination
                v-model:current="currentPage"
                v-model:pageSize="pageSize"
                :total="total"
                :show-size-changer="false"
                :show-total="(t) => `共 ${t} 场已发布考试`"
                @change="onPageChange"
              />
            </div>
          </div>
        </div>
      </Spin>
    </Card>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  Checkbox,
  CheckboxGroup,
  Empty,
  Input,
  Pagination,
  Radio,
  RadioGroup,
  Spin,
  Tag,
} from 'ant-design-vue';
import { computed, ref } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import dayjs from 'dayjs';

import {
  myPracticePool,
  myWrongQuestions,
  submitPractice,
  type PracticePoolItem,
  type PracticeSubmitResponse,
  type WrongQuestionPageResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { ApiError } from '@/api/types';
import QuestionImage from '@/components/question/QuestionImage.vue';
import { acceptedAnswersOf, isFillBlankType } from '@/utils/questionTypes';

const queryClient = useQueryClient();

const currentPage = ref(1);
const pageSize = ref(10);
const includeMastered = ref(false);

// 错题列表数据
const {
  data: pageData,
  isFetching,
  error,
  refetch: refetchList,
} = useQuery({
  queryKey: computed(
    () =>
      ['student-wrong-questions', currentPage.value, pageSize.value, includeMastered.value] as const
  ),
  queryFn: () =>
    unwrap<WrongQuestionPageResponse>(
      myWrongQuestions({
        client,
        throwOnError: true,
        query: {
          page: currentPage.value,
          size: pageSize.value,
          includeMastered: includeMastered.value,
        },
      })
    ),
});

const total = computed(() => pageData.value?.total ?? 0);
const groups = computed(() => pageData.value?.groups ?? []);

const queryError = computed<string | null>(() => {
  const caught = error.value;
  if (!caught) return null;
  return caught instanceof ApiError || caught instanceof Error ? caught.message : '错题本加载失败';
});

// 重练模式状态
const isPracticeMode = ref(false);
const isPoolLoading = ref(false);
const practicePool = ref<PracticePoolItem[]>([]);
const currentIndex = ref(0);
const practiceAnswer = ref('');
const multipleAnswers = ref<string[]>([]);
const hasSubmitted = ref(false);
const isSubmitting = ref(false);
const practiceFeedback = ref<PracticeSubmitResponse | null>(null);
const practiceError = ref<string | null>(null);

const currentQuestion = computed<PracticePoolItem | undefined>(() => {
  return practicePool.value[currentIndex.value];
});

async function startPractice(): Promise<void> {
  isPracticeMode.value = true;
  practiceError.value = null;
  isPoolLoading.value = true;
  currentIndex.value = 0;
  resetPracticeForm();

  try {
    const list = await unwrap<PracticePoolItem[]>(
      myPracticePool({
        client,
        throwOnError: true,
      })
    );
    practicePool.value = Array.isArray(list) ? list : [];
  } catch (err) {
    practiceError.value =
      err instanceof ApiError || err instanceof Error ? err.message : '加载重练池失败';
  } finally {
    isPoolLoading.value = false;
  }
}

function resetPracticeForm(): void {
  practiceAnswer.value = '';
  multipleAnswers.value = [];
  hasSubmitted.value = false;
  practiceFeedback.value = null;
}

async function submitCurrentPractice(): Promise<void> {
  const q = currentQuestion.value;
  if (!q?.questionId) return;

  let finalAnswer = '';
  if (q.questionType === '多选') {
    finalAnswer = multipleAnswers.value.slice().sort().join(',');
  } else {
    finalAnswer = (practiceAnswer.value || '').trim();
  }

  isSubmitting.value = true;
  practiceError.value = null;

  try {
    const res = await unwrap<PracticeSubmitResponse>(
      submitPractice({
        client,
        throwOnError: true,
        body: {
          questionId: q.questionId,
          answer: finalAnswer,
        },
      })
    );
    practiceFeedback.value = res ?? null;
    hasSubmitted.value = true;
  } catch (err) {
    practiceError.value =
      err instanceof ApiError || err instanceof Error ? err.message : '提交答案失败';
  } finally {
    isSubmitting.value = false;
  }
}

function nextQuestion(): void {
  // 若当前题目已消灭，从池中移除
  if (practiceFeedback.value?.mastered && currentQuestion.value) {
    practicePool.value = practicePool.value.filter(
      (item) => item.questionId !== currentQuestion.value?.questionId
    );
  }

  if (practicePool.value.length === 0) {
    resetPracticeForm();
    return;
  }

  if (currentIndex.value >= practicePool.value.length) {
    currentIndex.value = 0;
  }
  resetPracticeForm();
}

function exitPractice(): void {
  isPracticeMode.value = false;
  practiceError.value = null;
  resetPracticeForm();
  // 刷新错题本列表
  queryClient.invalidateQueries({ queryKey: ['student-wrong-questions'] });
  refetchList();
}

function formatDate(dateStr: string): string {
  try {
    return dayjs(dateStr).format('YYYY-MM-DD HH:mm');
  } catch {
    return dateStr;
  }
}

function onPageChange(page: number): void {
  currentPage.value = page;
}
</script>

<style scoped>
.mb-2 {
  margin-bottom: 0.5rem;
}
.mb-3 {
  margin-bottom: 0.75rem;
}
.mb-4 {
  margin-bottom: 1rem;
}
.hint {
  color: #999;
  font-size: 12px;
  font-weight: normal;
}
</style>
