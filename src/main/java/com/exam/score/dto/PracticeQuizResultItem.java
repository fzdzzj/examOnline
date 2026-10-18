package com.exam.score.dto;

import lombok.Data;

/**
 * 错题强化自测逐题作答结果（spec add-student-wrong-question-mastery）：
 * 字段形态与单题重练反馈一致，另附题目 ID 与最新掌握度三态。
 */
@Data
public class PracticeQuizResultItem {

    private Long questionId;

    /** 本次作答是否答对（得满分即答对，多选部分对不算答对） */
    private Boolean correct;

    /** 标准正确答案 */
    private String correctAnswer;

    /** 题目解析（严格取自题库 questions.analysis；快照回退路径为 null） */
    private String analysis;

    /** 提交后当前连对次数（答对递增，答错归零） */
    private Integer consecutiveCorrect;

    /** 是否已消灭（连对达到 3 次自动消灭） */
    private Boolean mastered;

    /** 掌握度三态：0 UNTOUCHED / 1 PRACTICING / 2 MASTERED */
    private Integer masteryStatus;

    /** 掌握度三态名称（UNTOUCHED / PRACTICING / MASTERED） */
    private String masteryStatusName;
}
