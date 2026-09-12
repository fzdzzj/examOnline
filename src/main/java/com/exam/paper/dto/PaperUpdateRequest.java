package com.exam.paper.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新试卷元信息请求：字段均可选，传了才更新。
 * 若试卷已有题目且传了 totalScore，则校验"各题分值之和 = 总分"（spec「总分校验」场景）。
 */
@Data
public class PaperUpdateRequest {

    @Size(max = 128, message = "标题最长 128 字")
    private String title;

    @Size(max = 512, message = "描述最长 512 字")
    private String description;

    @DecimalMin(value = "0", message = "总分不能为负")
    @DecimalMax(value = "9999.9", message = "总分过大")
    private BigDecimal totalScore;
}
