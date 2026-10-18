<template>
  <div class="space-y-4">
    <Card title="我的收藏" class="mb-4">
      <template #extra>
        <span class="hint text-xs text-gray-500">
          学生自主收藏的优质题目知识库，支持按题型检索与解析回顾
        </span>
      </template>

      <!-- 检索与筛选栏 -->
      <div class="flex flex-wrap items-center justify-between gap-3 mb-4">
        <div class="flex flex-wrap items-center gap-3">
          <RadioGroup v-model:value="selectedType" button-style="solid" @change="onFilterChange">
            <RadioButton :value="undefined">全部题型</RadioButton>
            <RadioButton :value="1">单选题</RadioButton>
            <RadioButton :value="2">多选题</RadioButton>
            <RadioButton :value="3">判断题</RadioButton>
            <RadioButton :value="4">简答题</RadioButton>
            <RadioButton :value="5">填空题</RadioButton>
          </RadioGroup>
        </div>

        <div class="w-full sm:w-72">
          <InputSearch
            v-model:value="keywordInput"
            placeholder="搜索题干关键字..."
            allow-clear
            @search="onSearch"
          />
        </div>
      </div>

      <!-- 查询错误提示 -->
      <Alert
        v-if="queryError"
        type="error"
        show-icon
        class="mb-3"
        :message="queryError"
        data-test="favorites-error"
      />

      <!-- 题目列表 -->
      <Spin :spinning="isFetching">
        <div v-if="!queryError">
          <div
            v-if="favoritesList.length === 0"
            class="my-12 text-center"
            data-test="favorites-empty"
          >
            <Empty description="暂无收藏题目" />
          </div>

          <div v-else class="space-y-4" data-test="favorites-list">
            <div
              v-for="item in favoritesList"
              :key="item.id ?? item.questionId"
              class="p-4 border rounded-lg bg-white shadow-sm hover:shadow transition-shadow space-y-3"
              data-test="favorite-card"
            >
              <!-- 卡片头部：题型、难度、收藏时间与取消收藏按钮 -->
              <div class="flex items-center justify-between border-b pb-2">
                <div class="flex items-center gap-2">
                  <Tag color="cyan">{{ item.typeName || getTypeName(item.type) }}</Tag>
                  <Tag v-if="item.difficulty" :color="getDifficultyColor(item.difficulty)">
                    {{ getDifficultyLabel(item.difficulty) }}
                  </Tag>
                  <span class="text-xs text-gray-400">
                    收藏于 {{ formatTime(item.createdTime) }}
                  </span>
                </div>
                <Popconfirm
                  title="确定取消收藏这道题目吗？"
                  ok-text="确定"
                  cancel-text="取消"
                  @confirm="handleUnfavorite(item.questionId!)"
                >
                  <Button type="link" danger size="small" data-test="unfavorite-btn">
                    取消收藏
                  </Button>
                </Popconfirm>
              </div>

              <!-- 题干 -->
              <div class="text-gray-800 text-base font-medium whitespace-pre-wrap">
                {{ item.content }}
              </div>

              <!-- 题干图片 -->
              <QuestionImage :src="item.imageUrl" />

              <!-- 客观题选项 -->
              <div
                v-if="item.choices && item.choices.length > 0"
                class="pl-2 space-y-1 text-sm text-gray-600 bg-gray-50/50 p-2 rounded"
              >
                <div v-for="(choice, idx) in item.choices" :key="idx">
                  {{ String.fromCharCode(65 + idx) }}. {{ choice }}
                </div>
              </div>

              <!-- 标准答案 -->
              <div class="bg-gray-50 p-2.5 rounded text-sm flex items-center gap-2">
                <span class="font-medium text-gray-700">参考答案：</span>
                <span class="font-semibold text-green-600" data-test="correct-answer">
                  {{ formatCorrectAnswer(item) }}
                </span>
              </div>

              <!-- 解析折叠面板 -->
              <Collapse :bordered="false" class="bg-blue-50/30">
                <CollapsePanel key="analysis" header="查看参考解析" force-render>
                  <div class="text-sm text-gray-700 whitespace-pre-wrap" data-test="analysis-text">
                    {{ item.analysis || '暂无解析' }}
                  </div>
                </CollapsePanel>
              </Collapse>
            </div>

            <!-- 分页控件 -->
            <div class="flex justify-end pt-4">
              <Pagination
                v-model:current="currentPage"
                v-model:pageSize="pageSize"
                :total="total"
                :show-size-changer="false"
                :show-total="(t) => `共 ${t} 道收藏题目`"
                @change="onPageChange"
              />
            </div>
          </div>
        </div>
      </Spin>
    </Card>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  Collapse,
  CollapsePanel,
  Empty,
  InputSearch,
  message,
  Pagination,
  Popconfirm,
  RadioButton,
  RadioGroup,
  Spin,
  Tag,
} from 'ant-design-vue';
import { computed, ref } from 'vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import dayjs from 'dayjs';

import { listFavorites, unfavorite, type StudentQuestionFavoriteResponse } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { ApiError } from '@/api/types';
import QuestionImage from '@/components/question/QuestionImage.vue';

const queryClient = useQueryClient();

const currentPage = ref(1);
const pageSize = ref(10);
const selectedType = ref<number | undefined>(undefined);
const keywordInput = ref('');
const activeKeyword = ref('');

const queryKey = computed(
  () =>
    [
      'student-favorites',
      currentPage.value,
      pageSize.value,
      selectedType.value,
      activeKeyword.value,
    ] as const
);

const { data, error, isFetching } = useQuery({
  queryKey,
  queryFn: async () => {
    return unwrap(
      listFavorites({
        client,
        query: {
          page: currentPage.value,
          size: pageSize.value,
          type: selectedType.value,
          keyword: activeKeyword.value || undefined,
        },
      })
    );
  },
});

const favoritesList = computed<StudentQuestionFavoriteResponse[]>(() => {
  return data.value?.list ?? [];
});

const total = computed<number>(() => {
  return data.value?.total ?? 0;
});

const queryError = computed<string | null>(() => {
  if (!error.value) return null;
  if (error.value instanceof ApiError) return error.value.message;
  return error.value instanceof Error ? error.value.message : '网络连接异常';
});

function onFilterChange(): void {
  currentPage.value = 1;
}

function onSearch(val: string): void {
  activeKeyword.value = val.trim();
  currentPage.value = 1;
}

function onPageChange(page: number): void {
  currentPage.value = page;
}

async function handleUnfavorite(questionId: number): Promise<void> {
  try {
    await unwrap(
      unfavorite({
        client,
        path: { questionId },
      })
    );
    message.success('已取消收藏');
    await queryClient.invalidateQueries({ queryKey: ['student-favorites'] });
  } catch (err) {
    const msg = err instanceof ApiError ? err.message : '取消收藏失败';
    message.error(msg);
  }
}

function getTypeName(type?: number): string {
  switch (type) {
    case 1:
      return '单选题';
    case 2:
      return '多选题';
    case 3:
      return '判断题';
    case 4:
      return '简答题';
    case 5:
      return '填空题';
    default:
      return '未知题型';
  }
}

function getDifficultyLabel(d?: number): string {
  switch (d) {
    case 1:
      return '基础';
    case 2:
      return '中等';
    case 3:
      return '进阶';
    default:
      return '标准';
  }
}

function getDifficultyColor(d?: number): string {
  switch (d) {
    case 1:
      return 'green';
    case 2:
      return 'orange';
    case 3:
      return 'red';
    default:
      return 'blue';
  }
}

function formatTime(t?: string): string {
  if (!t) return '';
  return dayjs(t).format('YYYY-MM-DD HH:mm');
}

function formatCorrectAnswer(item: StudentQuestionFavoriteResponse): string {
  if (!item.correctAnswer) return '（无）';
  if (item.type === 5) {
    return item.correctAnswer.split('|').join(' / ');
  }
  return item.correctAnswer;
}
</script>
