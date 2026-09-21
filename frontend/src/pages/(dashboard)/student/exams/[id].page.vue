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

        <!--
          草稿同步状态（阶段 22 第 2 片）：pending / synced / unsynced / conflict /
          归零锁定后的「待同步」。这不是离线作答模式——断线期间只留底答案，
          恢复后同步（spec「不声称离线考试」场景，措辞见 DraftSyncBadge）。
        -->
        <DraftSyncBadge
          v-if="!closedByBackend"
          class="mt-2"
          :status="autoSave.status.value"
          :last-saved-at="autoSave.lastSavedAt.value"
          :pending-sync="autoSave.pendingSync.value"
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

        <!--
          保守合并的冲突分支（硬约定 7）：后端草稿与本机缓存都留着，绝不静默覆盖。
          默认播种后端那份（超时兜底收卷从服务器草稿取答案），学生显式点按钮才换本地。
        -->
        <Alert
          v-if="seedConflict"
          type="warning"
          show-icon
          message="检测到本机有一份未同步的答案，与服务器草稿不一致"
          data-test="draft-seed-conflict"
          class="mt-2"
        >
          <template #description>
            <p>两份草稿都已保留（服务器一份、本机一份），未做任何覆盖。</p>
            <Button size="small" danger data-test="use-local-draft" @click="useLocalDraft">
              改用本机未同步的答案
            </Button>
          </template>
        </Alert>
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
 * 学生答题页（阶段 22 第 1 片：进入考试 + 极简作答 + 服务端时间倒计时与归零锁定；
 * 第 2 片：30s 自动保存 + IndexedDB 本地缓存 + 保守合并 + 归零「待同步」）。
 *
 * 数据来源：`POST /api/exam-taking/exams/{examId}/enter` → `EnterExamResponse`
 * （`ExamTakingService.enter`：首次生成个人快照并起表，重复进入幂等返回**同一份**快照与草稿，
 * 所以刷新页面不换题——spec「进入即锁定个人快照」）。
 *
 * 第 2 片的三条边界，代码里可核对：
 * 1. **保存频率不高于后端 30s 设计假设**：后端请求只从 `useAutoSaveDraft` 的
 *    定时器（30s）/ 输入防抖（30s）/ 网络恢复三个入口发出；学生每敲一下键盘只写
 *    IndexedDB（本地操作，无网络负载）；
 * 2. **播种走保守合并**：后端草稿 × IndexedDB 缓存交给 `mergeDrafts` 纯函数，
 *    冲突时两份都保留并提示，默认用后端那份，学生显式选择才换本地；
 * 3. **归零只锁定 + 待同步**：`pendingSync` 亮起后网络恢复（`online` 事件）即把最终
 *    答案保存为服务器草稿——超时收卷由后端定时扫描从草稿取答案兜底（`ExamSweepService`），
 *    前端不判定超时、也不在此发交卷请求（交卷是第 3 片）。
 */
import { Alert, Button, Card, Spin } from 'ant-design-vue';
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import {
  enter as enterContract,
  saveDraft as saveDraftContract,
  type AutoSaveResponse,
  type EnterExamResponse,
  type QuestionView,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import ExamCountdown from '@/components/student/ExamCountdown.vue';
import TakingBoard from '@/components/student/TakingBoard.vue';
import DraftSyncBadge from '@/components/student/DraftSyncBadge.vue';
import { useServerCountdown } from '@/hooks/useServerCountdown';
import { useAutoSaveDraft } from '@/hooks/useAutoSaveDraft';
import { createEnterExamQueryOptions, examIdOf, isClosedByBackend } from '@/hooks/useStudentTaking';
import { ApiError } from '@/api/types';
import { answerMapOf, type AnswerMap } from '@/utils/studentTaking';
import { mergeDrafts, resolveSeed, serverDraftOf, type DraftRecord } from '@/utils/draftMerge';
import { createStudentDraftStorage } from '@/utils/draftStorage';

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

/** 本地草稿缓存：生产是 IndexedDB 薄封装；jsdom 下自动降级内存（见 draftStorage 注释）。 */
const draftStorage = createStudentDraftStorage();

const autoSave = useAutoSaveDraft({
  examId: () => examId.value,
  answersSource: () => answers.value,
  locked: () => countdown.isExpired.value,
  // 后端已封闭（已交卷/已收卷）或 ID 非法 → 一切保存动作挂空挡
  suspended: () => closedByBackend.value || !examIdValid.value,
  deps: {
    saveDraft: (id, payload) =>
      unwrap<AutoSaveResponse>(
        saveDraftContract({
          client,
          throwOnError: true,
          path: { examId: id },
          body: { version: payload.version, answers: payload.answers, marked: payload.marked },
        })
      ),
    storage: draftStorage,
  },
});

/** 播种冲突时保留的本地那份（学生可显式改用，见上方 Alert）。 */
const seedConflict = ref<DraftRecord | null>(null);

/**
 * 用后端草稿 × 本地缓存保守合并后播种一次。
 *
 * 「一次」是重点：进入考试是幂等查询，refetch（窗口聚焦等）反复发生，
 * 每次都拿草稿覆盖 `answers` 会把学生刚答的内容冲掉（硬约定 7 的反面）。
 * `next.examId` 守卫挡的是「路由复用组件切考试」时旧快照播种进新 examId 的竞态。
 */
const seededFor = ref<number | null>(null);
watch(
  snapshot,
  async (next) => {
    if (!next || seededFor.value === examId.value) return;
    if (next.examId !== undefined && next.examId !== examId.value) return;
    seededFor.value = examId.value;

    const server = serverDraftOf(next, answerMapOf);
    const local = await draftStorage.load(examId.value);
    const seed = resolveSeed(mergeDrafts(server, local));
    answers.value = seed.answers;
    seedConflict.value = seed.conflict;
    autoSave.seed({ version: seed.version, savedAt: null, answers: seed.answers });
  },
  { immediate: true }
);

function onAnswer(key: string, value: string): void {
  if (locked.value) return;
  answers.value = { ...answers.value, [key]: value };
  // 作答通知自动保存引擎：标脏 + 写 IndexedDB + 重置 30s 防抖（播种/换卷不调它）
  autoSave.notifyAnswered();
}

/** 学生显式选择本地那份未同步答案：换内容 + 标脏，30s 防抖内自动保存推上服务器。 */
function useLocalDraft(): void {
  if (!seedConflict.value) return;
  answers.value = { ...seedConflict.value.answers };
  seedConflict.value = null;
  autoSave.notifyAnswered();
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
