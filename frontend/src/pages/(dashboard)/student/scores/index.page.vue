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

      <!-- U-1 三态分离：查询失败（非「成绩待发布」）显性呈现，不伪装成「成绩未发布」业务态
           （形态先例：StudentExamList「空态与错误态分开……真相是系统坏了」） -->
      <Alert
        v-if="queryError"
        type="error"
        show-icon
        class="mb-3"
        :message="queryError"
        data-test="scores-error"
      />

      <Spin :spinning="scoreFetching">
        <ScoreVisibilityCard
          v-if="!queryError"
          :view="view"
          :variant="scoreMode === 'makeup' ? 'final' : 'detail'"
        />
      </Spin>

      <div v-if="view.kind === 'reviewing'" class="mt-3">
        <!-- 复核中不再重复申请：后端会对重复申请返回 1001，此处按钮由后端 reviewing 字段驱动 -->
        <span class="pending-hint">复核进行中，处理完成后成绩恢复显示</span>
      </div>
      <div
        v-else-if="view.kind === 'published' && scoreMode === 'regular'"
        class="mt-3 flex items-center gap-3"
      >
        <Button type="primary" data-test="review-exam-btn" @click="openReviewModal">
          逐题回顾
        </Button>
        <Button
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

    <!-- 单场考试逐题回顾 Modal -->
    <Modal
      v-model:open="reviewModalOpen"
      :title="`${reviewData?.examTitle || '考试'} - 逐题回顾与解析`"
      width="800px"
      :footer="null"
    >
      <Alert v-if="reviewError" type="error" show-icon class="mb-3" :message="reviewError" />
      <Spin :spinning="reviewFetching">
        <div v-if="reviewData" class="space-y-4 max-h-[70vh] overflow-y-auto pr-2">
          <!-- 总体成绩概览 -->
          <div
            class="bg-blue-50/50 p-3 rounded border border-blue-100 flex flex-wrap gap-4 text-sm"
          >
            <div>
              考生姓名：
              <span class="font-medium">{{ reviewData.studentName }}</span>
            </div>
            <div>
              总分：
              <span class="font-semibold text-blue-600">{{ reviewData.totalScore }}</span>
            </div>
            <div>客观题：{{ reviewData.objectiveScore }}</div>
            <div>主观题：{{ reviewData.subjectiveScore }}</div>
            <div>全班排名：第 {{ reviewData.rank }} 名</div>
            <div>
              批改状态：
              <Tag :color="reviewData.partialGraded ? 'warning' : 'green'">
                {{ reviewData.partialGraded ? '部分批改' : '已全部批改' }}
              </Tag>
            </div>
          </div>

          <!-- 逐题列表 -->
          <div
            v-for="q in reviewData.questions || []"
            :key="q.questionId"
            class="p-3 border rounded bg-white space-y-2"
          >
            <div class="flex items-center justify-between">
              <div class="flex items-center gap-2">
                <span class="font-semibold">第 {{ q.questionNumber }} 题</span>
                <Tag color="cyan">{{ q.questionType }}</Tag>
                <Tag
                  :color="
                    q.myScore === q.fullScore ? 'success' : q.myScore === 0 ? 'error' : 'warning'
                  "
                >
                  {{ q.myScore }} / {{ q.fullScore }} 分
                </Tag>
              </div>
              <Tag v-if="!q.graded" color="default">未批</Tag>
            </div>

            <!-- 题干 -->
            <div class="text-gray-800 font-medium whitespace-pre-wrap">{{ q.questionContent }}</div>

            <!-- 选项 -->
            <div
              v-if="q.choices && q.choices.length > 0"
              class="pl-2 space-y-1 text-sm text-gray-600"
            >
              <div v-for="(choice, idx) in q.choices" :key="idx">
                {{ String.fromCharCode(65 + idx) }}. {{ choice }}
              </div>
            </div>

            <!-- 作答与标准答案 -->
            <div class="bg-gray-50 p-2 rounded text-sm grid grid-cols-1 md:grid-cols-2 gap-2">
              <div>
                我的作答：
                <span class="font-medium text-blue-600">
                  {{ q.myAnswer ? q.myAnswer : '（未作答）' }}
                </span>
              </div>
              <div>
                正确答案：
                <span class="font-medium text-green-600">
                  {{ q.correctAnswer ? q.correctAnswer : '（无）' }}
                </span>
              </div>
            </div>

            <!-- 题目解析 -->
            <div class="text-sm bg-blue-50/50 p-2 rounded border border-blue-100">
              <span class="font-semibold text-blue-800">题目解析：</span>
              <span v-if="q.analysis" class="text-gray-700 whitespace-pre-wrap">
                {{ q.analysis }}
              </span>
              <span v-else class="text-gray-400 italic">暂无解析</span>
            </div>

            <!-- 教师评语或判分依据 -->
            <div v-if="q.comment || q.scoreDetail" class="text-xs text-gray-500">
              <span v-if="q.comment">评语：{{ q.comment }}</span>
              <span v-if="q.scoreDetail">依据：{{ q.scoreDetail }}</span>
            </div>
          </div>
        </div>
      </Spin>
    </Modal>

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
  Tag,
  Textarea,
  message,
} from 'ant-design-vue';
import { computed, ref } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import {
  apply,
  myExamReview,
  myExams,
  myMakeupFinalScore,
  myScore,
  type ExamListItem,
  type ExamReviewResponse,
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

const {
  data: scoreData,
  isFetching: myScoreFetching,
  error: scoreError,
} = useQuery({
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

const {
  data: makeupData,
  isFetching: makeupFetching,
  error: makeupError,
} = useQuery({
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

// ===== U-1 三态分离：失败态先于业务映射裁决 =====
// queryFn 已把「成绩待发布」归一为 null（not-published 业务态），能到 error 里的都是真失败；
// 对 error 再判别一次 isNotPublishedError 是防回归双保险——判别入口仍是 scoreVisibility.ts
// 的唯一函数（后端 400 固定文案），不放宽、不新增第二套本地推断。
const scoreQueryError = computed<string | null>(() => {
  const caught = scoreError.value;
  if (!caught || isNotPublishedError(caught)) return null;
  return caught instanceof Error ? caught.message : '成绩查询失败';
});
const makeupQueryError = computed<string | null>(() => {
  const caught = makeupError.value;
  if (!caught || isNotPublishedError(caught)) return null;
  return caught instanceof Error ? caught.message : '补考最终成绩查询失败';
});
const queryError = computed<string | null>(() =>
  scoreMode.value === 'makeup' ? makeupQueryError.value : scoreQueryError.value
);

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

// ===== 单场考试逐题回顾 =====
const reviewModalOpen = ref(false);

const {
  data: reviewData,
  isFetching: reviewFetching,
  error: reviewQueryErrorCaught,
  refetch: refetchReview,
} = useQuery({
  queryKey: computed(() => ['my-exam-review', selectedExamId.value] as const),
  queryFn: () =>
    unwrap<ExamReviewResponse>(
      myExamReview({
        client,
        throwOnError: true,
        path: { examId: selectedExamId.value as number },
      })
    ),
  enabled: computed(() => reviewModalOpen.value && selectedExamId.value !== undefined),
});

const reviewError = computed<string | null>(() => {
  const caught = reviewQueryErrorCaught.value;
  if (!caught) return null;
  return caught instanceof Error ? caught.message : '逐题回顾加载失败';
});

function openReviewModal(): void {
  reviewModalOpen.value = true;
  void refetchReview();
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
