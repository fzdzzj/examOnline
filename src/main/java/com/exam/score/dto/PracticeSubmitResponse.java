package com.exam.score.dto;

import lombok.Data;

/**
 * 错题重练逐题提交即时反馈响应（spec add-wrong-question-practice）。
 */
@Data
public class PracticeSubmitResponse {

    /** 本次作答是否答对（得满分即答对，多选部分对不算答对） */
    private Boolean correct;

    /** 标准正确答案 */
    private String correctAnswer;

    /** 题目解析（严格取自题库 questions.analysis） */
    private String analysis;

    /** 提交后当前连对次数（答对递增，答错归零） */
    private Integer consecutiveCorrect;

    /** 是否已消灭（连对达到 3 次自动消灭） */
    private Boolean mastered;

    /** 掌握度三态：0 UNTOUCHED（连对 0）/ 1 PRACTICING（连对 1~2）/ 2 MASTERED（连对 >= 3） */
    private Integer masteryStatus;

    /** 掌握度三态名称（UNTOUCHED / PRACTICING / MASTERED） */
    private String masteryStatusName;
}
