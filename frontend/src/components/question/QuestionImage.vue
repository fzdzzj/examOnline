<template>
  <img
    v-if="src && !failed"
    :src="src"
    :alt="alt || '题干图片'"
    class="question-image"
    data-test="question-image"
    @error="onError"
  />
  <!-- 加载失败显示占位：不阻塞作答/判读（spec 裁决：失败不阻塞） -->
  <div
    v-else-if="src && failed"
    class="question-image-fallback"
    data-test="question-image-fallback"
  >
    （图片加载失败）
  </div>
</template>

<script setup lang="ts">
/**
 * 题干图片统一渲染（add-question-image）：
 * imageUrl 为空渲染空（既有无图题目逐字零回归）；加载失败显示占位、不阻塞作答。
 * 五个渲染点（作答卡/逐题回顾/错题本/组卷预览/批改工作台）共用本组件，
 * 保持「有图渲染、无图不渲染、失败占位」三态口径一致。
 */
import { ref, watch } from 'vue';

const props = defineProps<{
  /** 相对 URL /images/{uuid}.{ext}；null/空串 = 无图（渲染空） */
  src?: string | null;
  alt?: string;
}>();

const failed = ref(false);
watch(
  () => props.src,
  () => {
    // 换图/回填后复位失败态（同一 img 复用 src 变化场景）
    failed.value = false;
  }
);

function onError(): void {
  failed.value = true;
}
</script>

<style scoped>
.question-image {
  display: block;
  max-width: 100%;
  max-height: 320px;
  margin: 0.5rem 0;
  border-radius: 4px;
}
.question-image-fallback {
  margin: 0.5rem 0;
  padding: 0.5rem;
  color: #999;
  font-size: 12px;
  background: #fafafa;
  border: 1px dashed #e5e5e5;
  border-radius: 4px;
}
</style>
