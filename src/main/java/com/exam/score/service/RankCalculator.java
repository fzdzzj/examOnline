package com.exam.score.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 成绩排名计算（spec「成绩排名」需求，docs/需求决策记录.md §11.6：并列同名次 1,2,2,4）：
 *
 * <p>采用标准竞赛排名（standard competition ranking）——同分并列同一名次，
 * 后续名次按人数跳空（如 90,85,85,80 → 1,2,2,4）。纯函数无状态，
 * 排名口径在发布预览、学生查询、导出三处共用，保证同一时刻展示一致。
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
        for (int i = 0; i < n; i++) {
            if (totals.get(i) == null) {
                continue;   // 未汇总的答卷无排名
            }
            // 名次 = 总分严格更高的学生数 + 1（同分不叠加，天然并列）
            int rank = 1;
            for (int j = 0; j < n; j++) {
                if (j != i && totals.get(j) != null
                        && totals.get(j).compareTo(totals.get(i)) > 0) {
                    rank++;
                }
            }
            ranks[i] = rank;
        }
        return ranks;
    }
}
