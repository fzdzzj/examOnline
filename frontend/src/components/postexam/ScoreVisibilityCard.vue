<template>
  <div>
    <!-- 三态均由后端决定：未发布（后端 400「成绩待发布」）/ 复核中（reviewing=true，后端已置空分数）/ 已发布 -->
    <!-- variant="final"：补考最终成绩视图——后端 MakeupFinalScoreResponse 只有 finalScore，
         不渲染排名 / 客观题 / 主观题 / 批改状态等后端没给的字段（不编造） -->
    <Descriptions
      v-if="view.kind === 'published' && variant === 'final'"
      bordered
      :column="1"
      size="middle"
    >
      <DescriptionsItem label="最终成绩（后端沿主考家族合并）">
        <span class="total">{{ view.totalScore ?? '-' }}</span>
      </DescriptionsItem>
    </Descriptions>

    <Descriptions v-else-if="view.kind === 'published'" bordered :column="2" size="middle">
      <DescriptionsItem label="考试">{{ view.examTitle ?? '-' }}</DescriptionsItem>
      <DescriptionsItem label="排名">
        {{ view.rank === 0 || view.rank === undefined ? '-' : `第 ${view.rank} 名` }}
      </DescriptionsItem>
      <DescriptionsItem label="客观题">{{ view.objectiveScore ?? '-' }}</DescriptionsItem>
      <DescriptionsItem label="主观题">{{ view.subjectiveScore ?? '-' }}</DescriptionsItem>
      <DescriptionsItem label="总分">
        <span class="total">{{ view.totalScore ?? '-' }}</span>
      </DescriptionsItem>
      <DescriptionsItem label="批改状态">
        <Tag :color="view.partialGraded === 1 ? 'warning' : 'green'">
          {{ view.partialGraded === 1 ? '部分批改' : '已全部批改' }}
        </Tag>
      </DescriptionsItem>
    </Descriptions>

    <Result
      v-else-if="view.kind === 'reviewing'"
      status="info"
      title="成绩复核中，暂不可见"
      sub-title="你已提交成绩复核申请，后端在处理期间隐藏成绩（判定在后端，前端不自行推断）。处理完成后本页会显示最终成绩。"
    />

    <Result
      v-else
      status="warning"
      title="成绩未发布"
      sub-title="该考试成绩尚未发布（或已被撤回）。后端对未发布统一返回「成绩待发布」，此处如实呈现，不显示 0 分或空白分数。"
    />
  </div>
</template>

<script setup lang="ts">
import { Descriptions, DescriptionsItem, Result, Tag } from 'ant-design-vue';

import type { ScoreView } from '@/utils/scoreVisibility';

/**
 * 学生成绩卡片：三态渲染完全由后端返回的 ScoreView 驱动。
 * ⚠️ 组件内不得出现任何「是否复核中」「是否已发布」的本地推断——
 * 那会把后端的可见性裁决复制到前端，规则漂移会泄露成绩。
 * variant：'detail' 常规成绩全字段；'final' 补考最终成绩仅总分（后端只返回 finalScore）。
 */

withDefaults(defineProps<{ view: ScoreView; variant?: 'detail' | 'final' }>(), {
  variant: 'detail',
});
</script>

<style scoped>
.total {
  font-size: 20px;
  font-weight: 600;
  color: #1677ff;
}
</style>
