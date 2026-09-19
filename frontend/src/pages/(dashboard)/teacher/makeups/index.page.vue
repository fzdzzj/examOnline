<template>
  <div>
    <Card title="补考管理" class="mb-4">
      <template #extra>
        <span class="hint">
          补考是独立考试记录，通过 parent_exam_id 关联主考，准入范围写入 exam_candidates。
        </span>
      </template>

      <!-- 诚实边界：后端 MakeupScoreService.finalScore 全仓库零调用，不存在「补考最终成绩」接口 -->
      <Alert type="warning" show-icon class="mb-3">
        <template #message>补考最终成绩合并规则：后端尚未接线</template>
        <template #description>
          <code>MakeupScoreService.finalScore</code>
          当前没有任何 Controller / Service 调用（遗留 #5），因此本页
          <b>只做补考的创建与准入管理</b>
          ， 不展示「主考 vs 补考合并后的最终成绩」，也不声称该能力可用。需要该展示须先单独立项
          <code>add-makeup-final-score</code>
          （后端功能变更）。
        </template>
      </Alert>

      <div class="mb-3 flex flex-wrap items-center gap-3">
        <Select
          v-model:value="selectedExamId"
          :options="examOptions"
          placeholder="选择主考考试"
          class="w-96"
          show-search
          :filter-option="examFilterOption"
          :loading="examsFetching"
        />
        <span class="pass-line">
          及格线
          <InputNumber v-model:value="passLine" :min="0" :max="150" class="pass-input" />
        </span>
        <Button :loading="eligibleFetching" @click="() => void refetchEligible()">
          查询补考候选人
        </Button>
      </div>
    </Card>

    <Card title="补考候选人（后端按及格线判定）" class="mb-4">
      <Table
        :columns="candidateColumns"
        :data-source="candidates"
        :loading="eligibleFetching"
        :row-selection="rowSelection"
        :pagination="{ pageSize: 10, showSizeChanger: false }"
        :row-key="(row: MakeupCandidateItem) => row.studentId as number"
        size="middle"
      >
        <template #emptyText>请先选择主考考试并点击「查询补考候选人」</template>
      </Table>
      <p class="reason-note">
        候选人来自后端
        <code>GET /api/exams/{id}/makeup-eligible?passLine=</code>
        ，判定口径（缺考 / 未达及格线）由后端 reason 字段给出，前端不自行筛选。
      </p>
    </Card>

    <Card title="创建补考">
      <Form :label-col="{ span: 5 }" :wrapper-col="{ span: 14 }">
        <FormItem label="补考标题" v-bind="titleValidate">
          <Input v-model:value="form.title" placeholder="如：高一数学期末补考" />
        </FormItem>
        <FormItem label="开始时间" required>
          <Input v-model:value="form.startTime" placeholder="2026-09-20 09:00:00" />
        </FormItem>
        <FormItem label="结束时间" required>
          <Input v-model:value="form.endTime" placeholder="2026-09-20 11:00:00" />
        </FormItem>
        <FormItem label="考试时长(分钟)" required>
          <InputNumber v-model:value="form.durationMinutes" :min="1" :max="600" />
        </FormItem>
        <FormItem label="允许迟到(分钟)">
          <InputNumber v-model:value="form.allowLateMinutes" :min="0" :max="60" />
        </FormItem>
        <FormItem label="成绩规则">
          <Select v-model:value="form.makeupScoreRule" :options="ruleOptions" class="w-80" />
        </FormItem>
        <FormItem label="准入学生">
          <div class="selected-students">
            <Tag v-for="id in selectedIds" :key="id" color="blue">
              {{ id }} {{ studentNameOf(id) }}
            </Tag>
            <span v-if="selectedIds.length === 0" class="empty-hint">
              未选择（后端要求 studentIds 必填）
            </span>
          </div>
        </FormItem>
        <FormItem :wrapper-col="{ offset: 5, span: 14 }">
          <Button type="primary" :loading="creating" @click="onCreate">创建补考</Button>
        </FormItem>
      </Form>
    </Card>

    <Card v-if="created" title="创建结果" class="mt-4">
      <Descriptions bordered :column="1">
        <DescriptionsItem label="补考考试 ID">{{ created.examId }}</DescriptionsItem>
        <DescriptionsItem label="标题">{{ created.title }}</DescriptionsItem>
        <DescriptionsItem label="关联主考 (parent_exam_id)">
          {{ created.parentExamId }}
        </DescriptionsItem>
        <DescriptionsItem label="成绩规则">
          {{ makeupRuleLabel(created.makeupScoreRule) }}
        </DescriptionsItem>
        <DescriptionsItem label="准入人数 (exam_candidates)">
          {{ created.candidateCount }}
        </DescriptionsItem>
      </Descriptions>
      <Alert
        class="mt-2"
        type="info"
        show-icon
        message="补考已成为独立考试记录，可在「考试管理」中查看与发布；最终成绩合并规则后端未接线，故此处不展示 merged 成绩。"
      />
    </Card>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  Input,
  InputNumber,
  Select,
  Table,
  Tag,
  message,
  type TableColumnsType,
  type TableProps,
} from 'ant-design-vue';
import { computed, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import {
  createMakeup,
  makeupEligible,
  page2 as pageExams,
  type ExamResponse,
  type MakeupCandidateItem,
  type MakeupCreateResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { MAKEUP_RULE_OPTIONS, makeupRuleLabel } from '@/constants/postExam';

/**
 * 补考管理（阶段 23）：
 * - GET /api/exams/{id}/makeup-eligible?passLine= 候选名单（后端按及格线判定，reason 由后端给出）
 * - POST /api/exams/{id}/makeups 创建补考（独立考试记录 + parentExamId + exam_candidates 准入）
 * 硬约定 5：不做「主考 vs 补考最终成绩合并展示」（后端 finalScore 零调用），界面明示该边界。
 */

const route = useRoute();
const PAGE_SIZE = 50;

const candidateColumns: TableColumnsType = [
  { title: '学生 ID', key: 'studentId', width: 100 },
  { title: '姓名', key: 'studentName', width: 140 },
  { title: '后端判定原因', key: 'reason' },
];

// ===== 主考考试选择 =====
const examPage = ref(1);
const { data: examsData, isFetching: examsFetching } = useQuery({
  queryKey: computed(() => ['exams', 'makeups', examPage.value] as const),
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
const passLine = ref<number>(60);

// 从缺考页「为该生创建补考」跳转过来时预填主考与该生
const presetExamId = Number(route.query.examId);
const presetStudentIds = String(route.query.studentIds ?? '')
  .split(',')
  .filter((s) => s.trim())
  .map((s) => Number(s.trim()))
  .filter((n) => !Number.isNaN(n));

if (!Number.isNaN(presetExamId) && presetExamId > 0) {
  selectedExamId.value = presetExamId;
}

// ===== 候选名单 =====
const {
  data: eligibleData,
  isFetching: eligibleFetching,
  refetch: refetchEligible,
} = useQuery({
  queryKey: computed(() => ['makeup-eligible', selectedExamId.value, passLine.value] as const),
  queryFn: () =>
    unwrap<MakeupCandidateItem[]>(
      makeupEligible({
        client,
        throwOnError: true,
        path: { id: selectedExamId.value as number },
        query: { passLine: passLine.value },
      })
    ),
  enabled: computed(() => selectedExamId.value !== undefined),
});

const candidates = computed<MakeupCandidateItem[]>(() => eligibleData.value ?? []);
const selectedIds = ref<number[]>([]);

// 候选名单更新后：保留预填（缺考页带来的学生）与用户已选项
watch(candidates, (list) => {
  const valid = new Set(list.map((c) => c.studentId as number));
  const preset = presetStudentIds.filter((id) => valid.has(id));
  const kept = selectedIds.value.filter((id) => valid.has(id));
  selectedIds.value = Array.from(new Set([...preset, ...kept]));
});

const rowSelection = computed<TableProps['rowSelection']>(() => ({
  selectedRowKeys: selectedIds.value,
  onChange: (keys) => {
    selectedIds.value = keys.map((k) => Number(k));
  },
}));

// as const 的只读元组转可变数组，满足 antd Select 的 options 类型
const ruleOptions = MAKEUP_RULE_OPTIONS.map((o) => ({ value: o.value, label: o.label }));

function studentNameOf(id: number): string {
  return candidates.value.find((c) => c.studentId === id)?.studentName ?? '';
}

// ===== 创建补考 =====
const form = reactive<{
  title: string;
  startTime: string;
  endTime: string;
  durationMinutes: number;
  allowLateMinutes: number;
  makeupScoreRule: string;
}>({
  title: '',
  startTime: '',
  endTime: '',
  durationMinutes: 60,
  allowLateMinutes: 0,
  makeupScoreRule: MAKEUP_RULE_OPTIONS[0].value,
});

const titleValidate = computed(() => ({
  help: form.title.trim() ? '' : '标题留空时后端会按默认规则生成',
  validateStatus: (form.title.trim() ? '' : 'warning') as '' | 'warning',
}));

const creating = ref(false);
const created = ref<MakeupCreateResponse | null>(null);

async function onCreate(): Promise<void> {
  if (selectedExamId.value === undefined) {
    message.warning('请先选择主考考试');
    return;
  }
  if (selectedIds.value.length === 0) {
    message.warning('请至少选择一名学生（后端 studentIds 必填）');
    return;
  }
  creating.value = true;
  try {
    created.value =
      (await unwrap(
        createMakeup({
          client,
          throwOnError: true,
          path: { id: selectedExamId.value },
          body: {
            title: form.title.trim() || undefined,
            startTime: form.startTime.trim(),
            endTime: form.endTime.trim(),
            durationMinutes: form.durationMinutes,
            allowLateMinutes: form.allowLateMinutes,
            makeupScoreRule: form.makeupScoreRule,
            studentIds: selectedIds.value,
          },
        })
      )) ?? null;
    message.success('补考已创建（独立考试记录 + 准入范围已写入）');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '创建补考失败');
  } finally {
    creating.value = false;
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
.mt-2 {
  margin-top: 0.5rem;
}
.mt-4 {
  margin-top: 1rem;
}
.hint {
  color: #999;
  font-size: 12px;
  font-weight: normal;
  max-width: 460px;
}
.pass-line {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #555;
}
.pass-input {
  width: 90px;
}
.selected-students {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.empty-hint {
  color: #999;
  font-size: 12px;
}
.reason-note {
  margin: 8px 0 0;
  color: #999;
  font-size: 12px;
}
</style>
