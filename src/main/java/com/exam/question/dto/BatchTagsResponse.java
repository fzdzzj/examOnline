package com.exam.question.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量标签结果（逐题结果信封）：与 {@link BatchDeleteQuestionsResponse} 同构。
 * 合法（通过所有权校验）题目的 questionId 放入 {@code succeeded}，越权/不存在题目
 * 放入 {@code failed}（逐题 reason 与单题路径文案一致）。全部失败仍返回 200，
 * 请求参数校验失败（空/超限/非法 action）才是 400。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchTagsResponse {

    /** 通过所有权校验、被处理的题目 ID */
    private List<Long> succeeded;

    /** 逐题失败项：id + 越权/不存在等原因 */
    private List<FailedItem> failed;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedItem {

        private Long id;

        private String reason;
    }
}