package com.exam.taking.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 学生可见题目视图（来自个人快照）：
 * 只含题号/题型/题干/选项/分值——不含 correctAnswer，学生端永远拿不到答案。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuestionView {

    /** 题号（个人快照内 1 起连续，题序已洗牌锁定） */
    private Integer number;

    /** 题目 ID：作答与判分的真实主键 */
    private Long questionId;

    /** 题型：1单选 2多选 3判断 4简答 */
    private Integer type;

    private String content;

    /** 选项数组（个人快照内已乱序锁定；判断/简答为 null） */
    private JsonNode choices;

    /** 试卷内分值 */
    private BigDecimal score;
}
