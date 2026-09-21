<template>
  <div>
    <QuestionNavPanel :states="states" data-test="board-nav" @select="goTo" />

    <div class="meta-row">
      <span data-test="progress">
        已答 {{ answeredCount }} / {{ questions.length }}（进度仅供参考，交卷与否由后端判定）
      </span>
    </div>

    <QuestionAnswerCard
      v-if="current"
      :question="current"
      :answer="currentAnswer"
      :locked="locked"
      @update:answer="onAnswer"
    />

    <Empty v-else description="后端未返回题目（答卷已封闭或快照为空）" data-test="board-empty" />

    <Space class="mt-3">
      <Button data-test="prev" :disabled="currentIndex === 0" @click="goTo(currentIndex - 1)">
        上一题
      </Button>
      <Button
        data-test="next"
        :disabled="currentIndex >= questions.length - 1"
        @click="goTo(currentIndex + 1)"
      >
        下一题
      </Button>
      <span class="text-xs text-gray-500" data-test="cursor">
        当前第 {{ currentIndex + 1 }} / {{ questions.length }} 题
      </span>
    </Space>
  </div>
</template>

<script setup lang="ts">
/**
 * 答题工作台容器（阶段 22 第 1 片）：导航 + 当前题作答控件 + 上一题/下一题。
 *
 * 状态归属说清楚（第 2 片要在此基础上接自动保存，别把答案挪进 store）：
 * - **答案存在组件状态**：由父页面持有 `answers`（`ref<AnswerMap>`），本组件只做展示与回抛，
 *   不发任何请求、不写任何本地存储——断线恢复与 IndexedDB 缓存是第 2 片的口子；
 * - **当前题号存在本组件状态**：它是纯视图焦点，不参与任何判定，也没必要往上传。
 *
 * 界面刻意保持极简：无计算器、无标记备注、无草稿画板（proposal 与硬约定 10/12）。
 */
import { Button, Empty, Space } from 'ant-design-vue';
import { computed, ref, watch } from 'vue';

import type { QuestionView } from '@/api/axios';
import QuestionAnswerCard from '@/components/student/QuestionAnswerCard.vue';
import QuestionNavPanel from '@/components/student/QuestionNavPanel.vue';
import { answerKeyOf, navStatesOf, type AnswerMap } from '@/utils/studentTaking';

const props = withDefaults(
  defineProps<{
    questions: QuestionView[];
    answers: AnswerMap;
    /** 倒计时归零：所有作答入口锁死（导航不锁，学生要能回看） */
    locked?: boolean;
  }>(),
  { locked: false }
);

const emit = defineEmits<{ 'update:answer': [key: string, value: string] }>();

const currentIndex = ref(0);

const current = computed<QuestionView | undefined>(() => props.questions[currentIndex.value]);

const currentAnswer = computed(() =>
  current.value ? (props.answers[answerKeyOf(current.value)] ?? '') : ''
);

const states = computed(() => navStatesOf(props.questions, props.answers, currentIndex.value));

const answeredCount = computed(() => states.value.filter((state) => state.answered).length);

// 题目集合变化（首次加载、断线重连后重拉）时把焦点收进合法区间，
// 但**不重置**已有答案——那是第 2 片保守合并要守的不变式，这里先不破坏它。
watch(
  () => props.questions.length,
  (length) => {
    if (currentIndex.value > length - 1) currentIndex.value = Math.max(0, length - 1);
  }
);

function goTo(index: number): void {
  if (index < 0 || index > props.questions.length - 1) return;
  currentIndex.value = index;
}

function onAnswer(value: string): void {
  if (props.locked) return;
  const question = current.value;
  if (!question) return;
  emit('update:answer', answerKeyOf(question), value);
}
</script>

<style scoped>
.meta-row {
  margin: 8px 0 12px;
  font-size: 12px;
  color: #666;
}
.mt-3 {
  margin-top: 12px;
}
</style>
