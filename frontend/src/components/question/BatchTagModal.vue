<template>
  <Modal
    :open="open"
    :title="`批量标签（${questionIds.length} 题）`"
    :mask-closable="false"
    :confirm-loading="submitting"
    :ok-text="mode === BIND ? '追加标签' : '移除标签'"
    :ok-button-props="{ disabled: selectedTagIds.length === 0 }"
    @ok="submit"
    @cancel="emit('close')"
  >
    <div class="mb-3">
      <RadioGroup v-model:value="mode" button-style="solid">
        <RadioButton :value="BIND">追加标签（BIND）</RadioButton>
        <RadioButton :value="UNBIND">移除标签（UNBIND）</RadioButton>
      </RadioGroup>
    </div>

    <Alert
      v-if="mode === BIND"
      type="info"
      show-icon
      class="mb-3"
      :message="`为选中的 ${questionIds.length} 道题目追加所选标签（幂等，不覆盖原有标签）`"
    />
    <Alert
      v-else
      type="info"
      show-icon
      class="mb-3"
      :message="`从选中的 ${questionIds.length} 道题目上移除所选标签（其余标签保留）`"
    />

    <!-- 按标签分类组织的多选选择器 -->
    <div
      v-for="group in tagGroups"
      :key="group.type"
      class="mb-3"
      :data-test="`batch-tag-group-${group.type.toLowerCase()}`"
    >
      <div class="mb-1 text-sm font-medium text-gray-600">{{ group.label }}</div>
      <div v-if="group.tags.length === 0" class="text-xs text-gray-400">无</div>
      <CheckboxGroup
        v-else
        v-model:value="selectedTagIds"
        :options="
          group.tags.map((tag) => ({ value: tag.id as number, label: tag.name ?? String(tag.id) }))
        "
      />
    </div>

    <Alert
      v-if="tags.length === 0"
      type="warning"
      show-icon
      message="题库尚无标签，请先在题库管理中创建标签"
    />
  </Modal>
</template>

<script setup lang="ts">
import { Alert, CheckboxGroup, Modal, RadioButton, RadioGroup } from 'ant-design-vue';
import { computed, ref, watch } from 'vue';

import { tagTypeLabelOf } from '@/utils/questionTypes';
import type { TagResponse } from '@/api/axios';

const BIND = 'BIND';
const UNBIND = 'UNBIND';

const props = defineProps<{
  open: boolean;
  questionIds: number[];
  tags: TagResponse[];
  submitting?: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'submit', payload: { questionIds: number[]; tagIds: number[]; action: string }): void;
}>();

const mode = ref<string>(BIND);
const selectedTagIds = ref<number[]>([]);

// 打开弹窗时重置为「追加」模式并清空已选标签
watch(
  () => props.open,
  (open) => {
    if (open) {
      mode.value = BIND;
      selectedTagIds.value = [];
    }
  }
);

const tagGroups = computed(() => {
  const groups = new Map<string, TagResponse[]>();
  for (const tag of props.tags) {
    const type = tag.type ?? 'CUSTOM';
    if (!groups.has(type)) groups.set(type, []);
    groups.get(type)!.push(tag);
  }
  return [...groups.entries()].map(([type, tags]) => ({
    type,
    label: tagTypeLabelOf(type),
    tags,
  }));
});

function submit(): void {
  if (selectedTagIds.value.length === 0) return;
  emit('submit', {
    questionIds: [...props.questionIds],
    tagIds: [...selectedTagIds.value],
    action: mode.value,
  });
}
</script>
