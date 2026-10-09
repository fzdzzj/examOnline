<template>
  <div>
    <Card title="我的错题本" class="mb-4">
      <template #extra>
        <span class="hint">按考试分组归集错题（得分小于满分，含部分对），题目解析取自题库。</span>
      </template>

      <!-- 查询失败三态收口：显性呈现 Alert 且文案为后端 message 原文 -->
      <Alert
        v-if="queryError"
        type="error"
        show-icon
        class="mb-3"
        :message="queryError"
        data-test="wrong-questions-error"
      />

      <Spin :spinning="isFetching">
        <div v-if="!queryError" data-test="wrong-questions-content">
          <!-- 空态：暂无错题 -->
          <Empty v-if="total === 0 || groups.length === 0" description="暂无错题" class="my-8" />

          <!-- 按考试分组呈现 -->
          <div v-else class="space-y-6">
            <div
              v-for="group in groups"
              :key="group.examId"
              class="exam-group-card border rounded-lg p-4 bg-gray-50/50"
            >
              <div class="flex items-center justify-between border-b pb-2 mb-3">
                <div class="flex items-center gap-2">
                  <span class="font-bold text-base text-gray-800">{{ group.examTitle }}</span>
                  <Tag color="blue">考试 ID: {{ group.examId }}</Tag>
                  <Tag color="orange">{{ (group.wrongQuestions || []).length }} 道错题</Tag>
                </div>
                <span v-if="group.examTime" class="text-xs text-gray-400">
                  考试时间：{{ formatDate(group.examTime) }}
                </span>
              </div>

              <!-- 组内错题列表 -->
              <div class="space-y-4">
                <div
                  v-for="item in group.wrongQuestions"
                  :key="item.questionId"
                  class="bg-white p-4 rounded border border-gray-200 shadow-sm"
                >
                  <div class="flex items-center justify-between mb-2">
                    <div class="flex items-center gap-2">
                      <span class="font-semibold text-gray-700">
                        第 {{ item.questionNumber }} 题
                      </span>
                      <Tag color="cyan">{{ item.questionType }}</Tag>
                      <Tag v-if="item.myScore === 0" color="error">
                        全错 (0/{{ item.fullScore }}分)
                      </Tag>
                      <Tag v-else color="warning">
                        部分对 ({{ item.myScore }}/{{ item.fullScore }}分)
                      </Tag>
                    </div>
                  </div>

                  <!-- 题干 -->
                  <div class="text-gray-800 mb-3 whitespace-pre-wrap font-medium">
                    {{ item.questionContent }}
                  </div>

                  <!-- 客观题选项 -->
                  <div
                    v-if="item.choices && item.choices.length > 0"
                    class="mb-3 pl-2 space-y-1 text-sm text-gray-600"
                  >
                    <div v-for="(choice, idx) in item.choices" :key="idx">
                      {{ String.fromCharCode(65 + idx) }}. {{ choice }}
                    </div>
                  </div>

                  <!-- 作答与标准答案对比 -->
                  <div
                    class="bg-gray-50 p-2.5 rounded text-sm mb-3 grid grid-cols-1 md:grid-cols-2 gap-2"
                  >
                    <div class="text-red-600">
                      <span class="font-medium">我的作答：</span>
                      <span>{{ item.myAnswer ? item.myAnswer : '（未作答）' }}</span>
                    </div>
                    <div class="text-green-600">
                      <span class="font-medium">正确答案：</span>
                      <span>{{ item.correctAnswer ? item.correctAnswer : '（无）' }}</span>
                    </div>
                  </div>

                  <!-- 答案解析（严格取自题库，为空时占位） -->
                  <div class="text-sm bg-blue-50/60 p-2.5 rounded border border-blue-100 mb-2">
                    <span class="font-semibold text-blue-800">答案解析：</span>
                    <span v-if="item.analysis" class="text-gray-700 whitespace-pre-wrap">
                      {{ item.analysis }}
                    </span>
                    <span v-else class="text-gray-400 italic">暂无解析</span>
                  </div>

                  <!-- 判分依据或评语（可选） -->
                  <div v-if="item.scoreDetail" class="text-xs text-gray-500 pl-1">
                    <span>判分依据/评语：{{ item.scoreDetail }}</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- 分页控件 -->
            <div class="flex justify-end pt-4">
              <Pagination
                v-model:current="currentPage"
                v-model:pageSize="pageSize"
                :total="total"
                :show-size-changer="false"
                :show-total="(t) => `共 ${t} 场已发布考试`"
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
import { Alert, Card, Empty, Pagination, Spin, Tag } from 'ant-design-vue';
import { computed, ref } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import dayjs from 'dayjs';

import { myWrongQuestions, type WrongQuestionPageResponse } from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';
import { ApiError } from '@/api/types';

const currentPage = ref(1);
const pageSize = ref(10);

const {
  data: pageData,
  isFetching,
  error,
} = useQuery({
  queryKey: computed(() => ['student-wrong-questions', currentPage.value, pageSize.value] as const),
  queryFn: () =>
    unwrap<WrongQuestionPageResponse>(
      myWrongQuestions({
        client,
        throwOnError: true,
        query: { page: currentPage.value, size: pageSize.value },
      })
    ),
});

const total = computed(() => pageData.value?.total ?? 0);
const groups = computed(() => pageData.value?.groups ?? []);

const queryError = computed<string | null>(() => {
  const caught = error.value;
  if (!caught) return null;
  return caught instanceof ApiError || caught instanceof Error ? caught.message : '错题本加载失败';
});

function formatDate(dateStr: string): string {
  try {
    return dayjs(dateStr).format('YYYY-MM-DD HH:mm');
  } catch {
    return dateStr;
  }
}

function onPageChange(page: number): void {
  currentPage.value = page;
}
</script>

<style scoped>
.mb-2 {
  margin-bottom: 0.5rem;
}
.mb-3 {
  margin-bottom: 0.75rem;
}
.mb-4 {
  margin-bottom: 1rem;
}
.hint {
  color: #999;
  font-size: 12px;
  font-weight: normal;
}
</style>
