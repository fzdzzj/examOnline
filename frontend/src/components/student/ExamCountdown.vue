<template>
  <div>
    <Alert
      v-if="unanchored"
      type="warning"
      show-icon
      message="后端未返回剩余时间，界面不做本地推算"
      description="倒计时以服务端时间为准；此处不留假数值，也不改用本机时钟补一个。"
      data-test="countdown-unanchored"
      class="mb-2"
    />

    <Alert
      v-else-if="expired"
      type="error"
      show-icon
      message="考试时间已到，作答入口已锁定"
      description="超时与否由服务端时间判定（后端有就地兜底强制交卷与定时扫描），本界面只负责停止作答。"
      data-test="countdown-expired"
      class="mb-2"
    />

    <Alert
      v-else-if="nearEnd"
      type="warning"
      show-icon
      message="剩余时间不足 5 分钟，请注意交卷"
      description="这只是展示提示：超时与能否交卷仍由后端判定。"
      data-test="countdown-near-end"
      class="mb-2"
    />

    <div class="countdown-line">
      <span class="label">剩余时间</span>
      <Tag :color="tagColor" data-test="countdown-value">{{ display }}</Tag>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 倒计时展示（阶段 22 第 1 片）。
 *
 * 纯展示组件：`display` / `expired` 全部由 `useServerCountdown` 从后端字段算出来后传入，
 * 这里没有任何 `Date.now()`、也没有 `deadline - 本地时间`。
 * 措辞也刻意不写「已超时」——前端无权宣布超时，只能说「作答入口已锁定」。
 */
import { Alert, Tag } from 'ant-design-vue';
import { computed } from 'vue';

import { NEAR_END_WARNING_THRESHOLD_SECONDS } from '@/constants/studentTaking';

const props = defineProps<{
  /** mm:ss / h:mm:ss，或 '—'（后端未给时间） */
  display: string;
  remainingSeconds: number | null;
  expired: boolean;
  unanchored: boolean;
}>();

const nearEnd = computed(() => {
  const seconds = props.remainingSeconds;
  return (
    !props.unanchored &&
    !props.expired &&
    seconds !== null &&
    seconds > 0 &&
    seconds <= NEAR_END_WARNING_THRESHOLD_SECONDS
  );
});

const tagColor = computed(() => {
  if (props.unanchored) return 'default';
  if (props.expired) return 'red';
  const seconds = props.remainingSeconds;
  return seconds !== null && seconds <= 60 ? 'volcano' : 'blue';
});
</script>

<style scoped>
.countdown-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.label {
  color: #666;
  font-size: 13px;
}
.mb-2 {
  margin-bottom: 8px;
}
</style>
