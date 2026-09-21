<template>
  <div>
    <Alert
      v-if="!examIdValid"
      type="error"
      show-icon
      message="考试 ID 无效，请回到考试列表重新进入"
      data-test="invalid-exam"
    />

    <template v-else>
      <Card :title="snapshot?.examTitle ?? `考试 #${examId}`" class="mb-4">
        <template #extra>
          <Button size="small" @click="router.push('/student/exams')">返回列表</Button>
        </template>

        <ExamCountdown
          :display="countdown.display.value"
          :remaining-seconds="countdown.remainingSeconds.value"
          :expired="countdown.isExpired.value"
          :unanchored="countdown.isUnanchored.value"
        />

        <Alert
          v-if="errorText"
          type="error"
          show-icon
          :message="errorText"
          data-test="paper-error"
          class="mt-2"
        />

        <p v-else-if="closedByBackend" data-test="closed-hint" class="mt-2 text-sm">
          该答卷已由后端封闭（已交卷或已超时收卷），不再接受作答。
        </p>
      </Card>

      <Spin :spinning="isFetching && !snapshot">
        <TakingBoard
          v-if="questions.length"
          :questions="questions"
          :answers="answers"
          :locked="locked"
          @update:answer="onAnswer"
        />

        <!--
          交卷入口不在本片范围（阶段 22 第 3 片：手动交卷二次确认 + pending 防重 +
          超时自动交卷同源）。这里**不放**任何占位「交卷」按钮：
          一个点了什么都没发生、或者只改本地标记的按钮，正是硬约定 3 禁止的形态。
        -->
      </Spin>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 学生答题页（阶段 22 第 1 片：进入考试 + 极简作答 + 服务端时间倒计时与归零锁定）。
 *
 * 数据来源：`POST /api/exam-taking/exams/{examId}/enter` → `EnterExamResponse`
 * （`ExamTakingService.enter`：首次生成个人快照并起表，重复进入幂等返回**同一份**快照与草稿，
 * 所以刷新页面不换题——spec「进入即锁定个人快照」）。
 *
 * 三条本片硬边界，代码里可核对：
 * 1. **剩余时间只来自后端**：`remainingSeconds`（退化到 `deadlineTime - serverTime`，
 *    两者都是服务端值），页面里没有任何 `Date.now()` 参与判定；
 * 2. **归零只锁定作答**：`locked` 只喂给作答控件，不产生「已超时所以不发请求」之类的本地裁决，
 *    最终超时判定在后端（`buildAnsweringContext` 就地兜底 + `ExamSweepService` 定时扫描）；
 * 3. **答案暂存组件状态**：不发请求、不写 localStorage/IndexedDB（第 2 片的口子）。
 */
import { Alert, Button, Card, Spin } from 'ant-design-vue';
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import { enter as enterContract, type EnterExamResponse, type QuestionView } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import ExamCountdown from '@/components/student/ExamCountdown.vue';
import TakingBoard from '@/components/student/TakingBoard.vue';
import { useServerCountdown } from '@/hooks/useServerCountdown';
import { createEnterExamQueryOptions, examIdOf, isClosedByBackend } from '@/hooks/useStudentTaking';
import { ApiError } from '@/api/types';
import { answerMapOf, type AnswerMap } from '@/utils/studentTaking';

const route = useRoute('/(dashboard)/student/exams/[id]');
const router = useRouter();

const examId = computed(() => examIdOf(route.params.id as string | string[] | undefined));
const examIdValid = computed(() => Number.isInteger(examId.value) && examId.value > 0);

/**
 * 整个 options 走 computed：`/student/exams/:id` 在只改路由参数时会被 vue-router **复用组件**
 * （浏览器前进/后退在两场考试之间切换就是这条路径）。写成静态对象的话 key 会被冻结在
 * 挂载时那个 examId 上，URL 是 27 而卷是 9。`useQuery` 接受"返回 options 的 getter/computed"。
 */
const { data, isFetching, error } = useQuery(
  computed(() =>
    createEnterExamQueryOptions(examId.value, (id) =>
      unwrap<EnterExamResponse>(enterContract({ client, throwOnError: true, path: { examId: id } }))
    )
  )
);

const snapshot = computed<EnterExamResponse | null | undefined>(() => data.value);
const questions = computed<QuestionView[]>(() => snapshot.value?.questions ?? []);
const closedByBackend = computed(() => isClosedByBackend(snapshot.value));

const countdown = useServerCountdown(() => snapshot.value);

/** 归零（本地秒表走完服务端给的那段时长）或后端已封闭 → 锁作答。导航不锁，学生要能回看。 */
const locked = computed(() => countdown.isExpired.value || closedByBackend.value);

const answers = ref<AnswerMap>({});

/**
 * 用后端草稿播种一次。
 *
 * 「一次」是重点：进入考试是幂等查询，将来第 2 片加了自动保存后会有反复 refetch，
 * 每次都拿草稿覆盖 `answers` 会把学生刚答的内容冲掉。
 * 与 IndexedDB 本地缓存的保守合并属第 2 片，这里只处理「后端给了什么就用什么」。
 */
const seededFor = ref<number | null>(null);
watch(
  snapshot,
  (next) => {
    if (!next || seededFor.value === examId.value) return;
    seededFor.value = examId.value;
    answers.value = answerMapOf(next.answers);
  },
  { immediate: true }
);

function onAnswer(key: string, value: string): void {
  if (locked.value) return;
  answers.value = { ...answers.value, [key]: value };
}

const errorText = computed<string | null>(() => {
  const caught = error.value;
  if (!caught) return null;
  return caught instanceof ApiError || caught instanceof Error
    ? caught.message
    : '答题数据加载失败';
});
</script>

<style scoped>
.mb-4 {
  margin-bottom: 1rem;
}
.mt-2 {
  margin-top: 0.5rem;
}
</style>
