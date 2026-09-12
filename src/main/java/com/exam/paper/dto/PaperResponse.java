package com.exam.paper.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 试卷列表项响应。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaperResponse {

    private Long id;

    private String title;

    private String description;

    private BigDecimal totalScore;

    private Integer questionCount;

    /** 0=草稿 1=已锁定 */
    private Integer status;

    private Long snapshotId;

    private Long createdBy;

    private LocalDateTime createdTime;
}
