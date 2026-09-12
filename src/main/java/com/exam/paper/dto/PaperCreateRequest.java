package com.exam.paper.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 创建试卷请求。total_score 为教师申报总分，可先申报后加题，保存/生成快照时校验一致。 */
@Data
public class PaperCreateRequest {

    @NotBlank(message = "试卷标题不能为空")
    @Size(max = 128, message = "标题最长 128 字")
    private String title;

    @Size(max = 512, message = "描述最长 512 字")
    private String description;

    /** 试卷总分 */
    @NotNull(message = "试卷总分不能为空")
    @DecimalMin(value = "0", message = "总分不能为负")
    @DecimalMax(value = "9999.9", message = "总分过大")
    private BigDecimal totalScore;
}
