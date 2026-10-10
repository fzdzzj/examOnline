<template>
  <Alert
    v-if="visible"
    type="warning"
    show-icon
    :message="OFFLINE_STATUS_BANNER_TEXT.MESSAGE"
    :description="OFFLINE_STATUS_BANNER_TEXT.DESCRIPTION"
    data-test="offline-status-banner"
    class="mb-3"
  />
</template>

<script setup lang="ts">
/**
 * 离线保护状态条（阶段 2 / 创新点 5 二期）。
 *
 * 措辞纪律：
 * 严禁出现「离线考试 / 离线作答 / offline exam / offline answer」字样；
 * 如实告知学生当前处于离线保护中，答案已本地留存，恢复后自动同步；显性告知超窗收卷风险。
 */
import { Alert } from 'ant-design-vue';
import { OFFLINE_STATUS_BANNER_TEXT } from '@/constants/studentTaking';

defineProps<{
  visible: boolean;
}>();
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
</style>
