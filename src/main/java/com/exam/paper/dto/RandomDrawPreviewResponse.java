package com.exam.paper.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 随机抽题预览响应（spec「按规则抽题成功」场景）：
 * 教师预览抽题结果后可选择"确认入卷"或"重新抽取"（预览不落库）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RandomDrawPreviewResponse {

    /** 各规则抽取结果（与请求 rules 顺序对应） */
    private List<RuleResult> rules;

    /** 抽取总题数 */
    private int total;

    /** 单条规则的抽取结果 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleResult {

        /** 规则序号（0 起，对应请求 rules 下标） */
        private int ruleIndex;

        /** 该规则抽取数量 */
        private int count;

        /** 抽中的题目（简表） */
        private List<DrawnQuestion> questions;
    }

    /** 抽中题目简表项（不含答案，预览轻量化） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DrawnQuestion {

        private Long id;

        private Integer type;

        private String typeName;

        private String content;

        private BigDecimal score;

        private Integer difficulty;
    }
}
