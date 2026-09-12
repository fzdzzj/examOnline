package com.exam.paper.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 试卷快照响应（spec「快照锁定」场景）：
 * content 为生成的快照 JSON（题目/答案/分值/顺序的不可变副本）；
 * 快照生成后重复读取返回完全一致的内容，刷新不换题。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaperSnapshotResponse {

    private Long id;

    private Long paperId;

    private Integer version;

    private BigDecimal totalScore;

    private Integer questionCount;

    /** 快照内容（解析后的 JSON） */
    private JsonNode content;

    private LocalDateTime createdTime;
}
