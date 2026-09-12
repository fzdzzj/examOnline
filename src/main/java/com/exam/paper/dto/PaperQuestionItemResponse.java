package com.exam.paper.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 试卷内题目项：number/score 来自 paper_questions（题号顺序与分值覆盖），
 * 题干等内容实时读取题库；questionDeleted=true 表示题目后来被软删（草稿期可见，需处理）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaperQuestionItemResponse {

    private Long questionId;

    /** 试卷内题号（1 起连续） */
    private Integer number;

    /** 试卷内分值（覆盖题目默认分） */
    private BigDecimal score;

    /** 1单选 2多选 3判断 4简答（题目已删除时为 null） */
    private Integer questionType;

    /** 题干（题目已删除时为 null） */
    private String content;

    /** 选项文本列表 */
    private List<String> choices;

    /** 归一化后的正确答案 */
    private String correctAnswer;

    /** 题目默认分值（对照用） */
    private BigDecimal defaultScore;

    /** 题目是否已被软删除 */
    private boolean questionDeleted;
}
