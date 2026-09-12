package com.exam.paper.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 标签随机抽题请求（spec「标签随机抽题」需求）：
 * rules 为多规则组合——每条规则独立抽取 count 题，各规则数量配比即"比例"组卷
 * （如：单选×5 + 判断×3）。规则内 tagIds 为 OR 语义（命中任一标签即可）。
 */
@Data
public class RandomDrawRequest {

    @Valid
    @NotEmpty(message = "抽题规则不能为空")
    private List<Rule> rules;

    /** 单条抽题规则：条件（题型/难度/标签）可任意组合，均可选 */
    @Data
    public static class Rule {

        /** 题型：1单选 2多选 3判断 4简答（可选） */
        @Min(value = 1, message = "非法题型")
        @Max(value = 4, message = "非法题型")
        private Integer type;

        /** 难度：1易 2中 3难（可选） */
        @Min(value = 1, message = "非法难度")
        @Max(value = 3, message = "非法难度")
        private Integer difficulty;

        /** 标签 ID 列表（可选，OR 语义） */
        private List<Long> tagIds;

        /** 本规则抽取数量 */
        @NotNull(message = "抽取数量不能为空")
        @Min(value = 1, message = "抽取数量至少 1")
        private Integer count;
    }
}
