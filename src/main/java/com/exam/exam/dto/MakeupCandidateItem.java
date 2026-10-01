package com.exam.exam.dto;

/**
 * 主考筛选出的可补考学生条目（教师组织补考前的勾选数据源）：
 * reason 标识该生为何可被指定（ABSENT=缺考、BELOW_LINE=有成绩但低于分数线）。
 */
public record MakeupCandidateItem(Long studentId, String studentName, String reason) {

    /** 缺考（应考无答卷） */
    public static final String REASON_ABSENT = "ABSENT";

    /** 有成绩但低于分数线 */
    public static final String REASON_BELOW_LINE = "BELOW_LINE";
}