package com.exam.score.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试题测量学评级与命题质量诊断评估器单测（add-exam-question-discrimination）。
 *
 * <p>纯函数级钉死 spec-delta 全部阈值边界：区分度五档（0.40/0.30/0.20 临界值逐点断言）、
 * 负值反常预警、样本不足降级、难度三分档（0.70/0.40 临界）、难度系数 2 位 HALF_UP 舍入，
 * 以及诊断建议文案的关键警示词。评估器为纯函数，不依赖 Spring 上下文，直接静态调用。
 */
class QuestionMetricsEvaluatorTest {

    // ==================== 区分度五档评级 ====================

    @Test
    void discriminationLevelExcellentAtAndAboveForty() {
        assertEquals("优良", QuestionMetricsEvaluator.discriminationLevelOf(1.00));
        assertEquals("优良", QuestionMetricsEvaluator.discriminationLevelOf(0.40), "0.40 为优良下边界（≥0.40）");
    }

    @Test
    void discriminationLevelGoodBand() {
        assertEquals("良好", QuestionMetricsEvaluator.discriminationLevelOf(0.399));
        assertEquals("良好", QuestionMetricsEvaluator.discriminationLevelOf(0.30), "0.30 为良好下边界（≥0.30）");
    }

    @Test
    void discriminationLevelFairBand() {
        assertEquals("尚可", QuestionMetricsEvaluator.discriminationLevelOf(0.29));
        assertEquals("尚可", QuestionMetricsEvaluator.discriminationLevelOf(0.20), "0.20 为尚可下边界（≥0.20）");
    }

    @Test
    void discriminationLevelPoorBand() {
        assertEquals("劣质", QuestionMetricsEvaluator.discriminationLevelOf(0.199));
        assertEquals("劣质", QuestionMetricsEvaluator.discriminationLevelOf(0.0));
    }

    @Test
    void discriminationLevelNegativeAnomalyWarning() {
        assertEquals("反常预警", QuestionMetricsEvaluator.discriminationLevelOf(-0.01), "负区分度必须反常预警");
        assertEquals("反常预警", QuestionMetricsEvaluator.discriminationLevelOf(-1.0));
    }

    @Test
    void discriminationLevelInsufficientSample() {
        assertEquals("样本不足", QuestionMetricsEvaluator.discriminationLevelOf(null),
                "区分度 null（实考 <4 或分组空）→ 样本不足，绝不 NPE");
    }

    // ==================== 难度系数与等级 ====================

    @Test
    void difficultyHalfUpTwoDecimals() {
        assertEquals(0.50, QuestionMetricsEvaluator.difficultyOf(new BigDecimal("2.0"), 4, 4), 1e-9);
        assertEquals(0.68, QuestionMetricsEvaluator.difficultyOf(new BigDecimal("2.7"), 4, 3), 1e-9,
                "2.7/4=0.675 → HALF_UP 0.68（2 位小数）");
        assertEquals(0.63, QuestionMetricsEvaluator.difficultyOf(new BigDecimal("3.8"), 6, 4), 1e-9,
                "3.8/6=0.6333 → 0.63");
    }

    @Test
    void difficultyNullWhenNoAnswerOrZeroFullScore() {
        assertNull(QuestionMetricsEvaluator.difficultyOf(new BigDecimal("2.0"), 4, 0), "无人作答 → null");
        assertNull(QuestionMetricsEvaluator.difficultyOf(null, 4, 3), "平均分缺失 → null");
        assertNull(QuestionMetricsEvaluator.difficultyOf(new BigDecimal("2.0"), 0, 3), "满分非正 → null");
    }

    @Test
    void difficultyLevelThreeBands() {
        assertEquals("容易", QuestionMetricsEvaluator.difficultyLevelOf(0.70), "0.70 为容易下边界（≥0.70）");
        assertEquals("容易", QuestionMetricsEvaluator.difficultyLevelOf(1.00));
        assertEquals("中等", QuestionMetricsEvaluator.difficultyLevelOf(0.69));
        assertEquals("中等", QuestionMetricsEvaluator.difficultyLevelOf(0.40), "0.40 为中等下边界（≥0.40）");
        assertEquals("较难", QuestionMetricsEvaluator.difficultyLevelOf(0.39));
        assertEquals("较难", QuestionMetricsEvaluator.difficultyLevelOf(0.0));
        assertNull(QuestionMetricsEvaluator.difficultyLevelOf(null), "难度缺省 → 等级缺省，前端/Excel 呈「-」");
    }

    // ==================== 命题质量诊断建议 ====================

    @Test
    void qualityInsufficientSampleHintsAtLeastFour() {
        String text = QuestionMetricsEvaluator.qualityEvaluationOf(null, 0.5);
        assertTrue(text.contains("样本不足"), "样本不足建议必须点名样本不足");
        assertTrue(text.contains("4"), "样本不足建议必须提示需至少 4 人实考");
    }

    @Test
    void qualityNegativeDiscriminationUrgentReview() {
        String text = QuestionMetricsEvaluator.qualityEvaluationOf(-1.0, 0.75);
        assertTrue(text.contains("反常预警"), "负区分度建议必须带反常预警警示");
        assertTrue(text.contains("核查"), "负区分度建议必须提示紧急核查");
        assertTrue(text.contains("歧义") || text.contains("参考答案"), "提示题干严重歧义或参考答案错误");
    }

    @Test
    void qualityExcellentRecommendsPremiumBank() {
        String text = QuestionMetricsEvaluator.qualityEvaluationOf(1.0, 0.5);
        assertTrue(text.contains("优良"));
        assertTrue(text.contains("精品题库"), "优良试题建议入选精品题库");
    }

    @Test
    void qualityGoodAndFairAndPoorWording() {
        assertTrue(QuestionMetricsEvaluator.qualityEvaluationOf(0.35, 0.5).contains("可直接使用"));
        assertTrue(QuestionMetricsEvaluator.qualityEvaluationOf(0.25, 0.5).contains("微调"));
        assertTrue(QuestionMetricsEvaluator.qualityEvaluationOf(0.10, 0.5).contains("干扰项"));
    }

    @Test
    void qualityAppendsDifficultyNoteForEasyAndHard() {
        assertTrue(QuestionMetricsEvaluator.qualityEvaluationOf(1.0, 0.90).contains("偏易"));
        assertTrue(QuestionMetricsEvaluator.qualityEvaluationOf(0.5, 0.20).contains("偏难"));
        String neutral = QuestionMetricsEvaluator.qualityEvaluationOf(0.5, 0.50);
        assertFalse(neutral.contains("偏易"), "中等难度不追加偏易提示");
        assertFalse(neutral.contains("偏难"), "中等难度不追加偏难提示");
    }
}
