package com.exam.score.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * 排名计算单元测试（spec「成绩排名」场景：同分并列同名次，后续名次跳空 1,2,2,4）。
 */
class RankCalculatorTest {

    private final RankCalculator calculator = new RankCalculator();

    private BigDecimal score(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("并列同名次：90,85,85,80 → 1,2,2,4")
    void competitionRankingWithTies() {
        int[] ranks = calculator.rank(List.of(
                score("90"), score("85"), score("85"), score("80")));
        assertArrayEquals(new int[]{1, 2, 2, 4}, ranks);
    }

    @Test
    @DisplayName("无并列：连续名次 1,2,3")
    void noTies() {
        int[] ranks = calculator.rank(List.of(
                score("100"), score("90"), score("80")));
        assertArrayEquals(new int[]{1, 2, 3}, ranks);
    }

    @Test
    @DisplayName("三人同分并列第一：100,100,100 → 1,1,1")
    void allTied() {
        int[] ranks = calculator.rank(List.of(
                score("100"), score("100"), score("100")));
        assertArrayEquals(new int[]{1, 1, 1}, ranks);
    }

    @Test
    @DisplayName("未汇总(null)不参与排名记 0，不影响他人名次")
    void nullNotRanked() {
        // Arrays.asList 允许 null 元素（List.of 拒绝）
        int[] ranks = calculator.rank(java.util.Arrays.asList(
                score("90"), null, score("85")));
        assertArrayEquals(new int[]{1, 0, 2}, ranks);
    }

    @Test
    @DisplayName("空列表安全")
    void empty() {
        assertArrayEquals(new int[]{}, calculator.rank(List.of()));
    }
}
