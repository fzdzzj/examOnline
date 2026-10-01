package com.exam.score.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 成绩排名计算（spec「成绩排名」需求，docs/需求决策记录.md §11.6：并列同名次 1,2,2,4）：
 *
 * <p>采用标准竞赛排名（standard competition ranking）——同分并列同一名次，
 * 后续名次按人数跳空（如 90,85,85,80 → 1,2,2,4）。纯函数无状态，
 * 排名口径在发布预览、学生查询、导出三处共用，保证同一时刻展示一致。
 *
 * <p>实现：对非 null 分数按 {@link BigDecimal#compareTo} 降序排序（含并列稳定归组），
 * 再单遍赋值竞赛名次，比较次数增长为 O(n log n)，不再逐份答卷遍历全班 O(n²)。
 * 排序与归组均以 compareTo 的数值比较为准，故数值等价、scale 不同的分数同样并列；
 * null 不参与排名记 0，结果名次数组与入参位置一一对应。
 */
@Component
public class RankCalculator {

    /**
     * 按总分降序计算竞赛排名。
     *
     * @param totals 各学生总分（顺序与调用方列表一致，可含 null——未汇总者排 0 名）
     * @return 与入参等长的名次数组；null 总分不参与排名记 0
     */
    public int[] rank(List<BigDecimal> totals) {
        int n = totals == null ? 0 : totals.size();
        int[] ranks = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (totals.get(i) != null) {
                m++;
            }
        }
        if (m == 0) {
            return ranks;
        }
        // 收集非 null 下标，按下标原分数降序排序（比较走 compareTo，位序稳定，同分组可保原相对顺序）
        Integer[] idx = new Integer[m];
        int p = 0;
        for (int i = 0; i < n; i++) {
            if (totals.get(i) != null) {
                idx[p++] = i;
            }
        }
        Arrays.sort(idx, (a, b) -> totals.get(b).compareTo(totals.get(a)));
        // 单遍竞赛排名：与前一元素数值相等则沿用其名次，否则新名次=当前位置+1（跳号）
        int cur = 0;
        BigDecimal prev = null;
        for (int k = 0; k < m; k++) {
            BigDecimal s = totals.get(idx[k]);
            if (k > 0 && s.compareTo(prev) == 0) {
                ranks[idx[k]] = cur;
            } else {
                cur = k + 1;
                ranks[idx[k]] = cur;
            }
            prev = s;
        }
        return ranks;
    }
}
