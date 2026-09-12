package com.exam.grading.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 判分配置（docs/需求决策记录.md §8.9：多选"漏选给部分分、错选/多选 0 分；部分分比例按考试配置"）。
 *
 * <p>比例目前以应用级配置下发（exam.grading.multiple-partial-ratio），全系统统一；
 * 提案的数据迁移范围未含 exams 表改动，按考试粒度的比例配置留待后续表结构扩展时接入。
 */
@Component
public class GradingConfig {

    /** 多选漏选部分分系数（0,1]：得分 = 满分 × 系数 × (选中正确数 / 应选数)。1.0 = 纯按正确比例，调低则加重漏选惩罚。 */
    private final BigDecimal multiplePartialRatio;

    public GradingConfig(@Value("${exam.grading.multiple-partial-ratio:1.0}") BigDecimal multiplePartialRatio) {
        if (multiplePartialRatio.compareTo(BigDecimal.ZERO) <= 0
                || multiplePartialRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("multiplePartialRatio 必须在 (0,1] 区间");
        }
        this.multiplePartialRatio = multiplePartialRatio;
    }

    public BigDecimal getMultiplePartialRatio() {
        return multiplePartialRatio;
    }

    /** 按比例计算部分分：保留 1 位小数（与 DECIMAL(5,1) 分值口径一致），向下截断不给学生多分。 */
    public static BigDecimal scale(BigDecimal score) {
        return score.setScale(1, RoundingMode.DOWN);
    }
}
