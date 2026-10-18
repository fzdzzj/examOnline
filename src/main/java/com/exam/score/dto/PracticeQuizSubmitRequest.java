package com.exam.score.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 错题强化自测批量交卷请求（spec add-student-wrong-question-mastery）。
 */
@Data
public class PracticeQuizSubmitRequest {

    /** 组卷时返回的无状态试卷标识 */
    @NotBlank(message = "quizId 不能为空")
    private String quizId;

    /** 逐题作答列表 */
    @Valid
    @NotEmpty(message = "作答列表不能为空")
    private List<PracticeQuizAnswer> answers;
}
