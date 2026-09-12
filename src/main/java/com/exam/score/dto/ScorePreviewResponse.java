package com.exam.score.dto;

import lombok.Data;

import java.util.List;

/**
 * 成绩发布前预览（spec「发布前预览」场景）：教师确认后执行发布。
 */
@Data
public class ScorePreviewResponse {

    private Long examId;

    private String examTitle;

    /** 参与排名的人数（已汇总答卷数） */
    private int summarizedCount;

    /** 部分批改答卷数（发布时将带"部分批改"标记，§7.5） */
    private int partialGradedCount;

    private List<ScoreItem> items;
}
