package com.exam.paper.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 试卷详情响应：元信息 + 按题号排序的题目项。已锁定试卷的权威内容以快照为准。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaperDetailResponse {

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

    private LocalDateTime updatedTime;

    private List<PaperQuestionItemResponse> questions;
}
