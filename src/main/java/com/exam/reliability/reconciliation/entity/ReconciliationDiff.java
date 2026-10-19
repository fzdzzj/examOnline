package com.exam.reliability.reconciliation.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 差异票（spec reliability「成绩链全链路对账」，表 reconciliation_diffs）：
 * 一次对账检出的每条链路差异一行，支持 OPEN → AUTO_FIXED / MANUAL_CLOSED。
 *
 * <p>两类差异：{@link #TYPE_SUBMISSION_UNGRADED}（维度一：交卷 vs 判分，含判分缺失、
 * 主观题未批完、判分完成但总分未回填）与 {@link #TYPE_DETAIL_TOTAL_MISMATCH}
 * （维度二：判分明细汇总 vs 最终总分）。
 *
 * <p><b>涉分矛盾只出票不自动改分</b>：{@code expectedValue}/{@code actualValue}/{@code deviation}
 * 只对维度二有值，供人工处置参照；只有「判分完成但总分未回填」这类确定性缺失才自动补齐，
 * 其票状态为 {@link #STATUS_AUTO_FIXED}。
 *
 * <p>{@code questionId=0} 表示答卷级差异：唯一指纹
 * {@code uk_recon_diff_fingerprint(diff_type, submission_id, question_id, status)} 里的列全部
 * NOT NULL，避免 NULL 在唯一索引中逃逸（MySQL/H2 均允许多行 NULL）而使指纹失效。
 */
@Data
@TableName("reconciliation_diffs")
public class ReconciliationDiff {

    public static final String TYPE_SUBMISSION_UNGRADED = "SUBMISSION_UNGRADED";
    public static final String TYPE_DETAIL_TOTAL_MISMATCH = "DETAIL_TOTAL_MISMATCH";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_AUTO_FIXED = "AUTO_FIXED";
    public static final String STATUS_MANUAL_CLOSED = "MANUAL_CLOSED";

    /** 判分记录缺失/客观分未回填（维度一，人工处置） */
    public static final String DETAIL_GRADING_MISSING = "GRADING_MISSING";
    /** 主观题未批完（维度一，人工补批；不自动改分） */
    public static final String DETAIL_SUBJECTIVE_NOT_FULLY_GRADED = "SUBJECTIVE_NOT_FULLY_GRADED";
    /** 判分完成但总分未回填（维度一，确定性缺失 → 幂等自动补齐） */
    public static final String DETAIL_TOTAL_SCORE_NOT_FILLED = "TOTAL_SCORE_NOT_FILLED";
    /** 判分明细汇总与最终总分不一致（维度二） */
    public static final String DETAIL_TOTAL_MISMATCH = "TOTAL_MISMATCH";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联对账运行 */
    private Long runId;

    private Long examId;

    private Long submissionId;

    /** 0=答卷级差异（非题目级） */
    private Long questionId;

    /** SUBMISSION_UNGRADED / DETAIL_TOTAL_MISMATCH */
    private String diffType;

    /** 差异细分：GRADING_MISSING / SUBJECTIVE_NOT_FULLY_GRADED / TOTAL_SCORE_NOT_FILLED / TOTAL_MISMATCH */
    private String detail;

    /** 同源口径期望总分（涉分矛盾） */
    private BigDecimal expectedValue;

    /** 库中实际总分（涉分矛盾） */
    private BigDecimal actualValue;

    /** expected - actual */
    private BigDecimal deviation;

    /** OPEN / AUTO_FIXED / MANUAL_CLOSED */
    private String status;

    /** 人工关闭操作人 */
    private Long closedBy;

    private LocalDateTime closedTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
}
