package com.exam.grading.model;

import java.math.BigDecimal;

/**
 * 单题判分结果。
 *
 * @param score   本题得分（客观题 0 ≤ score ≤ 满分；简答初判在参考答案无可拆关键词时为 null=无提示分）
 * @param correct 是否得满分（客观题统计答对率用；简答初判恒 false——初判不是终分）
 * @param detail  判分依据说明（如"漏选 1 个，按比例给部分分"、"命中关键词 2/3"），落库/导出可追溯
 */
public record GradeResult(BigDecimal score, boolean correct, String detail) {

    /** 零分结果（含原因）。 */
    public static GradeResult zero(String detail) {
        return new GradeResult(BigDecimal.ZERO, false, detail);
    }
}
