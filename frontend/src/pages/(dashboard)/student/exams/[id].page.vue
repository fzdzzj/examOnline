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

        <!--
          切屏警告（第 3 片）：文案与严重度全部来自后端 `BehaviorReportResponse`，
          前端不改写、不放大。只警告——没有「切屏 N 次自动交卷」的逻辑（硬约定 8）。
        -->
        <Alert
          v-if="behaviorWarning"
          type="warning"
          show-icon
          :message="behaviorWarning.message ?? '检测到切屏 / 失焦行为'"
          :description="behaviorDescription"
          data-test="behavior-warning"
          class="mt-2"
        />

        <Alert
          v-if="errorText"
          type="error"
          show-icon
          :message="errorText"
          data-test="paper-error"
          class="mt-2"
        />

        <p
          v-else-if="closedByBackend && !submitResultView"
          data-test="closed-hint"
          class="mt-2 text-sm"
        >
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
        <!--
          交卷结果（第 3 片）：交卷成功或重进已封闭的答卷时呈现。
          后端 `SubmitResponse` / `EnterExamResponse` 都不含得分字段（已核实），
          结果卡只呈现交卷状态，得分如实标注「待批改」，不猜分（硬约定 13）。
        -->
        <SubmitResultCard
          v-if="submitResultView"
          :result="submitResultView"
          data-test="result-card"
        />

        <template v-else>
          <TakingBoard
            v-if="questions.length"
            :questions="questions"
            :answers="answers"
            :locked="locked"
            @update:answer="onAnswer"
          />

          <!--
            交卷入口（第 3 片）：手动二次确认 + 归零自动交卷共用 `submitExam`。
            上一片故意不在这里放占位按钮——一个只改本地标记的按钮正是硬约定 3 的禁物；
            现在它是后端三重幂等的真实前端配合层。
          -->
          <SubmitExamPanel
            v-if="submitEnabled"
            class="mt-3"
            :unanswered-count="unansweredCount"
            :total-questions="questions.length"
            :submitting="submitHook.submitting.value"
            :phase="submitHook.phase.value"
            :error="submitHook.error.value"
            data-test="submit-panel-host"
            @submit="onManualSubmit"
            @retry="submitHook.retry"
          />
        </template>
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
 * 3. **归零只锁定 + 待同步 + 交卷同源**：`pendingSync` 亮起后网络恢复（`online` 事件）即把最终
 *    答案保存为服务器草稿；倒计时归零还会触发交卷（第 3 片），与手动交卷走同一个
 *    `submitExam`——但那仍是体验层配合，超时收卷由后端定时扫描从草稿取答案兜底
 *    （`ExamSweepService`），前端不判定超时、自动交卷失败也不重试风暴。
 */
import { Alert, Button, Card, Spin } from 'ant-design-vue';
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import {
  enter as enterContract,
  reportBehavior as reportBehaviorContract,
  saveDraft as saveDraftContract,
  submit as submitContract,
  type BehaviorReportResponse,
  type SubmitResponse,
  type AutoSaveResponse,
  type EnterExamResponse,
  type QuestionView,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import ExamCountdown from '@/components/student/ExamCountdown.vue';
import TakingBoard from '@/components/student/TakingBoard.vue';
import DraftSyncBadge from '@/components/student/DraftSyncBadge.vue';
import SubmitExamPanel from '@/components/student/SubmitExamPanel.vue';
import SubmitResultCard from '@/components/student/SubmitResultCard.vue';
import { useServerCountdown } from '@/hooks/useServerCountdown';
import { useAutoSaveDraft } from '@/hooks/useAutoSaveDraft';
import { useSubmitExam } from '@/hooks/useSubmitExam';
import { useBehaviorReport } from '@/hooks/useBehaviorReport';
import { createEnterExamQueryOptions, examIdOf, isClosedByBackend } from '@/hooks/useStudentTaking';
import { SUBMIT_TYPE } from '@/constants/studentTaking';
import { ApiError } from '@/api/types';
import { answerMapOf, navStatesOf, type AnswerMap } from '@/utils/studentTaking';
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

/**
 * 交卷引擎（第 3 片）。手动交卷与倒计时归零自动交卷共用唯一的 `submitExam` 入口
 * （硬约定 4），submitType 只区分来源不改变路径。答案所有权仍在页面 `answers`：
 * hook 失败时不清空、不改写，重试直接用当前作答状态。
 *
 * 重复交卷的语义来自后端（已核实 `ExamSubmitService`）：**没有专门的重复错误码**——
 * 幂等快速路径直接返回首次结果；只有 SETNX 锁竞争 2 秒未收敛才抛 STATE_CONFLICT(1012)
 * （「正在提交中，请稍候重试」），前端按码提示、不自行判「已交过」。
 */
const submitHook = useSubmitExam({
  examId: () => examId.value,
  answersSource: () => answers.value,
  suspended: () => closedByBackend.value || !examIdValid.value,
  deps: {
    submit: (id, body) =>
      unwrap<SubmitResponse>(
        submitContract({ client, throwOnError: true, path: { examId: id }, body })
      ),
  },
});

/**
 * 切屏 / 失焦上报（第 3 片）：一次离开一条（归并在 `reduceBehaviorSignal` 纯函数里），
 * 事件类型只有后端注册过的 SWITCH_SCREEN / WINDOW_BLUR，severity 由后端策略判定。
 * 上报失败静默旁路——行为采集绝不打断作答（与后端采集核心同构的取舍）。
 */
const behavior = useBehaviorReport({
  examId: () => examId.value,
  suspended: () => closedByBackend.value || !examIdValid.value,
  deps: {
    report: (id, body) =>
      unwrap<BehaviorReportResponse>(
        reportBehaviorContract({ client, throwOnError: true, path: { examId: id }, body })
      ),
  },
});

const behaviorWarning = behavior.warning;

/** 警告描述：只复述后端给的字段，不加工不放大。 */
const behaviorDescription = computed<string | undefined>(() => {
  const w = behaviorWarning.value;
  if (!w) return undefined;
  const parts: string[] = [];
  if (w.count !== null && w.count !== undefined) parts.push(`本次考试已记录 ${w.count} 次`);
  if (w.severityName) parts.push(`严重度：${w.severityName}（由系统判定）`);
  return parts.join('；') || '该事件已记录，由教师事后依据行为日志判定是否处置。';
});

const autoSave = useAutoSaveDraft({
  examId: () => examId.value,
  answersSource: () => answers.value,
  locked: () => countdown.isExpired.value,
  // 后端已封闭（已交卷/已收卷）、交卷已成功、或 ID 非法 → 一切保存动作挂空挡
  //（交卷成功后后端不再受理草稿，继续保存只会制造无意义的拒绝）
  suspended: () =>
    closedByBackend.value || !examIdValid.value || submitHook.phase.value === 'submitted',
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

/** 未答题数：与导航面板同一份 `navStatesOf`（硬约定：两处数字不许各说各话）。 */
const unansweredCount = computed(
  () => navStatesOf(questions.value, answers.value, -1).filter((s) => !s.answered).length
);

/**
 * 交卷面板可见性：快照在手、答卷未封闭、交卷未成功。已交卷重进时后端把
 * questions 置空，面板自然不出现（结果卡接管）。
 */
const submitEnabled = computed(
  () => Boolean(snapshot.value) && !closedByBackend.value && submitHook.phase.value !== 'submitted'
);

/**
 * 结果卡内容：交卷成功用后端 SubmitResponse；重进已封闭答卷时用快照里的
 * submissionId / status=2 拼（后端进入接口对已交卷答卷只回这些字段，已核实）。
 */
const submitResultView = computed<SubmitResponse | null>(() => {
  if (submitHook.phase.value === 'submitted' && submitHook.result.value) {
    return submitHook.result.value;
  }
  if (closedByBackend.value && snapshot.value?.submissionId !== undefined) {
    return {
      submissionId: snapshot.value.submissionId,
      examId: snapshot.value.examId,
      status: 2,
    };
  }
  return null;
});

/** 手动交卷：先把未上报的切屏暂存兜底 flush（时长未知场景），再走唯一提交入口。 */
function onManualSubmit(): void {
  void behavior.flush();
  void submitHook.submitExam(SUBMIT_TYPE.MANUAL);
}

/**
 * 倒计时归零自动交卷（第 3 片）：与手动交卷**同一个** `submitExam`（硬约定 4），
 * 仅 submitType 不同。这条只是体验层的主动配合——**最终超时判定在后端**
 * （`ExamSweepService` 定时扫描 + `ExamTakingService.buildAnsweringContext` 就地兜底）：
 * 自动交卷因断线失败时，答案在 Redis 草稿里，后端扫描照常从草稿收卷，前端不重试风暴。
 */
watch(
  () => [countdown.isExpired.value, closedByBackend.value, examIdValid.value] as const,
  ([expired, closed, valid]) => {
    if (expired && !closed && valid) {
      void behavior.flush(); // 归零交卷前兜底 flush 切屏暂存（suspended 置真后 hook 内部还会再兜一层）
      void submitHook.submitExam(SUBMIT_TYPE.COUNTDOWN_ZERO);
    }
  },
  { immediate: true }
);

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
