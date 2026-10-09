<template>
  <Alert
    v-if="visible"
    type="warning"
    show-icon
    message="离线保护中，答案已本地保存"
    description="当前网络连接已断开或草稿同步未送达服务器。您的答案已实时保存在本机，请继续作答；恢复网络后系统将自动同步。"
    data-test="offline-status-banner"
    class="mb-3"
  />
</template>

<script setup lang="ts">
/**
 * 离线保护状态条（阶段 2）。
 *
 * 措辞纪律：
 * 严禁出现「离线考试 / 离线作答 / offline exam / offline answer」字样；
 * 如实告知学生当前处于离线保护中，答案已本地留存，恢复后自动同步。
 */
import { Alert } from 'ant-design-vue';

defineProps<{
  visible: boolean;
}>();
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
</style>
