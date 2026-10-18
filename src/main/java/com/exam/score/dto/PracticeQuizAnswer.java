package com.exam.score.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 错题强化自测逐题作答项（spec add-student-wrong-question-mastery）。
 */
@Data
public class PracticeQuizAnswer {

    @NotNull(message = "题目 ID 不能为空")
    private Long questionId;

    /** 学生作答（单选/多选/判断/填空文本；未作答可为空或空串） */
    private String answer;
}
