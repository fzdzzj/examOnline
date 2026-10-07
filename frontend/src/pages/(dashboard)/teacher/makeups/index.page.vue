<template>
  <div>
    <Card title="补考管理" class="mb-4">
      <template #extra>
        <span class="hint">
          补考是独立考试记录，通过 parent_exam_id 关联主考，准入范围写入 exam_candidates。
        </span>
      </template>

      <!-- 合并规则在后端：本页只渲染返回值，前端不本地推算（诚实边界随后端接线同步更新） -->
      <Alert type="info" show-icon class="mb-3">
        <template #message>补考最终成绩：合并规则在后端，历史成绩保留不覆盖</template>
        <template #description>
          后端沿主考家族（主考 + 各次补考）按考试配置的成绩规则（取最高 / 取最近一次 /
          取平均）合并出最终成绩，
          历史各次成绩保留、从不覆盖（后端只读计算，不改答卷）。本页只渲染后端返回值，
          <b>不本地推算合并规则、不组装家族树</b>
          。
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
      <!-- U-1 三态分离：候选人查询失败显性呈现——失败不得落「请先点查询」引导文案 -->
      <Alert
        v-if="eligibleErrorText"
        type="error"
        show-icon
        :message="eligibleErrorText"
        data-test="eligible-error"
        class="mb-3"
      />
      <Table
        v-else
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

    <Card title="补考最终成绩（后端合并）" class="mb-4">
      <div class="mb-3 flex flex-wrap items-center gap-3">
        <Select
          v-model:value="finalStudentId"
          :options="finalStudentOptions"
          placeholder="选择学生（来自候选人名单）"
          class="w-72"
          allow-clear
        />
      </div>
      <Spin :spinning="finalFetching">
        <!-- U-1 三态分离：最终成绩查询失败显性呈现——失败不得落「选择学生后查询」提示 -->
        <Alert
          v-if="finalErrorText"
          type="error"
          show-icon
          :message="finalErrorText"
          data-test="final-error"
          class="mb-3"
        />
        <Descriptions v-else-if="finalData" bordered :column="1">
          <DescriptionsItem label="最终成绩（后端沿主考家族合并）">
            {{ finalData.finalScore ?? '—（后端返回为空：该生无已批改成绩记录）' }}
          </DescriptionsItem>
        </Descriptions>
        <p v-else class="reason-note">
          选择学生后查询：后端沿主考家族按考试配置规则合并，历史成绩保留不覆盖；前端只渲染返回值，不本地推算。
        </p>
      </Spin>
    </Card>

    <Card title="创建补考">
      <Form
        ref="formRef"
        :model="form"
        :rules="rules"
        :label-col="{ span: 5 }"
        :wrapper-col="{ span: 14 }"
      >
        <FormItem label="补考标题" v-bind="titleValidate">
          <Input v-model:value="form.title" placeholder="如：高一数学期末补考" />
        </FormItem>
        <FormItem label="开始时间" name="startTime" required>
          <DatePicker
            v-model:value="form.startTime"
            show-time
            format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择开始时间"
            style="width: 100%"
          />
        </FormItem>
        <FormItem label="结束时间" name="endTime" required>
          <DatePicker
            v-model:value="form.endTime"
            show-time
            format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择结束时间"
            style="width: 100%"
          />
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
        message="补考已成为独立考试记录，可在「考试管理」中查看与发布；补考最终成绩由后端沿主考家族合并（历史成绩保留不覆盖），可在上方「补考最终成绩」卡片按学生查询。"
      />
    </Card>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  Input,
  InputNumber,
  Select,
  Spin,
  Table,
  Tag,
  message,
  type FormInstance,
  type TableColumnsType,
  type TableProps,
} from 'ant-design-vue';
import type { Rule } from 'ant-design-vue/es/form';
import type { Dayjs } from 'dayjs';
import { computed, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import {
  createMakeup,
  makeupEligible,
  makeupFinalScore,
  page2 as pageExams,
  type ExamResponse,
  type MakeupCandidateItem,
  type MakeupCreateResponse,
  type MakeupFinalScoreResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { MAKEUP_RULE_OPTIONS, makeupRuleLabel } from '@/constants/postExam';
import { createTeacherExamsQueryOptions } from '@/hooks/useTeacherExams';

/**
 * 补考管理（阶段 23，最终成绩展示见 add-makeup-final-score-frontend）：
 * - GET /api/exams/{id}/makeup-eligible?passLine= 候选名单（后端按及格线判定，reason 由后端给出）
 * - POST /api/exams/{id}/makeups 创建补考（独立考试记录 + parentExamId + exam_candidates 准入）
 * - GET /api/exams/{examId}/scores/makeup-final/{studentId} 补考最终成绩（教师侧）：
 *   权限 exam:manage（TEACHER/ADMIN），水平越权由后端归属校验兜底（仅考试创建教师可查，ADMIN 放行）；
 *   沿主考家族按考试配置规则合并、历史成绩保留不覆盖（后端只读计算）；教师侧返回 reviewing 恒为 false
 *   ——「复核中隐藏分数」是学生查本人的口径（见 student/scores 页），前端不得在此自行推断。
 * - GET /api/scores/makeup-final?examId= 补考最终成绩（学生查本人，教师侧端点的学生视角）：
 *   口径同 myScore——未发布统一「成绩待发布」、家族内存在进行中复核时 reviewing=true 且分数置空；
 *   该端点在 student/scores 页使用，本页不调用。
 */

const route = useRoute();

const candidateColumns: TableColumnsType = [
  { title: '学生 ID', key: 'studentId', dataIndex: 'studentId', width: 100 },
  { title: '姓名', key: 'studentName', dataIndex: 'studentName', width: 140 },
  { title: '后端判定原因', key: 'reason', dataIndex: 'reason' },
];

// ===== 主考考试选择 =====
const { data: examsData, isFetching: examsFetching } = useQuery(
  createTeacherExamsQueryOptions((page, size) =>
    unwrap<ExamResponse[]>(pageExams({ client, throwOnError: true, query: { page, size } }))
  )
);
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
  error: eligibleError,
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

// U-1 三态分离：候选人查询失败显性呈现（Alert 承载后端 message），
// 不落「请先选择主考考试并点击『查询补考候选人』」引导文案
const eligibleErrorText = computed<string | null>(() => {
  const caught = eligibleError.value;
  if (!caught) return null;
  return caught instanceof Error ? caught.message : '补考候选人查询失败';
});
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

// ===== 补考最终成绩（教师侧，exam:manage；合并在后端，这里只渲染返回值） =====
const finalStudentId = ref<number | undefined>(undefined);
const finalStudentOptions = computed(() =>
  candidates.value.map((c) => ({
    value: c.studentId as number,
    label: `#${c.studentId} ${c.studentName ?? ''}`,
  }))
);

const {
  data: finalData,
  isFetching: finalFetching,
  error: finalError,
} = useQuery({
  queryKey: computed(
    () => ['makeup-final-score', selectedExamId.value, finalStudentId.value] as const
  ),
  queryFn: () =>
    unwrap<MakeupFinalScoreResponse>(
      makeupFinalScore({
        client,
        throwOnError: true,
        path: {
          examId: selectedExamId.value as number,
          studentId: finalStudentId.value as number,
        },
      })
    ),
  enabled: computed(() => selectedExamId.value !== undefined && finalStudentId.value !== undefined),
});

// U-1 三态分离：最终成绩查询失败显性呈现（Alert 承载后端 message），
// 不落「选择学生后查询」提示文案（成功且无数据时该文案原样保留）
const finalErrorText = computed<string | null>(() => {
  const caught = finalError.value;
  if (!caught) return null;
  return caught instanceof Error ? caught.message : '补考最终成绩查询失败';
});

// 换主考后候选名单随之变化：清掉已选学生，避免拿着旧家族的学生查询
watch(selectedExamId, () => {
  finalStudentId.value = undefined;
});

// as const 的只读元组转可变数组，满足 antd Select 的 options 类型
const ruleOptions = MAKEUP_RULE_OPTIONS.map((o) => ({ value: o.value, label: o.label }));

function studentNameOf(id: number): string {
  return candidates.value.find((c) => c.studentId === id)?.studentName ?? '';
}

// ===== 创建补考 =====
const formRef = ref<FormInstance>();

const form = reactive<{
  title: string;
  startTime: Dayjs | undefined;
  endTime: Dayjs | undefined;
  durationMinutes: number;
  allowLateMinutes: number;
  makeupScoreRule: string;
}>({
  title: '',
  startTime: undefined,
  endTime: undefined,
  durationMinutes: 60,
  allowLateMinutes: 0,
  makeupScoreRule: MAKEUP_RULE_OPTIONS[0].value,
});

const rules: Record<string, Rule[]> = {
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [
    { required: true, message: '请选择结束时间', trigger: 'change' },
    {
      validator: async (_rule: Rule, value: Dayjs | undefined) => {
        if (!value) {
          return Promise.reject(new Error('请选择结束时间'));
        }
        if (form.startTime && !value.isAfter(form.startTime)) {
          return Promise.reject(new Error('结束时间必须晚于开始时间'));
        }
        return Promise.resolve();
      },
      trigger: 'change',
    },
  ],
};

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

  // U-4 表单校验：必填与起止先后由 Form rules 统一裁决，移除提交时 warning 双轨分支
  if (formRef.value) {
    try {
      await formRef.value.validate();
    } catch {
      return;
    }
  } else {
    if (!form.startTime || !form.endTime || !form.endTime.isAfter(form.startTime)) {
      return;
    }
  }

  const startTime = form.startTime?.format('YYYY-MM-DDTHH:mm:ss');
  const endTime = form.endTime?.format('YYYY-MM-DDTHH:mm:ss');
  if (!startTime || !endTime) {
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
            startTime,
            endTime,
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
