package com.exam.score.dto;

import lombok.Data;

import java.util.List;

/**
 * 错题重练题目池条目（spec add-wrong-question-practice）：
 * 仅含可机判客观题（SINGLE/MULTIPLE/JUDGE/FILL_BLANK），排除简答与已消灭；
 * 作答前不泄露正确答案与题目解析。
 */
@Data
public class PracticePoolItem {

    private Long questionId;

    /** 题型名称（单选 / 多选 / 判断 / 填空） */
    private String questionType;

    /** 题干 */
    private String questionContent;

    /** 题干图片相对 URL（add-question-image；无图为 null） */
    private String imageUrl;

    /** 客观题选项列表（判断/填空为 null） */
    private List<String> choices;

    /** 当前连对次数（x/3） */
    private Integer consecutiveCorrect;
}
