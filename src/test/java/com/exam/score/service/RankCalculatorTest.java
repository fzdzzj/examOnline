package com.exam.score.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ==================== 归因护栏（spec「归因成立才替换计算结构」，确定性、不依赖墙钟） ====================

    /** 测试侧可计数比较的 BigDecimal 子类：代理 compareTo 累加调用次数，验证实现确实逐个比较。 */
    static class CountingBigDecimal extends BigDecimal {
        static final ThreadLocal<AtomicLong> COUNTER = ThreadLocal.withInitial(AtomicLong::new);

        CountingBigDecimal(String value) {
            super(value);
        }

        @Override
        public int compareTo(BigDecimal other) {
            COUNTER.get().incrementAndGet();
            return super.compareTo(other);
        }
    }

    /** 旧二重遍历语义参照：仅测试侧用于对照，与旧实现逐格一致（同分并列、跳号、null 记 0）。 */
    private static int[] referenceRank(List<BigDecimal> totals) {
        int n = totals == null ? 0 : totals.size();
        int[] ranks = new int[n];
        for (int i = 0; i < n; i++) {
            if (totals.get(i) == null) {
                continue;
            }
            int rank = 1;
            for (int j = 0; j < n; j++) {
                if (j != i && totals.get(j) != null && totals.get(j).compareTo(totals.get(i)) > 0) {
                    rank++;
                }
            }
            ranks[i] = rank;
        }
        return ranks;
    }

    private static long ceilLog2(long x) {
        long bits = 0;
        while (x > 1) {
            x >>= 1;
            bits++;
        }
        return bits;
    }

    @Test
    @DisplayName("比较次数增长不超过 O(n log n)：旧二重遍历在该护栏下为红，新实现为绿")
    void comparisonGrowthGuard() {
        int n = 2048;
        Random rnd = new Random(20260926L);
        List<CountingBigDecimal> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int value = 400 + (i * 7919) % 300;
            int scale = (i % 7 == 1) ? 2 : 1;   // 混入数值等价、scale 不同的同分
            list.add(new CountingBigDecimal(BigDecimal.valueOf(value, scale).toPlainString()));
        }
        for (int k = 0; k < 20; k++) {
            list.set(rnd.nextInt(n), null);
        }

        AtomicLong counter = new AtomicLong();
        CountingBigDecimal.COUNTER.set(counter);
        long before = counter.get();
        int[] ranks = calculator.rank(new ArrayList<BigDecimal>(list));
        long comparisons = counter.get() - before;

        long bound = n * (2 * ceilLog2(n) + 2);
        // 计数有效性：样本长度/名次数与入参一致
        assertEquals(n, ranks.length, "名次数组长度必须与入参等长");
        // 护栏：比较次数增长须接近 O(n log n)，远低于 O(n²)
        assertTrue(comparisons <= bound,
                "比较次数 " + comparisons + " 超过 O(n log n) 上界 " + bound + "（仍为二重遍历）");
        System.out.println("RANK-GUARD n=" + n + " comparisons=" + comparisons + " bound=" + bound
                + " quadratic=" + ((long) n * (n - 1)));
    }

    @Test
    @DisplayName("计数机制自证：语义参照（二重遍历）在无 null 样本上必做 n(n-1) 次比较")
    void countingProvesImplementationsUsesRealComparisons() {
        int n = 2048;
        List<CountingBigDecimal> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int value = 400 + (i * 7919) % 300;
            list.add(new CountingBigDecimal(BigDecimal.valueOf(value, 1).toPlainString()));
        }
        AtomicLong counter = new AtomicLong();
        CountingBigDecimal.COUNTER.set(counter);
        referenceRank(new ArrayList<BigDecimal>(list));
        assertEquals((long) n * (n - 1), counter.get(),
                "计数子类必须确实统计到实现逐格调用的 compareTo（二重遍历应为 n(n-1) 次）");
    }

    @Test
    @DisplayName("旧函数语义对照：空值/并列跳号/不同 scale 等价/输入顺序/确定性多规模与参照一致")
    void semanticsMatchReferenceAcrossScalesAndNulls() {
        // 显式边界
        assertArrayEquals(new int[]{}, calculator.rank(List.of()));
        assertArrayEquals(new int[]{0, 0}, calculator.rank(Arrays.asList(null, null)));
        // 数值等价、scale 不同的并列：2.0 与 2.00 同分，后续按人数跳空
        assertArrayEquals(new int[]{1, 1, 3},
                calculator.rank(Arrays.asList(new BigDecimal("2.0"), new BigDecimal("2.00"),
                        new BigDecimal("1.50"))));

        Random rnd = new Random(31L);
        int[] sizes = {1, 3, 7, 50, 200, 1000};
        for (int n : sizes) {
            for (int trial = 0; trial < 5; trial++) {
                List<BigDecimal> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    if (rnd.nextInt(10) == 0) {
                        list.add(null);
                    } else {
                        int value = 40 + rnd.nextInt(60);
                        int scale = (rnd.nextInt(3) == 0) ? 2 : 1;
                        list.add(BigDecimal.valueOf(value * 10, 1).setScale(scale));
                    }
                }
                int[] expected = referenceRank(new ArrayList<>(list));
                assertArrayEquals(expected, calculator.rank(new ArrayList<>(list)),
                        "语义对照失败 n=" + n + " trial=" + trial);
            }
        }
    }
}
