<template>
  <div>
    <Alert
      v-if="loadError"
      type="error"
      show-icon
      class="mb-3"
      :message="loadError"
      data-test="arbitration-load-error"
    />
    <Alert
      v-else-if="items.length === 0"
      type="info"
      show-icon
      class="mb-3"
      message="暂无待仲裁题目"
      data-test="arbitration-empty"
    />

    <div
      v-for="item in items"
      :key="`${item.submissionId}-${item.questionId}`"
      class="arbitration-item"
    >
      <div class="item-head">
        <span class="student">{{ item.studentName ?? `答卷 ${item.submissionId}` }}</span>
        <span class="meta">
          第 {{ item.questionNumber }} 题 · 满分 {{ item.maxScore }} 分 · 分差
          {{ item.scoreDiff }} 分
        </span>
        <Tag color="orange">待仲裁</Tag>
      </div>
      <div class="compare-row">
        <Card size="small" class="review-card" :title="`一评：${item.firstScore} 分`">
          <TypographyParagraph
            :content="item.firstComment || '（无评语）'"
            :ellipsis="{ rows: 3, expandable: true }"
          />
        </Card>
        <Card size="small" class="review-card" :title="`二评：${item.secondScore} 分`">
          <TypographyParagraph
            :content="item.secondComment || '（无评语）'"
            :ellipsis="{ rows: 3, expandable: true }"
          />
        </Card>
      </div>
      <div class="arbitrate-row">
        <InputNumber
          :value="draftOf(item).score ?? undefined"
          class="score-input"
          :min="0"
          :max="item.maxScore"
          :step="0.5"
          placeholder="仲裁分"
          :status="errorOf(item) ? 'error' : undefined"
          @update:value="
            (v) =>
              setDraft(
                (item as BlindArbitrationItem).submissionId as number,
                (item as BlindArbitrationItem).questionId as number,
                'score',
                v
              )
          "
        />
        <Input
          :value="draftOf(item).comment"
          placeholder="仲裁理由（可空）"
          class="comment-input"
          @update:value="
            (v) =>
              setDraft(
                (item as BlindArbitrationItem).submissionId as number,
                (item as BlindArbitrationItem).questionId as number,
                'comment',
                v
              )
          "
        />
        <Button
          type="primary"
          size="small"
          data-test="arbitrate-submit"
          :loading="arbitratingKey === keyOf(item)"
          :disabled="arbitratingKey !== null"
          @click="onArbitrate(item)"
        >
          终审定分
        </Button>
      </div>
      <div v-if="errorOf(item)" class="row-error" data-test="arbitrate-error">
        {{ errorOf(item) }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Alert, Button, Card, Input, InputNumber, Tag, Typography, message } from 'ant-design-vue';
import { onMounted, reactive, ref } from 'vue';

import {
  arbitrate,
  arbitrationList,
  type BlindArbitrationItem,
  type BlindArbitrateResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';

/**
 * 分差仲裁面板（add-subjective-double-blind-grading）：
 * 待仲裁清单（一评/二评分数评语并排——仲裁员是唯一可见双方信息的角色）
 * + 终审仲裁打分控件（arbitrate，终分以仲裁分为准回填并结案）。
 * 后端 400（超满分/非待仲裁）错误条内呈现，绝不伪装成功。
 */

const TypographyParagraph = Typography.Paragraph;

const props = defineProps<{
  examId: number;
}>();

const items = ref<BlindArbitrationItem[]>([]);
const loading = ref(false);
const loadError = ref<string | null>(null);
const arbitratingKey = ref<string | null>(null);
const errorByKey = ref<Record<string, string>>({});

async function loadItems(): Promise<void> {
  loading.value = true;
  loadError.value = null;
  try {
    const list = await unwrap(
      arbitrationList({
        client,
        throwOnError: true,
        path: { examId: props.examId },
      })
    );
    items.value = (list as BlindArbitrationItem[] | undefined) ?? [];
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '待仲裁清单加载失败，请稍后重试';
  } finally {
    loading.value = false;
  }
}

onMounted(() => void loadItems());

function keyOf(item: BlindArbitrationItem): string {
  return `${item.submissionId}:${item.questionId}`;
}

function errorOf(item: BlindArbitrationItem): string | null {
  return errorByKey.value[keyOf(item)] ?? null;
}

// ===== 仲裁输入草稿（key = submissionId:questionId） =====
interface Draft {
  score: string | null;
  comment: string;
}
const drafts = reactive<Record<string, Draft>>({});

function draftOf(item: BlindArbitrationItem): Draft {
  return drafts[keyOf(item)] ?? { score: null, comment: '' };
}

function setDraft(
  submissionId: number,
  questionId: number,
  field: 'score' | 'comment',
  value: unknown
): void {
  const key = `${submissionId}:${questionId}`;
  const current = drafts[key] ?? { score: null, comment: '' };
  if (field === 'score') {
    // InputNumber 的 null/number 都收敛成字符串，交给提交前校验统一判定
    current.score = value === null || value === undefined ? null : String(value);
  } else {
    current.comment = typeof value === 'string' ? value : '';
  }
  drafts[key] = current;
}

/**
 * 提交一笔仲裁：分数必填校验 → arbitrate（终审回填）→ 成功后刷新清单（结案离场）。
 * 返回是否成功；失败时条内呈现原因。
 */
async function arbitrateItem(item: {
  submissionId?: number;
  questionId?: number;
}): Promise<boolean> {
  const key = `${item.submissionId}:${item.questionId}`;
  const draft = drafts[key] ?? { score: null, comment: '' };
  if (draft.score === null || draft.score.trim() === '') {
    errorByKey.value[key] = '请先填写仲裁分';
    return false;
  }
  arbitratingKey.value = key;
  delete errorByKey.value[key];
  try {
    const response = (await unwrap(
      arbitrate({
        client,
        throwOnError: true,
        path: { examId: props.examId },
        body: {
          submissionId: item.submissionId as number,
          questionId: item.questionId as number,
          score: Number(String(draft.score).trim()),
          comment: draft.comment?.trim() ? draft.comment.trim() : undefined,
        },
      })
    )) as BlindArbitrateResponse | undefined;
    message.success(`仲裁定分 ${response?.finalScore ?? ''} 分，已结案`);
    await loadItems();
    return true;
  } catch (error) {
    errorByKey.value[key] = error instanceof Error ? error.message : '仲裁提交失败，请稍后重试';
    return false;
  } finally {
    arbitratingKey.value = null;
  }
}

async function onArbitrate(item: BlindArbitrationItem): Promise<void> {
  await arbitrateItem(item);
}

defineExpose({
  items,
  loading,
  drafts,
  setDraft,
  arbitrateItem,
});
</script>

<style scoped>
.mb-3 {
  margin-bottom: 0.75rem;
}
.arbitration-item {
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 12px;
}
.item-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.student {
  font-weight: 600;
}
.meta {
  color: #999;
  font-size: 12px;
}
.compare-row {
  display: flex;
  gap: 12px;
  margin-bottom: 8px;
}
.review-card {
  flex: 1;
}
.arbitrate-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.score-input {
  width: 100px;
}
.comment-input {
  flex: 1;
  min-width: 160px;
}
.row-error {
  color: #cf1322;
  font-size: 12px;
  margin-top: 4px;
}
</style>
