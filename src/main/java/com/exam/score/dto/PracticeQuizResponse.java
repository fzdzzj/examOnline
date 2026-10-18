package com.exam.score.dto;

import lombok.Data;

import java.util.List;

/**
 * 错题强化自测组卷响应（spec add-student-wrong-question-mastery）：
 * 无状态临时试卷（UUID quizId 仅作答卷回执关联，不落库），
 * 题目条目复用重练池形态，作答前严格不泄露正确答案与解析。
 */
@Data
public class PracticeQuizResponse {

    /** 本次组卷标识（无状态 UUID） */
    private String quizId;

    /** 实际抽到的题数（错题池不足 limit 时按实际数量返回，空池为 0） */
    private Integer total;

    /** 抽中的题目列表（复用重练池条目形态：无答案、无解析） */
    private List<PracticePoolItem> items;
}
