package com.exam.question.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题目查重算法纯单测（add-question-duplicate-detection）：
 * 不依赖任何 Spring 上下文，直接验证 Bigram Jaccard + 编辑距离混合相似度的
 * 预处理（标点/全半角/大小写归一）与得分口径（0.0~1.0）。
 */
class QuestionDuplicateServiceAlgorithmTest {

    // ==================== 预处理 ====================

    @Test
    void preprocessNormalizesPunctuationCaseAndWhitespace() {
        // 中文标点去除、全角符号转半角、英文字母小写、空白剔除
        assertEquals("下面哪个是红色", QuestionDuplicateService.preprocess("下面哪个是红色?"));
        assertEquals("下面哪个是红色", QuestionDuplicateService.preprocess("下面哪个是红色？"));
        assertEquals("tcp三次握手", QuestionDuplicateService.preprocess("TCP 三次握手"));
        assertEquals("tcp三次握手", QuestionDuplicateService.preprocess("ＴＣＰ三次握手"));
        assertEquals("abc", QuestionDuplicateService.preprocess("  a b c  "));
    }

    @Test
    void preprocessNullOrBlankYieldsEmpty() {
        assertEquals("", QuestionDuplicateService.preprocess(null));
        assertEquals("", QuestionDuplicateService.preprocess("   "));
    }

    // ==================== 完全一致与高度相似 ====================

    @Test
    void identicalTextScoresOne() {
        assertEquals(1.0, QuestionDuplicateService.computeSimilarity("下面哪个是红色?", "下面哪个是红色？"),
                1e-9);
        assertEquals(1.0, QuestionDuplicateService.computeSimilarity("TCP 三次握手", "tcp三次握手"),
                1e-9);
    }

    @Test
    void nearIdenticalScoresAboveDefaultThreshold() {
        // 仅一字之差（正确的是 / 错误的是）→ 混合相似度应高于默认阈值 0.6
        double score = QuestionDuplicateService.computeSimilarity(
                "下列关于TCP三次握手说法正确的是",
                "下列关于TCP三次握手说法错误的是");
        assertTrue(score >= 0.6, "一字之差相似度应 ≥ 0.6，实际 " + score);
        assertTrue(score < 1.0, "非完全一致相似度应 < 1.0，实际 " + score);
    }

    @Test
    void unrelatedTextScoresLow() {
        double score = QuestionDuplicateService.computeSimilarity(
                "下列关于TCP三次握手说法正确的是",
                "北京到上海的最近高铁线路经过哪些城市");
        assertTrue(score < 0.6, "无关文本相似度应 < 0.6，实际 " + score);
    }

    // ==================== 分量口径 ====================

    @Test
    void bigramJaccardRangesZeroToOne() {
        // 完全相同 → 1.0；完全无关 → 0.0
        assertEquals(1.0, QuestionDuplicateService.bigramJaccard("abcd", "abcd"), 1e-9);
        assertEquals(1.0, QuestionDuplicateService.bigramJaccard("甲乙丙丁", "甲乙丙丁"), 1e-9);
        assertTrue(QuestionDuplicateService.bigramJaccard("abc", "xyz") >= 0.0);
        assertTrue(QuestionDuplicateService.bigramJaccard("abc", "xyz") <= 1.0);
    }

    @Test
    void emptyInputJaccardIsZero() {
        // 空序列不做除法，直接归零，避免 0/0 或 NaN
        assertEquals(0.0, QuestionDuplicateService.bigramJaccard("", ""), 1e-9);
        assertEquals(0.0, QuestionDuplicateService.bigramJaccard("abc", ""), 1e-9);
    }

    @Test
    void normalizedEditSimilarityRangesZeroToOne() {
        assertEquals(1.0, QuestionDuplicateService.normalizedEditSimilarity("abcd", "abcd"), 1e-9);
        // 一字之差：相似度约 1 - 1/maxLen，介于 0..1
        double edit = QuestionDuplicateService.normalizedEditSimilarity("abcde", "abxde");
        assertTrue(edit > 0.0 && edit < 1.0, "一字之差编辑相似度应介于 (0,1)，实际 " + edit);
        assertEquals(0.0, QuestionDuplicateService.normalizedEditSimilarity("", ""), 1e-9);
    }
}