<template>
  <div class="sync-line">
    <Tag :color="tagColor" data-test="draft-sync-status">{{ statusText }}</Tag>
    <span v-if="savedAtText" class="hint" data-test="draft-saved-at">{{ savedAtText }}</span>

    <!--
      措辞纪律（spec「不声称离线考试」场景）：这里只说「答案已保存在本机 / 恢复后同步」，
      不写「离线作答 / 离线考试模式」——断线期间能做的只有留底答案，拉不到题目、也交不了卷。
      pendingSync 放最前：归零锁定后的「待同步」比普通 unsynced 更具体（叠加了锁定语境），
      二者同真时优先展示它。
    -->
    <Alert
      v-if="pendingSync"
      type="warning"
      show-icon
      message="作答已锁定，答案待同步"
      description="倒计时归零后界面已锁定。未同步的答案将在网络恢复后自动保存为服务器草稿；超时与否及最终收卷由后端判定（定时扫描会从草稿取答案兜底交卷）。"
      data-test="draft-pending-sync-alert"
      class="mt-2"
    />

    <Alert
      v-else-if="status === 'unsynced'"
      type="warning"
      show-icon
      message="未同步：答案已保存在本机"
      description="上次自动保存未送达服务器（网络不可用或服务器拒绝）。恢复网络后会自动同步，期间请勿关闭页面。"
      data-test="draft-unsynced-alert"
      class="mt-2"
    />

    <Alert
      v-else-if="status === 'conflict'"
      type="error"
      show-icon
      message="草稿版本冲突：服务器存在更新的草稿"
      description="可能是同一考试在另一设备/页面作答过。本页未保存的答案仍在本机，继续作答后下次保存将以服务器版本为基准。"
      data-test="draft-conflict-alert"
      class="mt-2"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 草稿同步状态展示（阶段 22 第 2 片）。
 *
 * 纯展示组件：状态由 `useAutoSaveDraft` 产出后传入，这里不发请求、不判网络。
 * 各状态的文案口径：
 * - `synced`：正常路径，展示后端确认的保存时刻；
 * - `pending` / `saving`：还没到 30s 保存点 / 正在保存——不渲染警告，避免打扰作答；
 * - `unsynced`：断线或被拒——明示「答案已保存在本机」+「恢复后自动同步」；
 * - `conflict`：多端版本冲突——如实告知，不替学生做覆盖决定；
 * - `pendingSync`（归零 + 未同步）：任务 2 接缝 step 的「待同步」展示位。
 */
import { Alert, Tag } from 'ant-design-vue';
import { computed } from 'vue';

import type { DraftSyncStatus } from '@/hooks/useAutoSaveDraft';

const props = defineProps<{
  status: DraftSyncStatus;
  /** 后端 savedTime 原文（LocalDateTime.toString()），仅原样展示。 */
  lastSavedAt: string | null;
  pendingSync: boolean;
}>();

const statusText = computed(() => {
  if (props.pendingSync) return '待同步';
  switch (props.status) {
    case 'synced':
      return '已自动保存';
    case 'saving':
      return '正在保存…';
    case 'pending':
      return '有未保存的修改';
    case 'unsynced':
      return '未同步';
    case 'conflict':
      return '版本冲突';
    default:
      return '暂无修改';
  }
});

const tagColor = computed(() => {
  if (props.pendingSync || props.status === 'unsynced') return 'orange';
  if (props.status === 'conflict') return 'red';
  if (props.status === 'synced') return 'green';
  return 'blue';
});

const savedAtText = computed(() =>
  props.status === 'synced' && props.lastSavedAt ? `服务器保存于 ${props.lastSavedAt}` : ''
);
</script>

<style scoped>
.sync-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex-wrap: wrap;
}
.hint {
  color: #666;
  font-size: 12px;
  line-height: 22px;
}
.mt-2 {
  margin-top: 8px;
}
</style>
