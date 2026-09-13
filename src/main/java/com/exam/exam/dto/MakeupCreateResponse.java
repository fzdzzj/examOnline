package com.exam.exam.dto;

/**
 * 创建补考结果：独立考试记录 + 名单人数。
 */
public record MakeupCreateResponse(Long examId, String title, Long parentExamId,
                                   String makeupScoreRule, int candidateCount) {
}