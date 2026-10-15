package com.exam.question.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量标签请求：对多道题目批量追加（BIND）或解绑（UNBIND）标签。
 *
 * <p>{@code questionIds} 与 {@code tagIds} 均不允许为空（400），单次上限 100 题、
 * 单个请求内标签至多 50 个，防误传全库/超大批次。与 {@link BatchDeleteQuestionsRequest}
 * 的批量口径保持一致。
 */
@Data
public class BatchTagsRequest {

    public static final String ACTION_BIND = "BIND";
    public static final String ACTION_UNBIND = "UNBIND";

    @NotEmpty(message = "题目列表不能为空")
    @Size(max = 100, message = "单次批量至多 100 题")
    private List<Long> questionIds;

    @NotEmpty(message = "标签列表不能为空")
    @Size(max = 50, message = "单次批量至多 50 个标签")
    private List<Long> tagIds;

    /** BIND=追加打标 / UNBIND=解绑剥离 */
    @NotNull(message = "操作类型不能为空")
    private String action;
}