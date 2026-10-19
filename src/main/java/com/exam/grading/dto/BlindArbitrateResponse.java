package com.exam.grading.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 仲裁终审响应（add-subjective-double-blind-grading）：
 * status=ARBITRATED（已仲裁结案），finalScore=仲裁终分（已回填 subjective_grades.score）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlindArbitrateResponse {

    /** 状态：ARBITRATED */
    private String status;

    /** 仲裁终分（终审口径，已回填） */
    private BigDecimal finalScore;
}
