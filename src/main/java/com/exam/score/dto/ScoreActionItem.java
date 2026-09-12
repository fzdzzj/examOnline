package com.exam.score.dto;

/**
 * 批量发布/撤回的单场结果：批量操作不因个别考试状态不符而整体失败（部分成功语义）。
 */
public record ScoreActionItem(Long examId, boolean success, String message) {
}
