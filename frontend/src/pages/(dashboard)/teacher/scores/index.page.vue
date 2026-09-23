<template>
  <div>
    <Card title="成绩管理与发布" class="mb-4">
      <template #extra>
        <span class="hint">
          动作可用性全部由后端考试状态与角色决定：汇总仅限已结束且未发布，发布仅限已批改，撤回仅限管理员。
        </span>
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
        <Tag v-if="selectedExam" :color="statusTagColor">
          {{ getExamStatusConfig((selectedExam.status ?? -1) as ExamStatus).label }}
        </Tag>
        <Tag v-if="!isAdmin" color="default">当前角色非管理员：撤回入口不可用</Tag>
      </div>

      <div class="mb-3 flex flex-wrap items-center gap-2">
        <!-- 以下按钮全部按 scoreActions（后端状态 + 角色）渲染，后端未允许即不显示 -->
        <Button
          v-if="actions.canSummarize"
          type="primary"
          :loading="summarizing"
          @click="onSummarize"
        >
          汇总成绩
        </Button>
        <Button v-if="actions.canPreview" :loading="previewing" @click="onPreview">
          发布前预览
        </Button>
        <Button
          v-if="actions.canPublish"
          type="primary"
          danger
          :loading="publishing"
          @click="publishModalOpen = true"
        >
          发布成绩
        </Button>
        <Button v-if="actions.canRevoke" danger :loading="revoking" @click="revokeModalOpen = true">
          撤回成绩
        </Button>
        <span
          v-if="selectedExam && !actions.canSummarize && !actions.canPublish && !actions.canRevoke"
          class="no-action"
        >
          当前状态（{{
            getExamStatusConfig((selectedExam.status ?? -1) as ExamStatus).label
          }}）下后端不允许任何成绩动作
        </span>
      </div>

      <div v-if="selectedExam">
        <Divider orientation="left" plain>成绩导出（后端流式生成，前端仅下载）</Divider>
        <ExportScoreButtons
          :exam-id="selectedExamId as number"
          :exam-title="selectedExam.title"
          :enabled="actions.canExport"
        />
      </div>
    </Card>

    <Card v-if="summarizeStats" title="汇总结果" class="mb-4">
      <Statistic title="本次汇总" :value="summarizeStats.summarized ?? 0" suffix="人" />
      <Statistic title="跳过（已汇总过）" :value="summarizeStats.skipped ?? 0" suffix="人" />
      <p class="stat-note">
        examGraded={{ summarizeStats.examGraded === true ? 'true' : 'false' }}
        （后端判定是否已全部批改，仅该值为 true 时才允许发布）
      </p>
    </Card>

    <Card
      v-if="preview"
      :title="`发布前预览：${preview.examTitle ?? selectedExam?.title ?? ''}`"
      class="mb-4"
    >
      <ScorePublishPreview
        :exam-id="selectedExamId as number"
        :exam-title="preview.examTitle ?? selectedExam?.title"
        :preview="preview"
      />
    </Card>

    <Card v-if="actionResults.length > 0" title="批量操作结果（后端逐场返回）" class="mb-4">
      <Table
        :columns="resultColumns"
        :data-source="actionResults"
        :pagination="false"
        :row-key="(row: ScoreActionItem) => row.examId as number"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'success'">
            <Tag :color="(record as ScoreActionItem).success ? 'green' : 'red'">
              {{ (record as ScoreActionItem).success ? '成功' : '失败' }}
            </Tag>
          </template>
        </template>
      </Table>
    </Card>

    <Modal
      v-model:open="publishModalOpen"
      title="确认发布成绩"
      :confirm-loading="publishing"
      @ok="onPublish"
    >
      <!-- ⚠️ ant-design-vue 4 的 Alert 只渲染 message/description，正文必须走具名插槽
           （默认插槽会被静默丢弃） -->
      <Alert type="warning" show-icon>
        <template #message>
          <ul class="confirm-list">
            <li>仅「已批改」状态的考试允许发布（后端状态机裁决）</li>
            <li>发布后学生端立即可见，且汇总会被锁定（需撤回后重新汇总）</li>
            <li>重复发布同一场属幂等操作，后端跳过而非报错</li>
          </ul>
        </template>
      </Alert>
    </Modal>

    <Modal
      v-model:open="revokeModalOpen"
      title="撤回成绩（管理员）"
      :confirm-loading="revoking"
      @ok="onRevoke"
    >
      <Alert type="warning" show-icon message="撤回原因必填，将写入审计日志（后端强制校验）" />
      <Input
        v-model:value="revokeReason"
        class="revoke-input"
        placeholder="撤回原因（必填，如：主观题漏批，需重新批改）"
      />
    </Modal>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  Divider,
  Input,
  Modal,
  Select,
  Statistic,
  Table,
  Tag,
  message,
  type TableColumnsType,
} from 'ant-design-vue';
import { computed, ref } from 'vue';
import { useStore } from 'vuex';
import { useQuery } from '@tanstack/vue-query';

import {
  page2 as pageExams,
  publish,
  publishPreview,
  revoke,
  summarize,
  type ExamResponse,
  type ScoreActionItem,
  type ScorePreviewResponse,
  type SummarizeStats,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';
import { getExamStatusConfig, type ExamStatus } from '@/constants/examStatus';
import { highestRoleOf, type State } from '@/store';
import { resolveScoreActions } from '@/utils/scoreActions';
import ExportScoreButtons from '@/components/postexam/ExportScoreButtons.vue';
import ScorePublishPreview from '@/components/postexam/ScorePublishPreview.vue';

/**
 * 成绩汇总 / 发布 / 撤回 / 导出（阶段 23）。
 * 后端契约：
 * - POST /api/exams/{examId}/scores/summarize（exam:manage，≥ ENDED 且 ≠ PUBLISHED）
 * - GET  /api/exams/{examId}/scores/publish-preview（exam:manage，≥ GRADED）
 * - POST /api/scores/publish（exam:manage，GRADED → PUBLISHED，部分成功语义）
 * - POST /api/scores/revoke（@RequireRole(ADMIN) + assertAdmin，原因必填，PUBLISHED → GRADED）
 * 动作入口一律走 scoreActions 纯函数映射，前端不自行推算状态机、不自行放宽撤回权限。
 */

const PAGE_SIZE = 50;

const resultColumns: TableColumnsType = [
  { title: '考试 ID', key: 'examId', dataIndex: 'examId', width: 100 },
  { title: '结果', key: 'success', width: 90 },
  { title: '后端说明', key: 'message', dataIndex: 'message' },
];

const store = useStore<State>();
// 撤回是管理员专属动作（后端 @RequireRole(ADMIN)）：角色取自 /api/auth/me，前端不做本地缓存推断
const isAdmin = computed(() => highestRoleOf(store.state.user) === 'ADMIN');

const examPage = ref(1);
const { data: examsData, isFetching: examsFetching } = useQuery({
  queryKey: computed(() => ['exams', 'scores', examPage.value] as const),
  queryFn: () =>
    unwrap<ExamResponse[]>(
      pageExams({ client, throwOnError: true, query: { page: examPage.value, size: PAGE_SIZE } })
    ),
});
const exams = computed<ExamResponse[]>(() => examsData.value ?? []);

const examOptions = computed(() =>
  exams.value.map((e) => ({ value: e.id as number, label: `#${e.id} ${e.title}` }))
);

function examFilterOption(input: string, option?: unknown): boolean {
  const label = (option as { label?: unknown } | undefined)?.label;
  return String(label ?? '')
    .toLowerCase()
    .includes(input.toLowerCase());
}

const selectedExamId = ref<number | undefined>(undefined);
const selectedExam = computed(() => exams.value.find((e) => e.id === selectedExamId.value));
const actions = computed(() => resolveScoreActions(selectedExam.value?.status, isAdmin.value));
const statusTagColor = computed(
  () => getExamStatusConfig((selectedExam.value?.status ?? -1) as ExamStatus).color
);

// ===== 汇总 =====
const summarizing = ref(false);
const summarizeStats = ref<SummarizeStats | null>(null);

async function onSummarize(): Promise<void> {
  if (selectedExamId.value === undefined) return;
  summarizing.value = true;
  try {
    summarizeStats.value =
      (await unwrap(
        summarize({ client, throwOnError: true, path: { examId: selectedExamId.value } })
      )) ?? null;
    message.success('汇总完成');
    void queryClient.invalidateQueries({ queryKey: ['exams'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '汇总失败');
  } finally {
    summarizing.value = false;
  }
}

// ===== 发布前预览 =====
const previewing = ref(false);
const preview = ref<ScorePreviewResponse | null>(null);

async function onPreview(): Promise<void> {
  if (selectedExamId.value === undefined) return;
  previewing.value = true;
  try {
    preview.value =
      (await unwrap(
        publishPreview({ client, throwOnError: true, path: { examId: selectedExamId.value } })
      )) ?? null;
  } catch (error) {
    preview.value = null;
    message.error(error instanceof Error ? error.message : '预览失败');
  } finally {
    previewing.value = false;
  }
}

// ===== 发布 / 撤回（批量，逐场结果由后端返回）=====
const publishing = ref(false);
const revoking = ref(false);
const publishModalOpen = ref(false);
const revokeModalOpen = ref(false);
const revokeReason = ref('');
const actionResults = ref<ScoreActionItem[]>([]);

async function onPublish(): Promise<void> {
  if (selectedExamId.value === undefined) return;
  publishing.value = true;
  try {
    const results =
      (await unwrap(
        publish({ client, throwOnError: true, body: { examIds: [selectedExamId.value] } })
      )) ?? [];
    actionResults.value = results;
    const failed = results.filter((r) => !r.success);
    if (failed.length === 0) {
      message.success('成绩已发布，学生端可见');
      publishModalOpen.value = false;
    } else {
      message.warning(`发布结果含 ${failed.length} 场失败，详见结果表（后端逐场返回）`);
    }
    void queryClient.invalidateQueries({ queryKey: ['exams'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '发布失败');
  } finally {
    publishing.value = false;
  }
}

async function onRevoke(): Promise<void> {
  if (selectedExamId.value === undefined) return;
  if (!revokeReason.value.trim()) {
    message.warning('撤回原因必填（后端审计要求）');
    return;
  }
  revoking.value = true;
  try {
    const results =
      (await unwrap(
        revoke({
          client,
          throwOnError: true,
          body: { examIds: [selectedExamId.value], reason: revokeReason.value.trim() },
        })
      )) ?? [];
    actionResults.value = results;
    const failed = results.filter((r) => !r.success);
    if (failed.length === 0) {
      message.success('成绩已撤回，学生端恢复为「未发布」');
      revokeModalOpen.value = false;
      revokeReason.value = '';
    } else {
      message.warning(`撤回结果含 ${failed.length} 场失败，详见结果表`);
    }
    void queryClient.invalidateQueries({ queryKey: ['exams'] });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '撤回失败');
  } finally {
    revoking.value = false;
  }
}
</script>

<style scoped>
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
  max-width: 460px;
}
.no-action {
  color: #999;
  font-size: 13px;
}
.confirm-list {
  margin: 0;
  padding-left: 18px;
}
.revoke-input {
  margin-top: 8px;
}
.stat-note {
  margin-top: 8px;
  color: #999;
  font-size: 12px;
}
</style>
