<template>
  <Modal
    :open="open"
    :title="'⚡ 分值智能配平'"
    :mask-closable="false"
    :confirm-loading="submitting"
    :ok-text="'一键应用'"
    :ok-button-props="{ disabled: previewError !== null }"
    @ok="apply"
    @cancel="emit('close')"
  >
    <Tabs v-model:activeKey="activeTab">
      <TabPane key="even" tab="一键均分">
        <p class="mb-0 text-gray-500">
          将试卷全部题目按目标总分离散最大余数均分，严格保证各题分值之和等于目标总分。
        </p>
      </TabPane>
      <TabPane key="byType" tab="按题型配比">
        <p class="mb-0 text-gray-500">
          为指定题型设定固定单题分值，其余题型自动均分补齐至目标总分（严格保和）。
        </p>
      </TabPane>
    </Tabs>

    <div class="mt-4 space-y-3">
      <div class="flex items-center gap-2">
        <span class="w-20 text-gray-600">目标总分</span>
        <InputNumber
          v-model:value="targetScore"
          :min="0"
          :max="99999"
          :step="0.5"
          class="w-36"
          data-test="rebalance-target"
        />
        <span class="text-xs text-gray-400">缺省=申报总分 {{ declaredTotal }}</span>
      </div>

      <div class="flex items-center gap-2">
        <span class="w-20 text-gray-600">步长</span>
        <RadioGroup v-model:value="step" button-style="solid" size="small">
          <RadioButton :value="0.5">0.5</RadioButton>
          <RadioButton :value="1.0">1.0</RadioButton>
        </RadioGroup>
      </div>

      <!-- 按题型配比：各题型固定单题分值 -->
      <div
        v-if="activeTab === 'byType'"
        data-test="rebalance-bytype-fixed"
        class="rounded border border-gray-200 p-2"
      >
        <div class="mb-1 text-sm font-medium text-gray-600">
          各题型单题固定分值（留空=自动配平）
        </div>
        <div v-for="group in typeGroups" :key="group.type" class="flex items-center gap-2 py-0.5">
          <span class="w-14 text-sm text-gray-700">{{ group.label }}</span>
          <InputNumber
            :value="fixedScores[group.type]"
            :min="0.5"
            :step="step"
            class="w-28"
            placeholder="自动"
            @update:value="(v: string | number | null) => setFixed(group.type, v)"
          />
          <span class="text-xs text-gray-400">
            {{ group.count }} 题 × {{ fixedScores[group.type] ?? '…' }}
          </span>
        </div>
      </div>

      <Alert
        v-if="previewError"
        type="error"
        show-icon
        :message="previewError"
        data-test="rebalance-error"
      />

      <div class="rounded bg-gray-50 p-2 text-sm">
        <div class="mb-1 flex items-center gap-2">
          <span class="font-medium text-gray-700">配平预览</span>
          <Tag v-if="previewSumOk" color="green" data-test="rebalance-sum-ok">
            保和 ✓ 合计 {{ previewSum }}
          </Tag>
          <Tag v-else color="red">合计 {{ previewSum }}</Tag>
        </div>
        <div v-for="row in preview.rows" :key="row.type" class="flex items-center gap-2">
          <span class="w-14 text-gray-600">{{ row.label }}</span>
          <span class="text-gray-700">{{ row.count }} 题</span>
          <span class="text-gray-500">×</span>
          <span class="font-medium">{{ row.score }}</span>
          <span class="text-gray-400">= {{ row.subtotal }} 分</span>
        </div>
      </div>
    </div>
  </Modal>
</template>

<script setup lang="ts">
import {
  Alert,
  InputNumber,
  Modal,
  RadioButton,
  RadioGroup,
  TabPane,
  Tabs,
  Tag,
  message,
} from 'ant-design-vue';
import { computed, reactive, ref, watch } from 'vue';

import type { PaperQuestionItemResponse } from '@/api/axios';
import { typeLabelOf } from '@/utils/questionTypes';

const props = defineProps<{
  open: boolean;
  items: PaperQuestionItemResponse[];
  declaredTotal: number;
  submitting?: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (
    e: 'submit',
    payload: {
      mode: string;
      targetScore: number;
      step: number;
      fixedScores: Record<string, number>;
    }
  ): void;
}>();

const activeTab = ref('even');
const targetScore = ref<number | undefined>(undefined);
const step = ref<0.5 | 1.0>(0.5);
const fixedScores = reactive<Record<string, number | undefined>>({});

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    activeTab.value = 'even';
    targetScore.value = props.declaredTotal || undefined;
    step.value = 0.5;
    for (const key of Object.keys(fixedScores)) delete fixedScores[key];
    // 按题型配比默认把前三类客观题设为易配平的常见分值，其余留空自动
    for (const group of typeGroups.value) {
      fixedScores[group.type] = undefined;
    }
  }
);

const typeGroups = computed(() => {
  const groupMap = new Map<number, { type: number; label: string; count: number }>();
  for (const item of props.items) {
    const type = item.questionType ?? -1;
    if (type < 0) continue;
    let row = groupMap.get(type);
    if (!row) {
      row = { type, label: typeLabelOf(type), count: 0 };
      groupMap.set(type, row);
    }
    row.count += 1;
  }
  return [...groupMap.values()];
});

function setFixed(type: number, value: string | number | null | undefined): void {
  if (value === null || value === undefined || value === '') {
    delete fixedScores[type];
  } else {
    fixedScores[type] = Number(value);
  }
}

/** 步长正整数化：value 须为 step 的整数倍，否则返回 null。 */
function integerSteps(value: number): number | null {
  const scaled = value / step.value;
  if (Math.abs(scaled - Math.round(scaled)) < 1e-6) {
    return Math.round(scaled);
  }
  return null;
}

const previewError = computed<string | null>(() => {
  const target = targetScore.value ?? props.declaredTotal ?? 0;
  if (!Number.isFinite(target) || target < 0) return '目标总分不合法';
  if (integerSteps(target) === null) {
    return `目标总分须为步长（${step.value}）的整数倍`;
  }
  const totalCount = props.items.filter(
    (i) => i.questionType !== undefined && i.questionType !== null
  ).length;
  if (totalCount === 0) return '待配平题目数为 0';
  return null;
});

interface PreviewRow {
  type: number;
  label: string;
  count: number;
  score: number;
  subtotal: number;
}

/** 前端只读预览：镜像后端离散最大余数保和算法（真正持久化以后端端点为准）。 */
const preview = computed<{ rows: PreviewRow[]; sum: number }>(() => {
  const target = targetScore.value ?? props.declaredTotal ?? 0;
  const types = typeGroups.value;

  if (activeTab.value !== 'byType') {
    // EVEN：全卷题目按目标总分整数化为步长数后均分（保和 = target）
    const n = props.items.length;
    const T = integerSteps(target) ?? 0;
    const Q = Math.floor(T / n);
    return {
      rows: [
        {
          type: -1,
          label: '全部题目',
          count: n,
          score: Q * step.value,
          subtotal: T * step.value,
        },
      ],
      sum: T * step.value,
    };
  }

  // BY_TYPE：固定题型按所设分值，其余题型用最大余数均分剩余目标
  const rows: PreviewRow[] = [];
  let fixedSum = 0;
  let autoCount = 0;
  const autoGroups: typeof types = [];
  for (const group of types) {
    const fixed = fixedScores[group.type];
    if (fixed !== undefined && fixed !== null) {
      const subtotal = fixed * group.count;
      fixedSum += subtotal;
      rows.push({
        type: group.type,
        label: group.label,
        count: group.count,
        score: fixed,
        subtotal,
      });
    } else {
      autoCount += group.count;
      autoGroups.push(group);
    }
  }
  const remaining = target - fixedSum;
  const T = integerSteps(remaining) ?? 0;
  if (autoCount > 0) {
    const Q = Math.floor(T / autoCount);
    const autoScore = Q * step.value;
    for (const group of autoGroups) {
      rows.push({
        type: group.type,
        label: group.label,
        count: group.count,
        score: autoScore,
        subtotal: autoScore * group.count,
      });
    }
  }
  const sum = rows.reduce((acc, row) => acc + row.subtotal, 0);
  return { rows, sum };
});

// 预览汇总：严格等于目标总分即视为保和通过
const previewSum = computed(() => preview.value.sum);
const previewSumOk = computed(() => {
  const target = targetScore.value ?? props.declaredTotal ?? 0;
  return previewError.value === null && Math.abs(previewSum.value - target) < 1e-6;
});

function apply(): void {
  if (previewError.value) {
    message.warning(previewError.value);
    return;
  }
  const target = targetScore.value ?? props.declaredTotal ?? 0;
  const fixed: Record<string, number> = {};
  for (const group of typeGroups.value) {
    const value = fixedScores[group.type];
    if (value !== undefined && value !== null) {
      fixed[String(group.type)] = value;
    }
  }
  emit('submit', {
    mode: activeTab.value === 'byType' ? 'BY_TYPE' : 'EVEN',
    targetScore: target,
    step: step.value,
    fixedScores: fixed,
  });
}
</script>
