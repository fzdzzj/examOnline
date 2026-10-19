package com.exam.adaptive.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** CAT 单题作答请求（必须与当前待答题一致，防止跳题破坏 θ 轨迹）。 */
@Data
public class AdaptiveQuizAnswerRequest {

    @NotNull(message = "题目 ID 不能为空")
    private Long questionId;

    /** 学生作答（归一化前的原始文本，判分侧同源归一化） */
    private String answer;
}
