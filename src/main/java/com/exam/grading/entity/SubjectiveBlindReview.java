package com.exam.grading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 简答题双评盲评记录（add-subjective-double-blind-grading，一卷一题一轮一行，
 * uk_blind_review 唯一：submission_id + question_id + round_type 各至多一条）：
 *
 * <p>生命周期：教师 A 提交一评（FIRST）→ 教师 B 提交二评（SECOND，与一评教师互斥）→
 * 分差 ≤ 容差自动取均分回填 subjective_grades.score（RESOLVED）；超标流转待仲裁
 * （ARBITRATION_PENDING，终分保持 NULL），仲裁员提交仲裁分（ARBITRATION）终审回填结案。
 *
 * <p>背靠背脱敏：二评教师查询接口不透出一评评分评语，考生身份打码（SubjectiveGradingService）。
 */
@Data
@TableName("subjective_blind_reviews")
public class SubjectiveBlindReview {

    /** 一评 */
    public static final String ROUND_FIRST = "FIRST";
    /** 二评 */
    public static final String ROUND_SECOND = "SECOND";
    /** 仲裁 */
    public static final String ROUND_ARBITRATION = "ARBITRATION";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long examId;

    private Long submissionId;

    private Long questionId;

    /** 盲评轮次：FIRST / SECOND / ARBITRATION */
    private String roundType;

    /** 盲评教师（仲裁轮为仲裁员） */
    private Long graderId;

    /** 该轮评分 */
    private BigDecimal score;

    /** 评语 */
    private String comment;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
}
