<template>
  <div>
    <!-- 查询失败：错误 Alert 显性呈现后端 message，主体隐藏（U-1 三态分离） -->
    <Alert
      v-if="analyticsErrorText || recommendationsErrorText"
      type="error"
      show-icon
      class="mb-3"
      data-test="analytics-error"
      :message="analyticsErrorText || recommendationsErrorText"
    />
    <Spin v-else-if="loading" />
    <template v-else>
      <template v-if="hasData">
        <!-- ==================== 总览统计卡 ==================== -->
        <Row :gutter="[16, 16]" class="mb-4">
          <Col :span="12" :md="6">
            <Card size="small">
              <Statistic title="参测场次" :value="overview?.totalExams ?? 0" suffix="场" />
            </Card>
          </Col>
          <Col :span="12" :md="6">
            <Card size="small">
              <Statistic
                title="历史均分"
                :value="averageScoreValue"
                :precision="averageScorePrecision"
              />
            </Card>
          </Col>
          <Col :span="12" :md="6">
            <Card size="small">
              <Statistic
                title="最高分"
                :value="highestScoreValue"
                :precision="highestScorePrecision"
              />
            </Card>
          </Col>
          <Col :span="12" :md="6">
            <Card size="small">
              <Statistic
                title="及格率"
                :value="passRateValue"
                :precision="passRatePrecision"
                suffix="%"
              />
            </Card>
          </Col>
        </Row>

        <!-- ==================== 成绩走势 ==================== -->
        <p class="mb-2 text-sm font-semibold">成绩走势</p>
        <div v-if="trend.length" class="mb-4">
          <div v-for="t in trend" :key="t.examId" class="mb-1 flex items-center gap-2">
            <span class="text-sm w-40 truncate" :title="t.examTitle">{{ t.examTitle }}</span>
            <Progress
              class="flex-1"
              :percent="toPercent(t.scoreRate)"
              size="small"
              :status="rateStatus(t.scoreRate)"
              :format="() => `${t.studentScore ?? 0} / ${t.totalScore ?? 0} 分`"
            />
            <span class="text-xs text-gray-400 w-24 text-right">{{ formatTime(t.examTime) }}</span>
          </div>
        </div>

        <!-- ==================== 知识点掌握度 ==================== -->
        <p class="mb-2 text-sm font-semibold">知识点掌握度</p>
        <Alert
          v-if="tagMastery.length === 0"
          type="info"
          show-icon
          class="mb-4"
          message="暂无知识点掌握度数据"
          description="已参加的考试题目尚未挂知识点标签，无法聚合知识点掌握度。"
        />
        <div v-else class="mb-4">
          <div
            v-for="tag in tagMastery"
            :key="tag.tagId"
            class="mb-1 flex items-center gap-2"
            data-test="tag-mastery"
          >
            <span class="text-sm w-32 truncate" :title="tag.tagName">{{ tag.tagName }}</span>
            <Tag :color="masteryTagColor(tag)">{{ masteringLabel(tag) }}</Tag>
            <Progress
              class="flex-1"
              :percent="toPercent(tag.masteryRate)"
              size="small"
              :status="masteryStatus(tag)"
              :format="() => `${toPercent(tag.masteryRate)}%`"
            />
          </div>
        </div>

        <!-- ==================== 薄弱点靶向练习推荐 ==================== -->
        <p class="mb-2 text-sm font-semibold">薄弱点靶向练习</p>
        <Alert
          v-if="recommendationList.length === 0"
          type="info"
          show-icon
          class="mb-4"
          message="暂无薄弱点推荐"
          description="当前没有可推荐的主观机判客观题（可能没有薄弱知识点，或可推荐题目已全部消灭）。"
        />
        <div v-else class="mb-2 space-y-2">
          <Card
            v-for="r in recommendationList"
            :key="r.questionId"
            size="small"
            data-test="recommendation-card"
          >
            <div class="flex items-center justify-between gap-2">
              <div class="min-w-0">
                <div class="text-sm font-medium text-gray-800 truncate">{{ r.stem }}</div>
                <div class="mt-1 flex flex-wrap items-center gap-2 text-xs text-gray-500">
                  <Tag>{{ r.type }}</Tag>
                  <span>知识点：{{ r.tagName }}</span>
                  <span class="text-red-500" :title="`当前掌握度 ${toPercent(r.tagMasteryRate)}%`">
                    {{ r.recommendReason }}
                  </span>
                </div>
              </div>
              <Button type="primary" size="small" data-test="start-practice" @click="goPractice">
                去练习
              </Button>
            </div>
          </Card>
        </div>
      </template>

      <!-- 空态：无任何已发布考试/答卷 → 引导（非错误态） -->
      <template v-else>
        <div class="py-8 text-center">
          <Empty description="暂无学情数据" />
          <p class="mt-2 text-gray-500">
            完成并发布考试成绩后，即可查看你的跨考试学情画像与薄弱点靶向练习推荐。
          </p>
        </div>
      </template>
    </template>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Progress,
  Row,
  Spin,
  Statistic,
  Tag,
} from 'ant-design-vue';
import dayjs from 'dayjs';
import { computed } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { useRouter } from 'vue-router';

import {
  myLearningAnalytics,
  myRecommendations,
  type RecommendedPracticeItem,
  type TagMasteryItem,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';

/**
 * 学生跨考试学情画像与薄弱点靶向练习（add-student-learning-analytics）：
 * 纯渲染后端 `GET /api/scores/my/analytics` 与 `GET /api/scores/my/recommendations`
 * 返回的数据，概览/走势/掌握度/薄弱点口径百分百以后端为准，前端零本地推算。
 *
 * <p>三态分离：查询中 Spin、无数据 Empty 引导、API 失败 Alert 显性报错。
 * 「去练习」跳转既有错题刷题页（/student/wrong-questions），实际判分复用后端同一判分核心。
 * 单个推荐题仅带题干与推荐原因，不含可作答选项——作答必须在刷题流程完成。
 */
const router = useRouter();

const {
  data: analytics,
  isFetching: analyticsFetching,
  error: analyticsError,
} = useQuery({
  queryKey: ['my-learning-analytics'] as const,
  queryFn: () => unwrap(myLearningAnalytics({ client, throwOnError: true })),
  retry: 0,
});

const { data: recommendations, error: recommendationsError } = useQuery({
  queryKey: ['my-recommendations'] as const,
  queryFn: () =>
    unwrap<RecommendedPracticeItem[]>(myRecommendations({ client, throwOnError: true })),
  retry: 0,
});

const analyticsErrorText = computed(() =>
  analyticsError.value instanceof Error ? analyticsError.value.message : null
);
const recommendationsErrorText = computed(() =>
  recommendationsError.value instanceof Error ? recommendationsError.value.message : null
);

const loading = computed(() => analyticsFetching.value);

const overview = computed(() => analytics.value?.overview);
const trend = computed(() => analytics.value?.trend ?? []);
const tagMastery = computed(() => analytics.value?.tagMastery ?? []);
const recommendationList = computed(() => recommendations.value ?? []);

/** 空态判定：参测场次为 0 视为「无学情数据」→ 引导空态（后端空态对象返回 200）。 */
const hasData = computed(() => (overview.value?.totalExams ?? 0) > 0);

const averageScoreValue = computed(() => overview.value?.averageScore ?? 0);
const averageScorePrecision = computed(() =>
  typeof overview.value?.averageScore === 'number' ? 1 : undefined
);
const highestScoreValue = computed(() => overview.value?.highestScore ?? 0);
const highestScorePrecision = computed(() =>
  typeof overview.value?.highestScore === 'number' ? 1 : undefined
);
const passRateValue = computed(() => {
  const rate = overview.value?.passRate;
  return rate === undefined || rate === null ? 0 : Number((rate * 100).toFixed(1));
});
const passRatePrecision = computed(() =>
  overview.value?.passRate === undefined || overview.value?.passRate === null ? undefined : 1
);

/** 0~100 整数百分比；null/缺失返回 0。 */
function toPercent(rate: number | undefined | null): number {
  if (rate === undefined || rate === null) return 0;
  return Math.round(rate * 100);
}

/** 进度条状态：≥60% 绿，<40% 红，中间进行中（与单场分析报告同口径）。 */
function rateStatus(rate: number | undefined | null): 'success' | 'exception' | 'active' {
  if (rate === undefined || rate === null) return 'active';
  if (rate >= 0.6) return 'success';
  if (rate < 0.4) return 'exception';
  return 'active';
}

/** 知识点掌握度进度条状态：薄弱（<75%）红 / 及格（75%~90%）黄 / 达标（≥90%）绿。 */
function masteryStatus(tag: TagMasteryItem): 'success' | 'exception' | 'active' {
  const rate = tag.masteryRate ?? 0;
  if (rate >= 0.9) return 'success';
  if (rate >= 0.75) return 'active';
  return 'exception';
}

/** 掌握度标签配色：薄弱=error 红 / 及格=warning 黄 / 达标=success 绿。 */
function masteryTagColor(tag: TagMasteryItem): 'error' | 'warning' | 'success' {
  const rate = tag.masteryRate ?? 0;
  if (rate >= 0.9) return 'success';
  if (rate >= 0.75) return 'warning';
  return 'error';
}

function masteringLabel(tag: TagMasteryItem): string {
  const rate = tag.masteryRate ?? 0;
  if (rate >= 0.9) return '达标';
  if (rate >= 0.75) return '及格';
  return '薄弱';
}

function formatTime(value: string | undefined | null): string {
  return value ? dayjs(value).format('MM-DD HH:mm') : '';
}

/** 去练习：跳转错题刷题页（既有重练/刷题判定核心，单个推荐题作答须在刷题流程完成）。 */
function goPractice(): void {
  void router.push('/student/wrong-questions');
}
</script>
