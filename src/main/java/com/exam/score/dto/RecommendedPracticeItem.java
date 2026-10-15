package com.exam.score.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 薄弱知识点靶向练习推荐条目（spec add-student-learning-analytics）：
 * 从题库中检索学生最薄弱知识点下的机判客观题（单选/多选/判断/填空），
 * 排除错题重练中已消灭的题目，返回推荐原因。
 */
@Data
public class RecommendedPracticeItem {

    private Long questionId;

    /** 题干。 */
    private String stem;

    /** 题型名称（单选/多选/判断/填空）。 */
    private String type;

    /** 题目默认分值。 */
    private BigDecimal score;

    /** 难度（1易 2中 3难）。 */
    private Integer difficulty;

    /** 命中知识点 ID。 */
    private Long tagId;

    /** 命中知识点名称。 */
    private String tagName;

    /** 学生在该知识点上的当前掌握度（0~1）。 */
    private Double tagMasteryRate;

    /** 推荐原因文案（含知识点名称与薄弱掌握度）。 */
    private String recommendReason;
}