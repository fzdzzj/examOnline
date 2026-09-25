<template>
  <div>
    <Card title="我的成绩与复核" class="mb-4">
      <template #extra>
        <span class="hint">成绩可见性与复核资格一律以后端返回为准，前端不做本地判定。</span>
      </template>

      <div class="mb-3 flex flex-wrap items-center gap-3">
        <Select
          v-model:value="selectedExamId"
          :options="examOptions"
          placeholder="选择考试"
          class="w-96"
          show-search
          :filter-option="examFilterOption"
          :loading="examsFetching"
        />
        <RadioGroup v-model:value="scoreMode">
          <Radio value="regular">常规成绩</Radio>
          <Radio value="makeup">补考最终成绩</Radio>
        </RadioGroup>
        <span v-if="scoreMode === 'makeup'" class="hint">
          后端沿主考家族按考试配置规则合并，历史成绩保留不覆盖；前端只渲染返回值。
        </span>
      </div>

      <!-- 接口缺口如实告知：学生端没有「查询自己复核申请」的端点 -->
      <Alert type="info" show-icon class="mb-3">
        <template #description>
          复核资格（剩余次数 / 时间窗）由后端在提交时校验拒绝，后端
          <b>没有单独的资格查询端点</b>
          ，因此本页不展示「剩余次数」——前端不本地计数（两端规则漂移会导致资格判定失真）。
          同理，学生查询本人复核申请列表的端点目前也不存在，申请结果以成绩卡片状态与后端返回为准。
        </template>
      </Alert>

      <Spin :spinning="scoreFetching">
        <ScoreVisibilityCard :view="view" :variant="scoreMode === 'makeup' ? 'final' : 'detail'" />
      </Spin>

      <div v-if="view.kind === 'reviewing'" class="mt-3">
        <!-- 复核中不再重复申请：后端会对重复申请返回 1001，此处按钮由后端 reviewing 字段驱动 -->
        <span class="pending-hint">复核进行中，处理完成后成绩恢复显示</span>
      </div>
      <div v-else-if="view.kind === 'published' && scoreMode === 'regular'" class="mt-3">
        <Button
          type="primary"
          :disabled="appliedExamIds.includes(selectedExamId as number)"
          @click="applyModalOpen = true"
        >
          申请成绩复核
        </Button>
      </div>

      <Alert
        v-if="applyError"
        type="error"
        show-icon
        class="mt-3"
        :message="applyError"
        closable
        @close="applyError = null"
      />

      <Alert
        v-if="appliedReview"
        type="success"
        show-icon
        class="mt-3"
        message="复核申请已提交（待教师处理）"
      >
        <template #description>
          申请理由：{{ appliedReview.reason || '（未填写）' }} · 后端返回状态：
          {{ getReviewStatusConfig(appliedReview.status ?? -1).label }}
        </template>
      </Alert>
    </Card>

    <Modal
      v-model:open="applyModalOpen"
      title="申请成绩复核"
      :confirm-loading="applying"
      @ok="onApply"
    >
      <!-- ⚠️ ant-design-vue 4 的 Alert 只渲染 message/description，正文必须走具名插槽
           （默认插槽会被静默丢弃） -->
      <Alert type="warning" show-icon class="mb-2">
        <template #message>
          复核次数与时间窗限制由后端在提交时校验（超窗 / 重复申请会被后端拒绝并给出原因），
          前端不预先展示资格计数。
        </template>
      </Alert>
      <Textarea
        v-model:value="applyReason"
        :rows="4"
        placeholder="申请理由（说明存疑的题目或分数）"
      />
    </Modal>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  Modal,
  Radio,
  RadioGroup,
  Select,
  Spin,
  Textarea,
  message,
} from 'ant-design-vue';
import { computed, ref } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import {
  apply,
  myExams,
  myMakeupFinalScore,
  myScore,
  type ExamListItem,
  type MakeupFinalScoreResponse,
  type MyScoreResponse,
  type ScoreReview,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { getReviewStatusConfig } from '@/constants/postExam';
import {
  mapMakeupFinalScoreToView,
  mapMyScoreToView,
  isNotPublishedError,
} from '@/utils/scoreVisibility';
import ScoreVisibilityCard from '@/components/postexam/ScoreVisibilityCard.vue';

/**
 * 学生成绩查询与复核申请（阶段 23，补考最终成绩见 add-makeup-final-score-frontend）：
 * - GET /api/exam-taking/exams 我的考试列表；
 * - GET /api/scores/my?examId= 成绩（未发布 → 后端 400「成绩待发布」；复核中 → reviewing=true 且分数置空）；
 * - GET /api/scores/makeup-final?examId= 补考最终成绩（学生查本人，口径同 myScore）：
 *   未发布同样统一「成绩待发布」（后端 requirePublishedRoot）；主考家族存在进行中复核时
 *   reviewing=true 且 finalScore 置 null（防「看了分数再申请」），前端只看 reviewing 字段
 *   隐藏分数、不本地推断；合并规则在后端（沿主考家族按考试配置规则），前端只渲染返回值。
 *   教师侧对端点为 GET /api/exams/{examId}/scores/makeup-final/{studentId}（exam:manage，
 *   teacher/makeups 页使用），学生无此权限、本页不调用；
 * - POST /api/exams/{examId}/score-reviews 申请复核（限次限时由后端校验，前端不计数）。
 */

const { data: examsData, isFetching: examsFetching } = useQuery({
  queryKey: ['my-exams'] as const,
  queryFn: () => unwrap<ExamListItem[]>(myExams({ client, throwOnError: true })),
});
const exams = computed<ExamListItem[]>(() => examsData.value ?? []);
const examOptions = computed(() =>
  exams.value.map((e) => ({ value: e.examId as number, label: `${e.title}（#${e.examId}）` }))
);
function examFilterOption(input: string, option?: unknown): boolean {
  const label = (option as { label?: unknown } | undefined)?.label;
  return String(label ?? '')
    .toLowerCase()
    .includes(input.toLowerCase());
}

const selectedExamId = ref<number | undefined>(undefined);

// 成绩口径：常规成绩（myScore）/ 补考最终成绩（makeup-final）。
// 考试列表不携带补考标记（ExamListItem 无 parentExamId），前端不猜哪场是补考，
// 由学生显式选择口径——可见性裁决仍 100% 在后端。
const scoreMode = ref<'regular' | 'makeup'>('regular');

const { data: scoreData, isFetching: myScoreFetching } = useQuery({
  queryKey: computed(() => ['my-score', selectedExamId.value] as const),
  queryFn: async () => {
    try {
      return (
        (await unwrap<MyScoreResponse>(
          myScore({ client, throwOnError: true, query: { examId: selectedExamId.value as number } })
        )) ?? null
      );
    } catch (error) {
      // 未发布是后端的正常业务状态（400「成绩待发布」），不是异常：映射为 not-published
      if (isNotPublishedError(error)) return null;
      throw error;
    }
  },
  enabled: computed(() => scoreMode.value === 'regular' && selectedExamId.value !== undefined),
  retry: false,
});

const { data: makeupData, isFetching: makeupFetching } = useQuery({
  queryKey: computed(() => ['my-makeup-final', selectedExamId.value] as const),
  queryFn: async () => {
    try {
      return (
        (await unwrap<MakeupFinalScoreResponse>(
          myMakeupFinalScore({
            client,
            throwOnError: true,
            query: { examId: selectedExamId.value as number },
          })
        )) ?? null
      );
    } catch (error) {
      // 口径同 myScore：未发布（400「成绩待发布」）→ not-published，不当异常弹给用户
      if (isNotPublishedError(error)) return null;
      throw error;
    }
  },
  enabled: computed(() => scoreMode.value === 'makeup' && selectedExamId.value !== undefined),
  retry: false,
});

// 三态映射：未发布（响应为空）/ 复核中（reviewing）/ 已发布——两种口径共用同一套 ScoreView
const view = computed(() =>
  scoreMode.value === 'makeup'
    ? mapMakeupFinalScoreToView(makeupData.value ?? undefined)
    : mapMyScoreToView(scoreData.value ?? undefined)
);
const scoreFetching = computed(() =>
  scoreMode.value === 'makeup' ? makeupFetching.value : myScoreFetching.value
);

// ===== 复核申请 =====
const applyModalOpen = ref(false);
const applyReason = ref('');
const applying = ref(false);
const applyError = ref<string | null>(null);
const appliedReview = ref<ScoreReview | null>(null);
const appliedExamIds = ref<number[]>([]);

async function onApply(): Promise<void> {
  if (selectedExamId.value === undefined) return;
  applying.value = true;
  applyError.value = null;
  try {
    appliedReview.value =
      (await unwrap(
        apply({
          client,
          throwOnError: true,
          path: { examId: selectedExamId.value },
          body: { reason: applyReason.value.trim() || undefined },
        })
      )) ?? null;
    appliedExamIds.value = [...appliedExamIds.value, selectedExamId.value];
    applyModalOpen.value = false;
    message.success('复核申请已提交，等待教师处理');
  } catch (error) {
    // 限次/超窗/重复申请一律以后端拒绝文案为准（如 400 超窗、1001 已申请过）
    applyError.value = error instanceof Error ? error.message : '申请失败，请稍后重试';
  } finally {
    applying.value = false;
  }
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
.mt-3 {
  margin-top: 0.75rem;
}
.hint {
  color: #999;
  font-size: 12px;
  font-weight: normal;
}
.pending-hint {
  color: #999;
  font-size: 13px;
}
</style>
