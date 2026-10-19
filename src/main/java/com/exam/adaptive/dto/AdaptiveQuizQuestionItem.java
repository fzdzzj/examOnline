package com.exam.adaptive.dto;

import lombok.Data;

import java.util.List;

/**
 * CAT 自适应测验题目视图（开局/下一题共用）：作答前<b>不泄露</b>正确答案与解析。
 */
@Data
public class AdaptiveQuizQuestionItem {

    /** 本次会话内的题序（1 起） */
    private Integer order;

    private Long questionId;

    /** 题型标签（单选/多选/判断/填空） */
    private String questionType;

    private String questionContent;

    private String imageUrl;

    private List<String> choices;
}
