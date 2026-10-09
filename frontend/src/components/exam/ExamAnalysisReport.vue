<template>
  <div>
    <!-- 查询失败：错误 Alert 显性呈现后端 message，数据区隐藏（U-1 三态口径） -->
    <Alert v-if="errorText" type="error" show-icon :message="errorText" class="mb-3" />

    <template v-else>
      <template v-if="analysis && hasData && overview">
        <!-- ==================== 班级概览统计卡 ==================== -->
        <Row :gutter="[16, 16]" class="mb-4">
          <Col :span="12" :md="8" :lg="4">
            <Card size="small">
              <Statistic title="应考" :value="overview.expectedCount ?? 0" suffix="人" />
            </Card>
          </Col>
          <Col :span="12" :md="8" :lg="4">
            <Card size="small">
              <Statistic title="实考" :value="overview.actualCount ?? 0" suffix="人" />
            </Card>
          </Col>
          <Col :span="12" :md="8" :lg="4">
            <Card size="small">
              <Statistic title="缺席" :value="overview.absenceCount ?? 0" suffix="人" />
            </Card>
          </Col>
          <Col :span="12" :md="8" :lg="4">
            <Card size="small">
              <Statistic
                title="平均分"
                :value="overview.averageScore ?? 0"
                :precision="averageScorePrecision"
              />
            </Card>
          </Col>
          <Col :span="12" :md="8" :lg="4">
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

        <!-- ==================== 分数段分布 ==================== -->
        <p class="mb-2 text-sm font-semibold">分数段分布</p>
        <div class="mb-4">
          <div v-for="band in scoreBands" :key="band.band" class="mb-1 flex items-center gap-2">
            <span class="text-sm w-14">{{ band.band }}分</span>
            <Progress
              class="flex-1"
              :percent="bandPercent(band.count ?? 0)"
              size="small"
              :format="() => `${band.count ?? 0}人`"
            />
          </div>
        </div>

        <!-- ==================== 逐题指标 ==================== -->
        <p class="mb-2 text-sm font-semibold">逐题指标</p>
        <Table
          class="mb-4"
          :columns="questionColumns"
          :data-source="questionRows"
          :pagination="false"
          size="small"
          row-key="order"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'scoreRate'">
              <Progress
                :percent="toPercent((record as QuestionStatItem).scoreRate)"
                size="small"
                :status="rateStatus((record as QuestionStatItem).scoreRate)"
              />
            </template>
            <template v-else-if="column.key === 'discrimination'">
              <span>{{ discrimLabel((record as QuestionStatItem).discrimination) }}</span>
            </template>
          </template>
        </Table>

        <!-- ==================== 知识点薄弱 ==================== -->
        <p class="mb-2 text-sm font-semibold">知识点薄弱</p>
        <!-- 优雅降级：hasTagDimension=false → 提示「题库未打知识点标签」，非错误态 -->
        <Alert
          v-if="!hasTagDimension"
          type="info"
          show-icon
          class="mb-4"
          message="题库未打知识点标签"
          description="本场试卷中的题目均未挂知识点标签，无法聚合知识点得分率。"
        />
        <div v-else-if="tagWeakness.length" class="mb-4">
          <div v-for="item in tagWeakness" :key="item.tagId" class="mb-1 flex items-center gap-2">
            <span class="text-sm w-32 truncate" :title="item.tagName">{{ item.tagName }}</span>
            <Progress
              class="flex-1"
              :percent="toPercent(item.scoreRate)"
              size="small"
              :status="rateStatus(item.scoreRate)"
              :format="() => `${toPercent(item.scoreRate)}%`"
            />
          </div>
        </div>
        <p v-else class="mb-4 text-gray-500">暂无可聚合的知识点数据。</p>

        <!-- ==================== 学生关注名单 ==================== -->
        <p class="mb-2 text-sm font-semibold">学生关注名单（低于及格线 60 分）</p>
        <Table
          :columns="focusColumns"
          :data-source="focusRows"
          :pagination="false"
          size="small"
          row-key="studentId"
        />
        <p v-if="!focusRows.length" class="mb-0 mt-1 text-gray-500">暂无低于及格线的学生。</p>
      </template>

      <!-- 加载态 -->
      <p v-if="loading" class="mb-0 text-gray-500">加载中…</p>

      <!-- 成功但无成绩（未判分完成/无实考答卷） → 业务空态，非错误态 -->
      <p v-else-if="!errorText && analysis && !hasData" class="mb-0 text-gray-500">
        暂无成绩数据：本场考试还没有已汇总的答卷（请先判分并执行成绩汇总）。
      </p>
    </template>
  </div>
</template>

<script setup lang="ts">
import {
  Alert,
  Card,
  Col,
  Progress,
  Row,
  Statistic,
  Table,
  type TableColumnsType,
} from 'ant-design-vue';
import { computed } from 'vue';
import { useQuery } from '@tanstack/vue-query';

import {
  analysisReport,
  type ClassOverview,
  type ExamAnalysisReportResponse,
  type QuestionStatItem,
  type TagWeaknessItem,
} from '@/api/axios';
import { client, unwrap } from '@/api/apiClient';

/**
 * 考试数据分析报告（add-exam-analysis-report，创新点4）：
 * 纯渲染后端 `GET /api/exams/{examId}/scores/analysis-report` 返回的数据，
 * 不做任何前端推算；逐题指标/区分度/知识点口径全部以后端为准。
 */

const props = defineProps<{
  examId: number;
  enabled: boolean;
}>();

const {
  data: analysis,
  isFetching: loading,
  error,
} = useQuery({
  queryKey: computed(() => ['exam', props.examId, 'analysis-report'] as const),
  queryFn: () =>
    unwrap<ExamAnalysisReportResponse>(
      analysisReport({
        client,
        throwOnError: true,
        path: { examId: props.examId },
      })
    ),
  enabled: computed(() => props.enabled && props.examId > 0),
  retry: 0,
});

const errorText = computed(() => (error.value instanceof Error ? error.value.message : null));

const overview = computed<ClassOverview | undefined>(() => analysis.value?.classOverview);
const hasData = computed(() => (overview.value?.actualCount ?? 0) > 0);
const hasTagDimension = computed(() => analysis.value?.hasTagDimension ?? false);
const scoreBands = computed(() => overview.value?.scoreBands ?? []);

const averageScorePrecision = computed(() =>
  typeof overview.value?.averageScore === 'number' ? 1 : undefined
);
const passRateValue = computed(() => {
  const rate = overview.value?.passRate;
  return rate === undefined || rate === null ? 0 : Number((rate * 100).toFixed(1));
});
const passRatePrecision = computed(() =>
  overview.value?.passRate === undefined || overview.value?.passRate === null ? undefined : 1
);

function bandPercent(count: number): number {
  const total = overview.value?.actualCount ?? 0;
  if (total === 0) return 0;
  return Math.round((count / total) * 100);
}

// ==================== 逐题指标 ====================

const questionRows = computed<QuestionStatItem[]>(() => analysis.value?.questionStats ?? []);

const questionColumns: TableColumnsType = [
  { title: '题号', key: 'order', dataIndex: 'order', width: 60 },
  { title: '题型', key: 'type', dataIndex: 'type', width: 80 },
  { title: '满分', key: 'fullScore', dataIndex: 'fullScore', width: 70 },
  { title: '平均分', key: 'averageScore', dataIndex: 'averageScore', width: 90 },
  { title: '得分率', key: 'scoreRate', dataIndex: 'scoreRate' },
  { title: '答对率', key: 'correctRate', dataIndex: 'correctRate', width: 90 },
  { title: '区分度', key: 'discrimination', dataIndex: 'discrimination', width: 90 },
  { title: '作答人数', key: 'answeredCount', dataIndex: 'answeredCount', width: 90 },
];

/** 0~100 整数百分比；null/缺失（后端样本不足等）返回 0。 */
function toPercent(rate: number | undefined | null): number {
  if (rate === undefined || rate === null) return 0;
  return Math.round(rate * 100);
}

function rateStatus(rate: number | undefined | null): 'success' | 'exception' | 'active' {
  if (rate === undefined || rate === null) return 'active';
  if (rate >= 0.6) return 'success';
  if (rate < 0.4) return 'exception';
  return 'active';
}

function discrimLabel(discrimination: number | undefined | null): string {
  if (discrimination === undefined || discrimination === null) return '样本不足';
  return discrimination.toFixed(2);
}

// ==================== 知识点薄弱 / 关注名单 ====================

const tagWeakness = computed<TagWeaknessItem[]>(() => analysis.value?.tagWeakness ?? []);

interface FocusRow {
  studentId?: number;
  username?: string;
  studentName?: string;
  totalScore?: number;
}

const focusRows = computed<FocusRow[]>(() => analysis.value?.focusList ?? []);

const focusColumns: TableColumnsType = [
  { title: '学号', key: 'username', dataIndex: 'username', width: 160 },
  { title: '姓名', key: 'studentName', dataIndex: 'studentName', width: 140 },
  { title: '总分', key: 'totalScore', dataIndex: 'totalScore' },
];
</script>
