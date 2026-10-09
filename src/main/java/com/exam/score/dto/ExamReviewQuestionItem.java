package com.exam.score.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单场考试逐题回顾题目明细（包含全部题目，答对与答错均有）。
 */
@Data
public class ExamReviewQuestionItem {

    private Long questionId;

    /** 试卷内题号（1 起连续） */
    private Integer questionNumber;

    /** 题型名称（单选 / 多选 / 判断 / 简答） */
    private String questionType;

    /** 题干 */
    private String questionContent;

    /** 客观题选项列表（判断/简答为 null） */
    private List<String> choices;

    /** 学生作答 */
    private String myAnswer;

    /** 标准正确答案 */
    private String correctAnswer;

    /** 学生本题得分 */
    private BigDecimal myScore;

    /** 本题满分 */
    private BigDecimal fullScore;

    /** 批改状态（已批 / 未批） */
    private Boolean graded;

    /** 题目解析（严格取自题库 questions.analysis） */
    private String analysis;

    /** 教师评语（主观题） */
    private String comment;

    /** 判分依据（客观题） */
    private String scoreDetail;
}
