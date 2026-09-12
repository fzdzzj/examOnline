package com.exam.question.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建题目请求。
 * correctAnswer 接受多种录入格式（如 "a"、"B,A,C"、"正确"），入库前由
 * {@link com.exam.question.support.AnswerNormalizer} 归一化；choices 按顺序对应选项 A/B/C...
 */
@Data
public class QuestionCreateRequest {

    /** 题型：1单选 2多选 3判断 4简答 */
    @NotNull(message = "题型不能为空")
    @Min(value = 1, message = "非法题型")
    @Max(value = 4, message = "非法题型")
    private Integer type;

    /** 题干 */
    @NotBlank(message = "题干不能为空")
    @Size(max = 5000, message = "题干最长 5000 字")
    private String content;

    /** 客观题选项文本（单选/多选必填 2-26 项）；判断/简答无需提供 */
    @Size(max = 26, message = "选项最多 26 个")
    private List<String> choices;

    /** 原始答案（入库前归一化） */
    @NotBlank(message = "正确答案不能为空")
    @Size(max = 512, message = "答案最长 512 字")
    private String correctAnswer;

    /** 默认分值（组卷可在试卷内覆盖，互不影响） */
    @NotNull(message = "分值不能为空")
    @DecimalMin(value = "0.5", message = "分值最小 0.5")
    @DecimalMax(value = "999.9", message = "分值最大 999.9")
    private BigDecimal score;

    /** 难度：1易 2中 3难 */
    @NotNull(message = "难度不能为空")
    @Min(value = 1, message = "非法难度")
    @Max(value = 3, message = "非法难度")
    private Integer difficulty;

    /** 答案解析（可选） */
    @Size(max = 2000, message = "解析最长 2000 字")
    private String analysis;

    /** 关联标签 ID 列表（可空，题目可多选关联） */
    private List<Long> tagIds;
}
