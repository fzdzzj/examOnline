package com.exam.score.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 错题条目（错题判定：得分 < 满分，含部分对）：
 * 包含题目信息、作答、正确答案、得分及题目解析（取自 questions.analysis）。
 */
@Data
public class WrongQuestionItem {

    private Long examId;

    private String examTitle;

    private LocalDateTime examTime;

    private Long questionId;

    /** 试卷内题号（1 起连续） */
    private Integer questionNumber;

    /** 题型名称（单选 / 多选 / 判断 / 简答） */
    private String questionType;

    /** 题干 */
    private String questionContent;

    /** 客观题选项列表（判断/简答为 null） */
    private List<String> choices;

    /** 学生作答（真实答案，非空串占位） */
    private String myAnswer;

    /** 标准正确答案（归一化口径，非空串占位） */
    private String correctAnswer;

    /** 学生本题得分 */
    private BigDecimal myScore;

    /** 本题满分 */
    private BigDecimal fullScore;

    /** 答案解析（严格取自题库 questions.analysis；非判分依据） */
    private String analysis;

    /** 判分依据或教师评语（可选附） */
    private String scoreDetail;
}
