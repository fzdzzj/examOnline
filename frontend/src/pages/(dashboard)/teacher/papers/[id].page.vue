<template>
  <Spin :spinning="isFetching">
    <Alert
      v-if="detail && isLocked"
      type="info"
      show-icon
      class="mb-3"
      message="试卷已锁定（已生成快照），以下内容为只读预览"
    />

    <Empty v-else-if="!detail && !isFetching" description="试卷不存在或加载失败" />

    <template v-if="detail">
      <Card class="mb-4">
        <div class="flex flex-wrap items-start justify-between gap-2">
          <div class="min-w-0">
            <h2 class="mb-1 flex items-center gap-2 text-lg font-semibold">
              <span class="truncate">{{ detail.title }}</span>
              <Tag :color="isLocked ? 'gold' : 'default'">
                {{ paperStatusLabel(detail.status) }}
              </Tag>
            </h2>
            <p class="mb-1 text-gray-500">{{ detail.description || '暂无描述' }}</p>
            <p class="mb-0 text-gray-700">
              题数 {{ detail.questionCount ?? items.length }} · 实际合计
              <b>{{ sumPaperScores(items) }}</b>
              分 / 申报总分 {{ detail.totalScore }} 分
            </p>
          </div>
          <div v-if="!isLocked" class="flex gap-2">
            <Button @click="openMetaEdit">编辑信息</Button>
            <Button type="primary" @click="pickerOpen = true">+ 从题库选题</Button>
          </div>
        </div>

        <div v-if="distribution.length" class="mt-3 flex flex-wrap gap-2">
          <Tag v-for="row in distribution" :key="row.type" color="blue">
            {{ row.typeName }} × {{ row.count }} = {{ row.subtotal }} 分
          </Tag>
        </div>

        <Alert
          v-if="!isLocked && scoreMismatch"
          type="warning"
          show-icon
          class="mt-3"
          :message="`各题分值之和(${sumPaperScores(items)})与申报总分(${detail.totalScore})不一致，请调整题目分值或总分`"
        />
      </Card>

      <Tabs v-if="!isLocked" v-model:activeKey="activeTabKey" class="mb-4">
        <TabPane key="manual" tab="手动选题">
          <p class="mb-0 text-gray-500">
            点击右上角「从题库选题」加入题目；加入后可在下方题目列表覆盖单题分值、调整顺序或移出。
            题干与选项实时读取题库；题目入卷时使用默认分。
          </p>
        </TabPane>

        <TabPane key="draw" tab="随机抽题">
          <div class="mb-3 flex items-center gap-2">
            <span class="text-sm text-gray-500">抽题模式：</span>
            <RadioGroup v-model:value="drawMode" button-style="solid" size="small">
              <RadioButton value="rules">规则列表</RadioButton>
              <RadioButton value="blueprint">矩阵蓝图</RadioButton>
            </RadioGroup>
          </div>

          <!-- 规则列表模式：既有逻辑保持不变 -->
          <div v-if="drawMode === 'rules'" class="mb-3 space-y-2">
            <div
              v-for="(rule, index) in ruleDrafts"
              :key="index"
              class="flex flex-wrap items-center gap-2"
            >
              <span class="w-14 text-gray-500">规则{{ index + 1 }}</span>
              <Select
                v-model:value="rule.type"
                :options="QUESTION_TYPE_OPTIONS"
                allow-clear
                placeholder="题型不限"
                class="w-32"
              />
              <Select
                v-model:value="rule.difficulty"
                :options="DIFFICULTY_OPTIONS"
                allow-clear
                placeholder="难度不限"
                class="w-32"
              />
              <Select
                v-model:value="rule.tagIds"
                :options="tagOptions"
                mode="multiple"
                allow-clear
                placeholder="标签（任一命中即可）"
                class="w-72"
              />
              <span class="text-gray-500">抽</span>
              <InputNumber v-model:value="rule.count" :min="1" :max="100" class="w-20" />
              <span class="text-gray-500">题</span>
              <Button
                size="small"
                danger
                :disabled="ruleDrafts.length <= 1"
                @click="removeRule(index)"
              >
                移除
              </Button>
            </div>
            <Button size="small" @click="addRule">+ 添加规则</Button>
          </div>

          <!-- 矩阵蓝图（双向细目表）模式 -->
          <div v-else class="mb-3">
            <!-- 1. 标签接口加载失败：显性呈现错误 Alert 且数据区隐藏（U-1 三态规范） -->
            <Alert
              v-if="tagsErrorText"
              type="error"
              show-icon
              :message="tagsErrorText"
              data-test="blueprint-tags-error"
              class="mb-3"
            />

            <!-- 2. 题库无任何标签空态 -->
            <Empty
              v-else-if="!tagsFetching && (!tags || tags.length === 0)"
              description="题库尚无标签，请先在题库管理中创建并打标"
              data-test="blueprint-no-tags"
              class="py-4"
            />

            <!-- 3. 正常细目表矩阵录入区 -->
            <div v-else class="space-y-3">
              <div class="flex items-center gap-2">
                <span class="text-sm text-gray-500 whitespace-nowrap">知识点标签：</span>
                <Select
                  v-model:value="selectedBlueprintTagIds"
                  mode="multiple"
                  :options="tagOptions"
                  placeholder="请勾选要纳入蓝图的知识点标签"
                  class="flex-1"
                  allow-clear
                  data-test="blueprint-tag-select"
                />
              </div>

              <div
                v-if="selectedBlueprintTagIds.length === 0"
                class="rounded border border-dashed border-gray-200 py-4 text-center text-sm text-gray-400"
              >
                请勾选要纳入蓝图的知识点标签以生成细目表矩阵
              </div>

              <div v-else class="overflow-x-auto rounded border border-gray-200">
                <table
                  data-test="blueprint-matrix-table"
                  class="min-w-full divide-y divide-gray-200 text-sm"
                >
                  <thead class="bg-gray-50 text-gray-700">
                    <tr>
                      <th class="px-4 py-2 text-left font-medium">知识点标签</th>
                      <th class="w-32 px-4 py-2 text-center font-medium">简单 (易)</th>
                      <th class="w-32 px-4 py-2 text-center font-medium">中等 (中)</th>
                      <th class="w-32 px-4 py-2 text-center font-medium">困难 (难)</th>
                    </tr>
                  </thead>
                  <tbody class="divide-y divide-gray-200 bg-white">
                    <tr v-for="tag in blueprintSelectedTags" :key="tag.id" class="hover:bg-gray-50">
                      <td class="px-4 py-2 font-medium text-gray-800">{{ tag.name }}</td>
                      <td class="px-4 py-2 text-center">
                        <InputNumber
                          :value="blueprintMatrix[tag.id as number]?.[1]"
                          :min="0"
                          :max="100"
                          size="small"
                          class="w-20"
                          placeholder="0"
                          @update:value="
                            (val: number | null) => updateBlueprintCell(tag.id as number, 1, val)
                          "
                        />
                      </td>
                      <td class="px-4 py-2 text-center">
                        <InputNumber
                          :value="blueprintMatrix[tag.id as number]?.[2]"
                          :min="0"
                          :max="100"
                          size="small"
                          class="w-20"
                          placeholder="0"
                          @update:value="
                            (val: number | null) => updateBlueprintCell(tag.id as number, 2, val)
                          "
                        />
                      </td>
                      <td class="px-4 py-2 text-center">
                        <InputNumber
                          :value="blueprintMatrix[tag.id as number]?.[3]"
                          :min="0"
                          :max="100"
                          size="small"
                          class="w-20"
                          placeholder="0"
                          @update:value="
                            (val: number | null) => updateBlueprintCell(tag.id as number, 3, val)
                          "
                        />
                      </td>
                    </tr>
                  </tbody>
                  <tfoot class="bg-gray-50 font-medium text-gray-700">
                    <tr>
                      <td class="px-4 py-2">覆盖度摘要</td>
                      <td colspan="3" class="px-4 py-2 text-right">
                        合计
                        <span class="font-bold text-blue-600">{{ blueprintTotalQuestions }}</span>
                        题
                      </td>
                    </tr>
                  </tfoot>
                </table>
              </div>
            </div>
          </div>

          <div class="flex gap-2">
            <Button :loading="drawFlow.state.phase === 'previewing'" @click="onPreview">
              预览抽题
            </Button>
            <template
              v-if="drawFlow.state.phase === 'previewed' || drawFlow.state.phase === 'committing'"
            >
              <Button :disabled="drawFlow.state.phase === 'committing'" @click="drawFlow.redraw()">
                重新抽取
              </Button>
              <Button
                type="primary"
                :loading="drawFlow.state.phase === 'committing'"
                @click="onCommit"
              >
                确认入卷
              </Button>
            </template>
          </div>

          <Alert
            v-if="drawFlow.state.error"
            type="error"
            show-icon
            :message="drawFlow.state.error"
            class="mt-3"
          />

          <div v-if="drawFlow.state.preview" class="mt-3">
            <Card
              v-for="ruleResult in drawFlow.state.preview.rules"
              :key="ruleResult.ruleIndex ?? 0"
              size="small"
              :title="`规则 ${(ruleResult.ruleIndex ?? 0) + 1}（抽中 ${(ruleResult.questions ?? []).length} 题）`"
              class="mb-2"
            >
              <ul class="m-0 list-disc pl-5">
                <li v-for="question in ruleResult.questions ?? []" :key="question.id" class="mb-1">
                  <Tag>{{ question.typeName ?? typeLabelOf(question.type) }}</Tag>
                  {{ question.content }}
                  <span class="text-gray-400">
                    默认 {{ question.score }} 分 · 难度 {{ difficultyLabelOf(question.difficulty) }}
                  </span>
                </li>
              </ul>
            </Card>
            <p class="mb-0 text-gray-500">
              共抽中 {{ drawFlow.state.preview.total }} 题；随机算法在后端，
              确认入卷会按同一规则重新抽取并追加到试卷（使用题目默认分）。
            </p>
          </div>
        </TabPane>
      </Tabs>

      <Card :title="`试卷题目（${items.length}）`">
        <Table
          :columns="questionColumns"
          :data-source="items"
          :pagination="false"
          :row-key="(row: PaperQuestionItemResponse) => row.questionId as number"
          size="middle"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'number'">
              {{ record.number }}
            </template>
            <template v-else-if="column.key === 'questionType'">
              <Tag v-if="record.questionDeleted" color="red">题目已删除</Tag>
              <Tag v-else>{{ typeLabelOf(record.questionType) }}</Tag>
            </template>
            <template v-else-if="column.key === 'content'">
              <span :class="record.questionDeleted ? 'text-gray-400' : ''">
                {{ record.questionDeleted ? '原题目已被删除，仅保留占位与分值' : record.content }}
              </span>
            </template>
            <template v-else-if="column.key === 'score'">
              <template v-if="!isLocked">
                <InputNumber
                  v-model:value="scoreDrafts[record.questionId as number]"
                  :min="0.5"
                  :max="999.9"
                  :step="0.5"
                  size="small"
                  class="w-24"
                  @blur="commitScore(record)"
                  @press-enter="commitScore(record)"
                />
                <div v-if="record.defaultScore !== undefined" class="text-xs text-gray-400">
                  默认 {{ record.defaultScore }} 分
                </div>
              </template>
              <template v-else>
                {{ record.score }}
                <div class="text-xs text-gray-400">默认 {{ record.defaultScore ?? '—' }} 分</div>
              </template>
            </template>
            <template v-else-if="column.key === 'actions'">
              <template v-if="!isLocked">
                <Button
                  type="link"
                  size="small"
                  :disabled="isFirst(record)"
                  @click="move(record, -1)"
                >
                  上移
                </Button>
                <Button
                  type="link"
                  size="small"
                  :disabled="isLast(record)"
                  @click="move(record, 1)"
                >
                  下移
                </Button>
                <Popconfirm title="将该题移出试卷？" @confirm="onRemove(record)">
                  <Button type="link" size="small" danger>移出</Button>
                </Popconfirm>
              </template>
              <span v-else class="text-gray-400">只读</span>
            </template>
          </template>
        </Table>
      </Card>

      <QuestionPickerModal
        v-model:open="pickerOpen"
        :exclude-ids="paperQuestionIds"
        @picked="onPick"
      />

      <Modal v-model:open="metaOpen" title="编辑试卷信息" :footer="null" :mask-closable="false">
        <Form layout="vertical">
          <FormItem label="试卷标题" required>
            <Input v-model:value="metaDraft.title" :maxlength="128" />
          </FormItem>
          <FormItem label="试卷描述">
            <Textarea v-model:value="metaDraft.description" :rows="2" :maxlength="512" />
          </FormItem>
          <FormItem label="申报总分" required>
            <InputNumber
              v-model:value="metaDraft.totalScore"
              :min="0"
              :max="9999.9"
              :step="0.5"
              class="w-48"
            />
            <p v-if="detail.questionCount" class="mb-0 mt-1 text-gray-400">
              已有 {{ detail.questionCount }} 题，当前各题分值之和为
              {{ sumPaperScores(items) }} 分， 不一致将保存失败。
            </p>
          </FormItem>
          <div class="flex justify-end gap-2">
            <Button @click="metaOpen = false">取消</Button>
            <Button type="primary" :loading="metaSaving" @click="onMetaSave">保存</Button>
          </div>
        </Form>
      </Modal>
    </template>
  </Spin>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
  FormItem,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  RadioButton,
  RadioGroup,
  Select,
  Spin,
  TabPane,
  Tabs,
  Tag,
  Table,
  Textarea,
  message,
  type TableColumnsType,
} from 'ant-design-vue';
import { computed, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';

import QuestionPickerModal from '@/components/question/QuestionPickerModal.vue';
import {
  addQuestions,
  commitRandomDraw,
  detail1,
  list as listTags,
  previewDraw as previewDrawContract,
  removeQuestion,
  update1,
  updateOrder,
  updateQuestionScore,
  type PaperDetailResponse,
  type PaperQuestionItemResponse,
  type QuestionResponse,
  type TagResponse,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { queryClient } from '@/api/queryClient';
import { createDrawFlow } from '@/hooks/useRandomDraw';
import { moveItem, scoreDistribution, sumPaperScores } from '@/utils/paperMath';
import { drawRulesErrors, toDrawRules, type DrawRuleDraft } from '@/utils/drawRules';
import {
  compileBlueprintToRules,
  totalBlueprintCount,
  validateBlueprint,
  type BlueprintMatrixState,
} from '@/utils/blueprint';
import {
  DIFFICULTY_OPTIONS,
  PAPER_STATUS_LOCKED,
  QUESTION_TYPE_OPTIONS,
  difficultyLabelOf,
  paperStatusLabel,
  typeLabelOf,
} from '@/utils/questionTypes';

const route = useRoute('/(dashboard)/teacher/papers/[id]');
const paperId = computed(() => Number(route.params.id));

const { data: detail, isFetching } = useQuery({
  queryKey: computed(() => ['paper', paperId.value]),
  queryFn: () =>
    unwrap<PaperDetailResponse>(
      detail1({ client, throwOnError: true, path: { id: paperId.value } })
    ),
});

const items = computed<PaperQuestionItemResponse[]>(() => detail.value?.questions ?? []);
const isLocked = computed(() => detail.value?.status === PAPER_STATUS_LOCKED);
const scoreMismatch = computed(() => {
  if (!detail.value || items.value.length === 0) return false;
  const declared = detail.value.totalScore ?? 0;
  return Math.abs(sumPaperScores(items.value) - declared) > 0.001;
});

const distribution = computed(() => scoreDistribution(items.value));

const paperQuestionIds = computed(() =>
  items.value.map((item) => item.questionId).filter((id): id is number => id !== undefined)
);

function invalidatePaper(): void {
  void queryClient.invalidateQueries({ queryKey: ['paper', paperId.value] });
  // 列表页的题数/状态随试卷变化，一并失效
  void queryClient.invalidateQueries({ queryKey: ['papers'] });
}

// ==================== 手动组卷与抽题 Tab ====================

const activeTabKey = ref('manual');
const pickerOpen = ref(false);

async function onPick(selected: QuestionResponse[]): Promise<void> {
  const existing = new Set(paperQuestionIds.value);
  const fresh = selected.filter(
    (question) => question.id !== undefined && !existing.has(question.id)
  );
  if (fresh.length === 0) {
    message.info('所选题目均已在试卷中');
    return;
  }
  // 单次批量入卷（后端整批单事务全有全无，杜绝半批静默）；不传 score 即用题目默认分
  try {
    await unwrap(
      addQuestions({
        client,
        throwOnError: true,
        path: { id: paperId.value },
        body: {
          items: fresh.map((question) => ({ questionId: question.id as number })),
        },
      })
    );
    message.success(`已加入 ${fresh.length} 题`);
    invalidatePaper();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '批量加入试卷失败');
  }
}

/** 卷内分值编辑草稿：key=questionId。blur/回车时才提交，避免逐键触发 PUT。 */
const scoreDrafts = reactive<Record<number, number | undefined>>({});

watch(
  items,
  (list) => {
    for (const item of list) {
      const key = item.questionId;
      if (key !== undefined && !(key in scoreDrafts)) {
        scoreDrafts[key] = item.score ?? undefined;
      }
    }
  },
  { immediate: true }
);

async function commitScore(record: PaperQuestionItemResponse): Promise<void> {
  const key = record.questionId;
  if (key === undefined) return;
  const next = scoreDrafts[key];
  if (next === undefined || !Number.isFinite(next)) {
    scoreDrafts[key] = record.score ?? undefined;
    message.warning('分值须在 0.5–999.9 之间');
    return;
  }
  if (Math.abs(next - (record.score ?? 0)) < 0.001) {
    return;
  }
  try {
    await unwrap(
      updateQuestionScore({
        client,
        throwOnError: true,
        path: { id: paperId.value, questionId: key },
        body: { score: next },
      })
    );
    invalidatePaper();
  } catch (error) {
    scoreDrafts[key] = record.score ?? undefined;
    message.error(error instanceof Error ? error.message : '分值保存失败');
  }
}

function rowIndexOf(record: PaperQuestionItemResponse): number {
  return items.value.findIndex((item) => item.questionId === record.questionId);
}

function isFirst(record: PaperQuestionItemResponse): boolean {
  return rowIndexOf(record) <= 0;
}

function isLast(record: PaperQuestionItemResponse): boolean {
  return rowIndexOf(record) >= items.value.length - 1;
}

/**
 * 调序：本地 moveItem 算出新顺序后，把全量 questionId 列表交给后端重排题号
 * （契约要求传入列表与现有题目一一对应，随机算法不涉及、排序逻辑也只在前端算目标顺序）。
 */
async function move(record: PaperQuestionItemResponse, delta: number): Promise<void> {
  const from = rowIndexOf(record);
  if (from < 0) return;
  const ids = items.value.map((item) => item.questionId as number);
  const next = moveItem(ids, from, delta);
  if (next.every((id, index) => id === ids[index])) {
    return;
  }
  try {
    await unwrap(
      updateOrder({
        client,
        throwOnError: true,
        path: { id: paperId.value },
        body: { questionIds: next },
      })
    );
    invalidatePaper();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '调序失败');
  }
}

async function onRemove(record: PaperQuestionItemResponse): Promise<void> {
  if (record.questionId === undefined) return;
  try {
    await unwrap(
      removeQuestion({
        client,
        throwOnError: true,
        path: { id: paperId.value, questionId: record.questionId },
      })
    );
    delete scoreDrafts[record.questionId];
    message.success('已移出试卷');
    invalidatePaper();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '移出失败');
  }
}

// ==================== 试卷元信息 ====================

const metaOpen = ref(false);
const metaSaving = ref(false);
const metaDraft = reactive<{ title: string; description: string; totalScore: number | undefined }>({
  title: '',
  description: '',
  totalScore: undefined,
});

function openMetaEdit(): void {
  if (!detail.value) return;
  metaDraft.title = detail.value.title ?? '';
  metaDraft.description = detail.value.description ?? '';
  metaDraft.totalScore = detail.value.totalScore ?? undefined;
  metaOpen.value = true;
}

async function onMetaSave(): Promise<void> {
  if (!metaDraft.title.trim() || metaDraft.totalScore === undefined) {
    message.warning('标题与申报总分不能为空');
    return;
  }
  metaSaving.value = true;
  try {
    await unwrap(
      update1({
        client,
        throwOnError: true,
        path: { id: paperId.value },
        body: {
          title: metaDraft.title.trim(),
          description: metaDraft.description,
          totalScore: metaDraft.totalScore,
        },
      })
    );
    message.success('试卷信息已保存');
    metaOpen.value = false;
    invalidatePaper();
  } catch (error) {
    // 后端总分校验失败（各题分值之和 ≠ 总分）会返回 400 + 中文原因，直接透传
    message.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    metaSaving.value = false;
  }
}

// ==================== 标签随机抽题与矩阵蓝图 ====================

const {
  data: tags,
  error: rawTagsError,
  isFetching: tagsFetching,
} = useQuery({
  queryKey: ['tags'],
  queryFn: () => unwrap<TagResponse[]>(listTags({ client, throwOnError: true })),
});

const tagsErrorText = computed<string | null>(() => {
  const caught = rawTagsError?.value;
  if (!caught) return null;
  return caught instanceof Error ? caught.message : String(caught);
});

const tagOptions = computed(() =>
  (tags.value ?? []).map((tag) => ({ value: tag.id as number, label: tag.name ?? String(tag.id) }))
);

// --- 规则列表模式状态与操作 ---
function emptyRule(): DrawRuleDraft {
  return { type: undefined, difficulty: undefined, tagIds: [], count: 5 };
}

const ruleDrafts = ref<DrawRuleDraft[]>([emptyRule()]);

function addRule(): void {
  ruleDrafts.value.push(emptyRule());
}

function removeRule(index: number): void {
  if (ruleDrafts.value.length > 1) {
    ruleDrafts.value.splice(index, 1);
  }
}

// --- 矩阵蓝图（双向细目表）模式状态与操作 ---
const drawMode = ref<'rules' | 'blueprint'>('rules');
const selectedBlueprintTagIds = ref<number[]>([]);
const blueprintMatrix = reactive<BlueprintMatrixState>({});

const blueprintSelectedTags = computed(() => {
  const tagMap = new Map((tags.value ?? []).map((t) => [t.id as number, t]));
  return selectedBlueprintTagIds.value
    .map((id) => tagMap.get(id))
    .filter((t): t is TagResponse => t !== undefined);
});

function updateBlueprintCell(
  tagId: number,
  difficulty: number,
  value: number | null | undefined
): void {
  if (!blueprintMatrix[tagId]) {
    blueprintMatrix[tagId] = {};
  }
  blueprintMatrix[tagId][difficulty] = value ?? undefined;
}

const blueprintTotalQuestions = computed(() =>
  totalBlueprintCount(selectedBlueprintTagIds.value, blueprintMatrix)
);

function resetBlueprint(): void {
  for (const key of Object.keys(blueprintMatrix)) {
    delete blueprintMatrix[Number(key)];
  }
}

/** 生产注入：走 gen:api 生成客户端；单测注入 mock（见 useRandomDraw.spec.ts）。 */
const drawFlow = createDrawFlow({
  previewDraw: (rules) =>
    unwrap(previewDrawContract({ client, throwOnError: true, body: { rules } })),
  commitDraw: (id, rules) =>
    unwrap(commitRandomDraw({ client, throwOnError: true, path: { id }, body: { rules } })),
});

// 规则一旦被修改或切换模式，旧预览即作废：预览结果永远与当前规则一一对应
watch(
  [ruleDrafts, drawMode, selectedBlueprintTagIds, blueprintMatrix],
  () => {
    drawFlow.invalidate();
  },
  { deep: true }
);

async function onPreview(): Promise<void> {
  if (drawMode.value === 'blueprint') {
    const errors = validateBlueprint(selectedBlueprintTagIds.value, blueprintMatrix);
    if (errors.length > 0) {
      message.warning(errors[0] as string);
      return;
    }
    await drawFlow.preview(compileBlueprintToRules(selectedBlueprintTagIds.value, blueprintMatrix));
    return;
  }
  const errors = drawRulesErrors(ruleDrafts.value);
  if (errors.length > 0) {
    message.warning(errors[0] as string);
    return;
  }
  await drawFlow.preview(toDrawRules(ruleDrafts.value));
}

async function onCommit(): Promise<void> {
  const ok = await drawFlow.commit(paperId.value);
  if (ok) {
    message.success('抽题已入卷');
    if (drawMode.value === 'rules') {
      ruleDrafts.value = [emptyRule()];
    } else {
      resetBlueprint();
    }
    invalidatePaper();
  }
}

const questionColumns: TableColumnsType = [
  { title: '题号', key: 'number', width: 70 },
  { title: '题型', key: 'questionType', width: 110 },
  { title: '题干', key: 'content', ellipsis: true },
  { title: '卷内分值', key: 'score', width: 140 },
  { title: '操作', key: 'actions', width: 200 },
];
</script>
