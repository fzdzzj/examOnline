package com.exam.adaptive.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * CAT 能力报告（仅会话归属人可查）：θ、SE、正确率、终止原因与逐题轨迹。
 */
@Data
public class AdaptiveQuizReportResponse {

    private Long sessionId;

    private Long studentId;

    private String status;

    /** 最终能力估计 θ */
    private BigDecimal theta;

    /** 最终标准误 SE */
    private BigDecimal se;

    private Integer answeredCount;

    private Integer maxItems;

    private Integer correctCount;

    /** 正确率百分比（0~100，保留 2 位） */
    private BigDecimal accuracy;

    /** 终止原因：SE_CONVERGED / MAX_ITEMS / POOL_EXHAUSTED；进行中为 null */
    private String terminationReason;

    private List<AdaptiveQuizReportItem> items = new ArrayList<>();
}
