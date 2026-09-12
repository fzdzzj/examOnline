package com.exam.question.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 题目响应：choices 反序列化为选项文本列表；correctAnswer 为归一化后的存储值。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuestionResponse {

    private Long id;

    /** 1单选 2多选 3判断 4简答 */
    private Integer type;

    /** 题型中文名（单选/多选/判断/简答） */
    private String typeName;

    private String content;

    /** 选项文本列表（判断/简答为空列表） */
    private List<String> choices;

    /** 归一化后的答案 */
    private String correctAnswer;

    private BigDecimal score;

    /** 1易 2中 3难 */
    private Integer difficulty;

    private String analysis;

    /** 关联标签 */
    private List<TagResponse> tags;

    private Long createdBy;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
