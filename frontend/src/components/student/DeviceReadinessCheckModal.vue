<template>
  <Modal
    :open="open"
    title="考前设备自检"
    :closable="false"
    :mask-closable="false"
    :keyboard="false"
    :footer="null"
    :get-container="false"
    width="480px"
  >
    <div v-if="open" class="readiness" data-test="readiness-panel">
      <p class="tip">
        进入考试前先确认设备与运行环境就绪（全部为浏览器本地检测，不产生后端调用、不影响起表）。
      </p>

      <ul class="items">
        <li v-for="key in ITEM_KEYS" :key="key" class="item" :data-test="`readiness-item-${key}`">
          <span class="item-label">{{ READINESS_ITEM_LABELS[key] }}</span>
          <span v-if="!report" class="item-state" :data-test="`readiness-state-${key}`">
            <Spin v-if="running && runningItem === key" size="small" />
            <span v-if="running && runningItem === key">进行中</span>
            <span v-else>待执行</span>
          </span>
          <span v-else class="item-state" :data-test="`readiness-badge-${key}`">
            <Badge
              :status="badgeStatusOf(itemOf(key).level)"
              :text="badgeTextOf(itemOf(key).level)"
            />
          </span>
        </li>
      </ul>

      <Alert v-if="report" type="info" show-icon class="summary" :message="summaryText">
        <template #description>
          <ul class="summary-details">
            <li v-for="item in report.items" :key="item.key">{{ item.detail }}</li>
          </ul>
        </template>
      </Alert>

      <div class="actions">
        <Button
          type="primary"
          :disabled="!report || running"
          :data-test="hasCritical ? 'readiness-enter-critical' : 'readiness-enter'"
          @click="onEnterClick"
        >
          {{ hasCritical ? '仍要进入考试' : '进入考试' }}
        </Button>
      </div>

      <div v-if="confirmOpen" class="confirm" data-test="critical-confirm">
        <Alert
          type="error"
          show-icon
          message="检测到强警示项，是否仍要进入考试？"
          description="内核不兼容或 localStorage 不可用可能影响草稿留存与作答体验；考试可用性优先，是否进入由你决定。"
        />
        <div class="confirm-actions">
          <Button danger data-test="confirm-still-enter" @click="emit('enter')">
            确认仍要进入
          </Button>
          <Button data-test="cancel-still-enter" @click="confirmOpen = false">再想想</Button>
        </div>
      </div>
    </div>
  </Modal>
</template>

<script setup lang="ts">
/**
 * 考前设备就绪自检面板（add-pre-exam-device-readiness-check 阶段 2）。
 *
 * 纯展示组件：检查逻辑在 `hooks/useDeviceReadiness.ts`（页面驱动 run），组件只负责
 * 逐项进度 / 结果徽标 / 门控按钮 / 强警示二次确认，检查逻辑与 UI 分离可单测。
 *
 * 门控语义（知情门控，非绝对阻断）：
 * - 检查完成（report 呈现）前「进入考试」按钮禁用——「跑一遍自检」是硬门控；
 * - warning 仅徽标不阻断；critical（强警示）先「仍要进入考试」再二次确认，确认前不 emit enter；
 * - 自检异常兜底报告（全部 warning）不产生 critical，不触发二次确认。
 */
import { computed, ref } from 'vue';
import { Alert, Badge, Button, Modal, Spin } from 'ant-design-vue';

import {
  READINESS_ITEM_LABELS,
  type DeviceReadinessReport,
  type ReadinessItemKey,
  type ReadinessLevel,
} from '@/hooks/useDeviceReadiness';

const props = defineProps<{
  /** 门控面板是否呈现（在线且本场未留存、未放行）。 */
  open: boolean;
  /** 自检是否正在执行（按钮禁用依据之一）。 */
  running: boolean;
  /** 当前正在执行的检查项（逐项进度呈现）。 */
  runningItem: ReadinessItemKey | null;
  /** 自检完成报告；null 表示尚未完成。 */
  report: DeviceReadinessReport | null;
}>();

const emit = defineEmits<{ enter: [] }>();

const ITEM_KEYS: ReadinessItemKey[] = ['rtt', 'viewport', 'fullscreen', 'compatibility'];

const STATUS_BADGE: Record<ReadinessLevel, 'success' | 'warning' | 'error'> = {
  pass: 'success',
  warning: 'warning',
  critical: 'error',
};

const STATUS_TEXT: Record<ReadinessLevel, string> = {
  pass: '通过',
  warning: '警示',
  critical: '强警示',
};

function badgeStatusOf(level: ReadinessLevel): 'success' | 'warning' | 'error' {
  return STATUS_BADGE[level];
}

function badgeTextOf(level: ReadinessLevel): string {
  return STATUS_TEXT[level];
}

function itemOf(key: ReadinessItemKey) {
  const item = props.report?.items.find((entry) => entry.key === key);
  return (
    item ?? {
      key,
      level: 'pass' as const,
      label: READINESS_ITEM_LABELS[key],
      detail: '',
    }
  );
}

const hasCritical = computed(
  () => props.report?.items.some((item) => item.level === 'critical') ?? false
);

const summaryText = computed(() => {
  if (!props.report) return '自检进行中';
  switch (props.report.overall) {
    case 'critical':
      return '存在强警示项：进入前请知悉风险';
    case 'warning':
      return '存在提示项：可进入考试（不阻断）';
    default:
      return '环境就绪：可以进入考试';
  }
});

/** 二次确认状态：仅在 report 含 critical 且学生点了「仍要进入考试」后出现。 */
const confirmOpen = ref(false);

function onEnterClick(): void {
  if (hasCritical.value) {
    confirmOpen.value = true;
    return;
  }
  emit('enter');
}
</script>

<style scoped>
.tip {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: #6b7280;
}
.items {
  list-style: none;
  margin: 0;
  padding: 0;
}
.item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px dashed #e5e7eb;
}
.item-label {
  font-size: 14px;
  color: #1f2937;
}
.item-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #6b7280;
}
.summary {
  margin-top: 12px;
}
.summary-details {
  margin: 0;
  padding-left: 16px;
  font-size: 13px;
  line-height: 1.7;
}
.actions {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.confirm {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
}
.confirm-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
