package com.exam.paper.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 批量加题入卷请求：单项 score 缺省时使用题目默认分（与单题语义一致）。 */
@Data
public class BatchAddPaperQuestionsRequest {

    /** 与分页上限同量级，防误传全库 */
    @NotEmpty(message = "题目列表不能为空")
    @Size(max = 100, message = "单次批量至多 100 题")
    @Valid
    private List<Item> items;

    /** 单项：questionId 必填，score 可选（缺省用题目默认分）。 */
    @Data
    public static class Item {

        @NotNull(message = "题目 ID 不能为空")
        private Long questionId;

        /** 试卷内分值（可覆盖题目默认分；不传则用默认分） */
        @DecimalMin(value = "0.5", message = "分值最小 0.5")
        @DecimalMax(value = "999.9", message = "分值最大 999.9")
        private BigDecimal score;
    }
}
