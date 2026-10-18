package com.exam.score.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 错题强化自测组卷请求（spec add-student-wrong-question-mastery）：
 * 按题量与抽题策略从本人未消灭客观错题池抽取题目组卷（纯内存、零 DDL）。
 */
@Data
public class PracticeQuizGenerateRequest {

    /** 抽题量（缺省 5，区间 [1, 20]） */
    @Min(value = 1, message = "抽题量必须在 1~20 之间")
    @Max(value = 20, message = "抽题量必须在 1~20 之间")
    private Integer limit;

    /** 抽题策略：RANDOM 全局随机（缺省）/ PRACTICING_FIRST 优先攻克中 */
    private String strategy;
}
