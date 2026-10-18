package com.exam.grading.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 待仲裁条目（add-subjective-double-blind-grading）：
 * 一评/二评分数与评语并排呈现（仲裁员是唯一可见双方信息的角色），
 * scoreDiff=|S1-S2|；status 恒为 ARBITRATION_PENDING（清单只收分差超标且未定分条目）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlindArbitrationItem {

    private Long submissionId;

    private Long questionId;

    /** 考试快照内题号（展示用） */
    private Integer questionNumber;

    /** 学生姓名（仲裁员为考试归属人/管理员，可见身份） */
    private String studentName;

    /** 本题满分（仲裁分上限校验与前端输入约束） */
    private BigDecimal maxScore;

    /** 一评分数 */
    private BigDecimal firstScore;

    /** 一评评语 */
    private String firstComment;

    /** 二评分数 */
    private BigDecimal secondScore;

    /** 二评评语 */
    private String secondComment;

    /** 分差 |S1-S2|（必 > 容差 2.0 才进入本清单） */
    private BigDecimal scoreDiff;

    /** 状态：ARBITRATION_PENDING（待仲裁） */
    private String status;
}
